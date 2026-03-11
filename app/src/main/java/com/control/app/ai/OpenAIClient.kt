package com.control.app.ai

import android.util.Log
import com.control.app.agent.AIChatResult
import com.control.app.agent.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
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

    companion object {
        private const val TAG = "OpenAIClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = OkHttpClient.Builder()
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
        modelName: String
    ): Result<AIChatResult> = withContext(Dispatchers.IO) {
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

            val responseBody = executeRequest(request)
            parseToolResponse(responseBody)
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

    private suspend fun executeRequest(request: Request): String {
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) {
                        continuation.resumeWithException(
                            RuntimeException("Network request failed: ${e.message}", e)
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: ""

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

                        continuation.resume(body)
                    } catch (e: Exception) {
                        continuation.resumeWithException(
                            RuntimeException("Failed to read response: ${e.message}", e)
                        )
                    }
                }
            })
        }
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

            val responseBody = executeRequest(request)
            val chatResponse = json.decodeFromString<ChatResponse>(responseBody)
            chatResponse.choices.firstOrNull()?.message?.content ?: ""
        }
    }

    fun shutdown() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
