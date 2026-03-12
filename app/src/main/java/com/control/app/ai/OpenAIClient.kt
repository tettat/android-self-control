package com.control.app.ai

import android.util.Log
import com.control.app.agent.AIChatResult
import com.control.app.agent.DebugLogImage
import com.control.app.agent.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAIClient {

    data class RequestTiming(
        val totalMs: Long,
        val uploadMs: Long? = null,
        val modelMs: Long? = null
    )

    data class ToolChatResponse(
        val result: AIChatResult,
        val timing: RequestTiming
    )

    data class HttpLogEntry(
        val title: String,
        val content: String,
        val imageBase64: String? = null,
        val imageMimeType: String? = null,
        val images: List<DebugLogImage> = imageBase64?.let {
            listOf(DebugLogImage(base64 = it, mimeType = imageMimeType))
        } ?: emptyList()
    )

    companion object {
        private const val TAG = "OpenAIClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DATA_IMAGE_URL_REGEX = Regex(
            pattern = "^data:(image/[^;]+);base64,(.+)$",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }

    private data class ExecutedRequest(
        val body: String,
        val timing: RequestTiming
    )

    private class CallTimingCollector : EventListener() {
        private var callStartNs: Long? = null
        private var requestBodyStartNs: Long? = null
        private var requestBodyEndNs: Long? = null
        private var responseHeadersStartNs: Long? = null
        private var responseBodyEndNs: Long? = null
        private var callEndNs: Long? = null

        override fun callStart(call: Call) {
            callStartNs = System.nanoTime()
        }

        override fun requestBodyStart(call: Call) {
            requestBodyStartNs = System.nanoTime()
        }

        override fun requestBodyEnd(call: Call, byteCount: Long) {
            if (requestBodyStartNs == null) {
                requestBodyStartNs = callStartNs
            }
            requestBodyEndNs = System.nanoTime()
        }

        override fun responseHeadersStart(call: Call) {
            responseHeadersStartNs = System.nanoTime()
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            responseBodyEndNs = System.nanoTime()
        }

        override fun callEnd(call: Call) {
            callEndNs = System.nanoTime()
        }

        override fun callFailed(call: Call, ioe: IOException) {
            callEndNs = System.nanoTime()
        }

        fun snapshot(): RequestTiming {
            val totalMs = durationMs(
                startNs = callStartNs ?: requestBodyStartNs ?: responseHeadersStartNs,
                endNs = callEndNs ?: responseBodyEndNs ?: responseHeadersStartNs ?: requestBodyEndNs
            ) ?: 0L
            val uploadMs = durationMs(
                startNs = requestBodyStartNs ?: callStartNs,
                endNs = requestBodyEndNs
            )
            val modelMs = durationMs(
                startNs = requestBodyEndNs ?: requestBodyStartNs ?: callStartNs,
                endNs = responseHeadersStartNs
            )
            return RequestTiming(
                totalMs = totalMs,
                uploadMs = uploadMs,
                modelMs = modelMs
            )
        }

        private fun durationMs(startNs: Long?, endNs: Long?): Long? {
            if (startNs == null || endNs == null) return null
            return TimeUnit.NANOSECONDS.toMillis((endNs - startNs).coerceAtLeast(0L))
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(90, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Send a chat completion request with tool definitions.
     * Returns an [AIChatResult] distinguishing between text responses and tool call requests.
     */
    suspend fun chatWithTools(
        messages: JsonArray,
        tools: JsonArray,
        apiEndpoint: String,
        apiKey: String,
        modelName: String,
        logHttp: ((HttpLogEntry) -> Unit)? = null
    ): Result<ToolChatResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = buildToolRequestBody(modelName, messages, tools)
            val requestJson = json.encodeToString(requestBody)

            val endpoint = apiEndpoint.trimEnd('/')
            val url = "$endpoint/chat/completions"

            Log.d(TAG, "Sending tool request to: $url (${messages.size} messages, ${tools.size} tools)")
            Log.d(TAG, "Model: $modelName")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            logHttp?.invoke(buildRequestLog(request, requestJson))

            val response = executeRequest(request, logHttp)
            ToolChatResponse(
                result = parseToolResponse(response.body),
                timing = response.timing
            )
        }
    }

    /**
     * Build the request body including tools and tool_choice.
     */
    private fun buildToolRequestBody(
        modelName: String,
        messages: JsonArray,
        tools: JsonArray
    ): JsonObject {
        val model = modelName.lowercase()
        val usesNewTokenParam = model.startsWith("o1") || model.startsWith("o3") ||
                model.startsWith("o4") || model.contains("gpt-5")
        return buildJsonObject {
            put("model", modelName)
            put("messages", messages)
            put("tools", tools)
            put("tool_choice", "auto")
            if (!usesNewTokenParam) {
                put("temperature", 0.1)
            }
            val tokenKey = if (usesNewTokenParam) "max_completion_tokens" else "max_tokens"
            put(tokenKey, 4096)
        }
    }

    /**
     * Parse the API response, determining whether the AI returned text or tool calls.
     */
    private fun parseToolResponse(responseBody: String): AIChatResult {
        Log.d(TAG, "Raw API response: ${responseBody.take(500)}")

        val chatResponse = try {
            json.decodeFromString<ChatResponse>(responseBody)
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse API response structure: ${e.message}", e)
        }

        val message = chatResponse.choices.firstOrNull()?.message
            ?: throw RuntimeException("API response has no message. Choices: ${chatResponse.choices}")

        val finishReason = chatResponse.choices.firstOrNull()?.finishReason

        // Check if the response contains tool calls
        val responseToolCalls = message.toolCalls
        if (!responseToolCalls.isNullOrEmpty()) {
            // Build the raw assistant message JSON (with tool_calls) for conversation history
            val rawMessage = buildAssistantToolCallMessage(message.content, responseToolCalls)

            val toolCalls = responseToolCalls.map { tc ->
                val argsJson = try {
                    json.decodeFromString<JsonObject>(tc.function.arguments)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse tool call arguments: ${tc.function.arguments}", e)
                    JsonObject(emptyMap())
                }
                ToolCall(
                    id = tc.id,
                    functionName = tc.function.name,
                    arguments = argsJson
                )
            }

            Log.d(TAG, "AI requested ${toolCalls.size} tool call(s): ${toolCalls.map { it.functionName }}")
            return AIChatResult.ToolCallResponse(toolCalls = toolCalls, rawMessage = rawMessage)
        }

        // No tool calls -- text response
        val content = message.content ?: ""
        val rawMessage = buildJsonObject {
            put("role", "assistant")
            put("content", content)
        }

        Log.d(TAG, "AI text response (finish=$finishReason): ${content.take(200)}")
        return AIChatResult.TextResponse(content = content, rawMessage = rawMessage)
    }

    /**
     * Build the raw assistant message JSON including tool_calls, suitable for
     * appending to conversation history. This preserves the exact format the
     * OpenAI API expects when continuing a conversation after tool calls.
     */
    private fun buildAssistantToolCallMessage(
        content: String?,
        toolCalls: List<ResponseToolCall>
    ): JsonObject {
        return buildJsonObject {
            put("role", "assistant")
            // content can be null when there are tool calls
            if (content != null) {
                put("content", content)
            } else {
                put("content", JsonNull)
            }
            put("tool_calls", buildJsonArray {
                for (tc in toolCalls) {
                    add(buildJsonObject {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tc.function.name)
                            put("arguments", tc.function.arguments)
                        })
                    })
                }
            })
        }
    }

    private suspend fun executeRequest(
        request: Request,
        logHttp: ((HttpLogEntry) -> Unit)?
    ): ExecutedRequest {
        return suspendCancellableCoroutine { continuation ->
            val timingCollector = CallTimingCollector()
            val call = httpClient.newBuilder()
                .eventListener(timingCollector)
                .build()
                .newCall(request)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val timing = timingCollector.snapshot()
                    logHttp?.invoke(
                        HttpLogEntry(
                            title = "AI 请求失败",
                            content = buildString {
                                appendLine("Method: ${request.method}")
                                appendLine("URL: ${request.url}")
                                appendLine("Error: ${e.message}")
                                appendLine("total_ms: ${timing.totalMs}")
                                timing.uploadMs?.let { appendLine("upload_ms: $it") }
                                timing.modelMs?.let { appendLine("model_ms: $it") }
                            }.trimEnd()
                        )
                    )
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(
                            RuntimeException("Network request failed: ${e.message}", e)
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: ""
                        val timing = timingCollector.snapshot()
                        logHttp?.invoke(
                            HttpLogEntry(
                                title = "AI 响应详情",
                                content = buildResponseLog(response, body, timing)
                            )
                        )

                        if (!response.isSuccessful) {
                            val errorMsg = try {
                                val errorResponse = json.decodeFromString<ApiErrorResponse>(body)
                                errorResponse.error?.message ?: "Unknown API error"
                            } catch (_: Exception) {
                                body.ifBlank { "HTTP ${response.code}: ${response.message}" }
                            }
                            continuation.resumeWithException(
                                RuntimeException("API error (${response.code}): $errorMsg")
                            )
                            return
                        }

                        continuation.resume(ExecutedRequest(body = body, timing = timing))
                    } catch (e: Exception) {
                        continuation.resumeWithException(
                            RuntimeException("Failed to read response: ${e.message}", e)
                        )
                    }
                }
            })
        }
    }

    private data class EmbeddedImageSummary(
        val mimeType: String,
        val base64Chars: Int,
        val image: DebugLogImage
    ) {
        val estimatedBytes: Long
            get() = if (base64Chars <= 0) 0L else (base64Chars.toLong() * 3L) / 4L
    }

    private data class RequestLogPresentation(
        val body: String,
        val images: List<DebugLogImage> = emptyList()
    )

    private fun buildRequestLog(request: Request, requestJson: String): HttpLogEntry {
        val presentation = formatRequestBodyForLog(requestJson)
        return HttpLogEntry(
            title = "AI 请求详情",
            content = buildString {
                appendLine("Method: ${request.method}")
                appendLine("URL: ${request.url}")
                appendLine("Headers:")
                appendLine(formatHeaders(request.headers))
                appendLine("Body:")
                append(presentation.body)
            },
            imageBase64 = presentation.images.firstOrNull()?.base64,
            imageMimeType = presentation.images.firstOrNull()?.mimeType,
            images = presentation.images
        )
    }

    private fun buildResponseLog(
        response: Response,
        body: String,
        timing: RequestTiming
    ): String = buildString {
        appendLine("Status: ${response.code} ${response.message}")
        appendLine("URL: ${response.request.url}")
        appendLine("Timings:")
        appendLine("  total_ms: ${timing.totalMs}")
        timing.uploadMs?.let { appendLine("  upload_ms: $it") }
        timing.modelMs?.let { appendLine("  model_ms: $it") }
        appendLine("Headers:")
        appendLine(formatHeaders(response.headers))
        appendLine("Body:")
        append(body.ifBlank { "<empty>" })
    }

    private fun formatHeaders(headers: Headers): String {
        if (headers.size == 0) return "  <none>"
        return headers.names()
            .sorted()
            .joinToString("\n") { name ->
                val value = if (name.equals("Authorization", ignoreCase = true)) {
                    redactAuthorization(headers[name].orEmpty())
                } else {
                    headers.values(name).joinToString(", ")
                }
                "  $name: $value"
            }
    }

    private fun redactAuthorization(value: String): String {
        if (value.isBlank()) return value
        val trimmed = value.trim()
        val parts = trimmed.split(" ", limit = 2)
        if (parts.size != 2) return "***"
        val scheme = parts[0]
        val token = parts[1]
        val visible = token.takeLast(minOf(6, token.length))
        return "$scheme ***$visible"
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun formatRequestBodyForLog(requestJson: String): RequestLogPresentation {
        return try {
            val imageSummaries = mutableListOf<EmbeddedImageSummary>()
            val parsed = json.parseToJsonElement(requestJson)
            val sanitized = sanitizeJsonElementForLog(parsed, imageSummaries)
            val prettyJson = Json {
                prettyPrint = true
            }.encodeToString(sanitized)

            val body = if (imageSummaries.isEmpty()) {
                prettyJson
            } else {
                buildString {
                    appendLine("Embedded images: ${imageSummaries.size}")
                    imageSummaries.forEachIndexed { index, summary ->
                        appendLine(
                            "  - #${index + 1}: ${summary.mimeType}, " +
                                formatByteCount(summary.estimatedBytes) +
                                ", base64=${summary.base64Chars} chars"
                        )
                    }
                    append(prettyJson)
                }.trimEnd()
            }
            RequestLogPresentation(
                body = body,
                images = imageSummaries.map { it.image }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sanitize request body for logging", e)
            RequestLogPresentation(body = requestJson)
        }
    }

    private fun sanitizeJsonElementForLog(
        element: JsonElement,
        imageSummaries: MutableList<EmbeddedImageSummary>
    ): JsonElement {
        return when (element) {
            is JsonObject -> JsonObject(
                element.mapValues { (_, value) -> sanitizeJsonElementForLog(value, imageSummaries) }
            )

            is JsonArray -> JsonArray(
                element.map { item -> sanitizeJsonElementForLog(item, imageSummaries) }
            )

            is JsonPrimitive -> {
                val match = DATA_IMAGE_URL_REGEX.matchEntire(element.content)
                if (match != null) {
                    val mimeType = match.groupValues[1]
                    val base64Data = match.groupValues[2]
                    val summary = EmbeddedImageSummary(
                        mimeType = mimeType,
                        base64Chars = base64Data.length,
                        image = DebugLogImage(base64 = base64Data, mimeType = mimeType)
                    )
                    imageSummaries += summary
                    JsonPrimitive(
                        "<embedded image omitted: ${summary.mimeType}, " +
                            "${formatByteCount(summary.estimatedBytes)}, " +
                            "base64=${summary.base64Chars} chars>"
                    )
                } else {
                    element
                }
            }
        }
    }

    private fun formatByteCount(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1fKB", kb)
        val mb = kb / 1024.0
        return String.format("%.2fMB", mb)
    }

    /**
     * Simple chat completion without tools — used for tasks like context summarization.
     * Returns the assistant's text response.
     */
    suspend fun chatSimple(
        messages: JsonArray,
        apiEndpoint: String,
        apiKey: String,
        modelName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val requestBody = buildJsonObject {
                put("model", modelName)
                put("messages", messages)
                put("max_tokens", 1024)
                put("temperature", 0.3)
            }
            val requestJson = json.encodeToString(requestBody)

            val endpoint = apiEndpoint.trimEnd('/')
            val url = "$endpoint/chat/completions"

            Log.d(TAG, "Sending simple chat request to: $url (${messages.size} messages)")

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val responseBody = executeRequest(request, null).body
            val chatResponse = json.decodeFromString<ChatResponse>(responseBody)
            chatResponse.choices.firstOrNull()?.message?.content ?: ""
        }
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
