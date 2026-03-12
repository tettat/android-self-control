package com.control.app.log

import android.content.Context
import android.util.Log
import com.control.app.agent.AgentEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class ExecutionLogServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 0,
    val accessUrls: List<String> = emptyList(),
    val errorMessage: String? = null
)

class ExecutionLogHttpServer(
    context: Context,
    private val agentEngine: AgentEngine,
    private val preferredPort: Int = 8976
) {

    companion object {
        private const val TAG = "ExecutionLogHttpServer"
        private const val BIND_HOST = "0.0.0.0"
        private const val DEFAULT_LIMIT = 200
        private const val MAX_LIMIT = 1000
        private val PREFERRED_INTERFACE_PREFIXES = listOf("wlan", "wifi", "ap", "swlan", "eth")
        private val EXCLUDED_INTERFACE_PREFIXES = listOf("lo", "tun", "ppp", "rmnet", "ccmni", "v4-rmnet", "v4", "veth", "docker", "bridge")
    }

    private val appContext = context.applicationContext

    private val _status = MutableStateFlow(ExecutionLogServerStatus())
    val status: StateFlow<ExecutionLogServerStatus> = _status.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptExecutor: ExecutorService? = null
    private var requestExecutor: ExecutorService? = null

    @Synchronized
    fun start() {
        if (running) {
            refreshStatus()
            return
        }

        try {
            val socket = bindServerSocket()
            val requestPool = Executors.newCachedThreadPool()
            val acceptPool = Executors.newSingleThreadExecutor()

            serverSocket = socket
            requestExecutor = requestPool
            acceptExecutor = acceptPool
            running = true
            updateStatus(errorMessage = null)

            acceptPool.execute { acceptLoop(socket) }
            Log.i(TAG, "Execution log server listening on ${socket.localPort}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start execution log server", e)
            running = false
            runCatching { serverSocket?.close() }
            serverSocket = null
            requestExecutor?.shutdownNow()
            acceptExecutor?.shutdownNow()
            requestExecutor = null
            acceptExecutor = null
            updateStatus(errorMessage = "日志服务启动失败: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor?.shutdownNow()
        requestExecutor?.shutdownNow()
        acceptExecutor = null
        requestExecutor = null
        updateStatus()
    }

    fun refreshStatus() {
        updateStatus()
    }

    private fun bindServerSocket(): ServerSocket {
        return try {
            createServerSocket(preferredPort)
        } catch (preferredError: Exception) {
            Log.w(TAG, "Preferred port $preferredPort unavailable, falling back to random port", preferredError)
            createServerSocket(0)
        }
    }

    private fun createServerSocket(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(BIND_HOST, port))
        }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                requestExecutor?.execute { handleClient(client) } ?: client.close()
            } catch (e: SocketException) {
                if (running) {
                    Log.e(TAG, "Socket error in accept loop", e)
                }
                break
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Unexpected accept loop error", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { client ->
                client.soTimeout = 5_000

                val reader = client.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val requestLine = reader.readLine() ?: return

                while (true) {
                    val headerLine = reader.readLine() ?: break
                    if (headerLine.isEmpty()) break
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    sendText(client, 400, "Bad Request", "请求格式无效")
                    return
                }

                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                if (method != "GET") {
                    sendText(client, 405, "Method Not Allowed", "仅支持 GET 请求")
                    return
                }

                val uri = try {
                    URI(target)
                } catch (_: Exception) {
                    sendText(client, 400, "Bad Request", "请求地址无效")
                    return
                }

                val path = uri.path ?: "/"
                val query = parseQuery(uri.rawQuery)

                when (path) {
                    "/", "" -> sendHtml(client, buildHtmlPage(query))
                    "/api/logs" -> sendJson(client, buildLogsPayload(query))
                    "/api/sessions" -> sendJson(client, buildSessionsPayload())
                    "/health" -> sendJson(client, buildHealthPayload())
                    else -> sendText(client, 404, "Not Found", "未找到对应接口")
                }
            }
        } catch (_: SocketTimeoutException) {
            Log.w(TAG, "Client timed out before completing request")
        } catch (_: SocketException) {
            Log.w(TAG, "Client socket closed while handling request")
        } catch (_: IOException) {
            Log.w(TAG, "I/O error while handling execution log request")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error while handling execution log request", e)
        }
    }

    private fun buildLogsPayload(query: Map<String, String>): JSONObject {
        val sessionId = query["sessionId"]?.takeIf { it.isNotBlank() }
        val includeImages = query["includeImages"].toBooleanFlag()
        val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

        val sessions = agentEngine.sessionManager.sessions.value
        val selectedSession = sessionId?.let { id -> sessions.find { it.id == id } }
        val entries = selectedSession?.debugEntries ?: agentEngine.debugLog.value
        val snapshot = status.value

        return ExecutionLogFormatter.buildExportJson(
            context = appContext,
            agentState = agentEngine.agentState.value,
            entries = entries,
            sessions = sessions,
            serverPort = snapshot.port.takeIf { it > 0 },
            accessUrls = snapshot.accessUrls,
            selectedSession = selectedSession,
            includeImages = includeImages,
            requestedLimit = limit
        ).apply {
            put(
                "request",
                JSONObject().apply {
                    put("sessionId", sessionId ?: JSONObject.NULL)
                    put("limit", limit)
                    put("includeImages", includeImages)
                }
            )
            put("entryScope", if (selectedSession != null) "session" else "global")
        }
    }

    private fun buildSessionsPayload(): JSONObject {
        val sessions = agentEngine.sessionManager.sessions.value
        val snapshot = status.value

        return JSONObject().apply {
            put("exportTime", ExecutionLogFormatter.buildExportTime())
            put("deviceInfo", ExecutionLogFormatter.buildDeviceInfo())
            put("appVersion", ExecutionLogFormatter.buildAppVersion(appContext))
            put("currentSessionId", agentEngine.sessionManager.currentSession.value?.id ?: JSONObject.NULL)
            put("agentState", ExecutionLogFormatter.buildAgentStateJson(agentEngine.agentState.value))
            put("sessions", ExecutionLogFormatter.buildSessionsJson(sessions))
            put(
                "logServer",
                JSONObject().apply {
                    put("port", snapshot.port)
                    put("accessUrls", JSONArray(snapshot.accessUrls))
                }
            )
        }
    }

    private fun buildHealthPayload(): JSONObject {
        val snapshot = status.value
        return JSONObject().apply {
            put("status", if (snapshot.isRunning) "ok" else "stopped")
            put("exportTime", ExecutionLogFormatter.buildExportTime())
            put("deviceInfo", ExecutionLogFormatter.buildDeviceInfo())
            put("appVersion", ExecutionLogFormatter.buildAppVersion(appContext))
            put("port", snapshot.port)
            put("accessUrls", JSONArray(snapshot.accessUrls))
            put("errorMessage", snapshot.errorMessage ?: JSONObject.NULL)
            put("agentState", ExecutionLogFormatter.buildAgentStateJson(agentEngine.agentState.value))
        }
    }

    private fun buildHtmlPage(query: Map<String, String>): String {
        val sessionId = query["sessionId"]?.takeIf { it.isNotBlank() }
        val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        val sessions = agentEngine.sessionManager.sessions.value
        val selectedSession = sessionId?.let { id -> sessions.find { it.id == id } }
        val entries = (selectedSession?.debugEntries ?: agentEngine.debugLog.value)
            .takeLast(limit)
            .asReversed()
        val snapshot = status.value
        val agentState = agentEngine.agentState.value

        val sessionTabs = buildString {
            append("""<a class="session-tab${if (selectedSession == null) " active" else ""}" href="/?limit=$limit">全部日志</a>""")
            for (session in sessions) {
                val encodedId = URLEncoder.encode(session.id, StandardCharsets.UTF_8.name())
                append(
                    """<a class="session-tab${if (selectedSession?.id == session.id) " active" else ""}" href="/?sessionId=$encodedId&limit=$limit">${escapeHtml(session.title)}</a>"""
                )
            }
        }

        val sessionCards = if (sessions.isEmpty()) {
            """<div class="empty">暂无会话记录</div>"""
        } else {
            buildString {
                for (session in sessions) {
                    append(
                        """
                        <div class="session-card">
                          <div class="session-title">${escapeHtml(session.title)}</div>
                          <div class="meta">${if (session.isActive) "进行中" else "已结束"} · ${session.debugEntries.size} 条日志 · ${ExecutionLogFormatter.formatTimestamp(session.lastActiveAt)}</div>
                        </div>
                        """.trimIndent()
                    )
                }
            }
        }

        val entryCards = if (entries.isEmpty()) {
            """<div class="empty">暂无执行日志</div>"""
        } else {
            buildString {
                for (entry in entries) {
                    append(
                        """
                        <article class="entry ${entry.type.name.lowercase(Locale.US)}">
                          <div class="entry-header">
                            <span class="entry-type">${entry.type.name}</span>
                            <span class="entry-time">${ExecutionLogFormatter.formatTimestamp(entry.timestamp)}</span>
                          </div>
                          <h3>${escapeHtml(entry.title)}</h3>
                          <pre>${escapeHtml(entry.content)}</pre>
                          ${buildEntryImagesHtml(entry.images)}
                        </article>
                        """.trimIndent()
                    )
                }
            }
        }

        val accessUrls = snapshot.accessUrls.joinToString("") { url ->
            """<li><a href="${escapeHtml(url)}">${escapeHtml(url)}</a></li>"""
        }
        val selectedTitle = selectedSession?.title ?: "全部日志"
        val apiBase = buildString {
            append("/api/logs?limit=$limit")
            if (selectedSession != null) {
                val encodedId = URLEncoder.encode(selectedSession.id, StandardCharsets.UTF_8.name())
                append("&sessionId=$encodedId")
            }
        }
        val stepTimingItems = agentState.stepTimings.mapIndexed { index, step ->
            val presentation = ExecutionLogFormatter.describeStepTiming(step)
            val breakdownSummary = ExecutionLogFormatter.formatTimingBreakdown(step.breakdown)
            """
                <li>
                  <strong>${index + 1}. [${escapeHtml(presentation.category)}] ${escapeHtml(presentation.title)}</strong>
                  <div class="meta">耗时: ${escapeHtml(ExecutionLogFormatter.formatDurationMs(step.durationMs))}</div>
                  ${if (breakdownSummary.isNotBlank()) """<div class="meta">${escapeHtml(breakdownSummary)}</div>""" else ""}
                  ${if (presentation.subtitle.isNotBlank()) """<div class="meta">${escapeHtml(presentation.subtitle)}</div>""" else ""}
                  ${if (step.toolArguments.isNotBlank()) """<div class="meta">参数: ${escapeHtml(step.toolArguments)}</div>""" else ""}
                  ${if (step.intent.isNotBlank()) """<div class="meta">意图: ${escapeHtml(step.intent)}</div>""" else ""}
                </li>
            """.trimIndent()
        }.joinToString("")
        val stepTimingSection = if (agentState.stepTimings.isEmpty()) {
            """<div class="meta">任务完成或终止后，会在这里展示每一步耗时。</div>"""
        } else {
            """<ul class="meta">$stepTimingItems</ul>"""
        }

        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta http-equiv="refresh" content="5">
              <title>Control 执行日志</title>
              <style>
                :root {
                  --bg: #f5f2ee;
                  --panel: #fff;
                  --ink: #1a1a1a;
                  --muted: #6b6b6b;
                  --line: #e0ddd8;
                  --accent: #17624d;
                  --warn: #9f3a1d;
                }
                * { box-sizing: border-box; }
                body { margin: 0; font-family: system-ui, sans-serif; background: var(--bg); color: var(--ink); font-size: 15px; line-height: 1.5; }
                .page { max-width: 900px; margin: 0 auto; padding: 20px 16px 40px; }
                .head {
                  display: flex;
                  flex-wrap: wrap;
                  align-items: baseline;
                  gap: 12px 20px;
                  margin-bottom: 16px;
                  padding-bottom: 12px;
                  border-bottom: 1px solid var(--line);
                }
                .head h1 { margin: 0; font-size: 1.25rem; font-weight: 600; }
                .head .meta { color: var(--muted); font-size: 13px; }
                .status { font-size: 13px; padding: 4px 10px; border-radius: 6px; background: ${if (agentState.isRunning) "#e8f4f0" else "#fce8e4"}; color: ${if (agentState.isRunning) "var(--accent)" else "var(--warn)"}; }
                .bar {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px 16px;
                  font-size: 13px;
                  color: var(--muted);
                  margin-bottom: 16px;
                }
                .bar strong { color: var(--ink); }
                .links { font-size: 13px; }
                .links a { color: var(--accent); text-decoration: none; }
                .links a:hover { text-decoration: underline; }
                .session-tabs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 20px; }
                .session-tab {
                  padding: 6px 12px;
                  border-radius: 6px;
                  border: 1px solid var(--line);
                  color: var(--ink);
                  text-decoration: none;
                  font-size: 13px;
                  background: var(--panel);
                }
                .session-tab.active { background: var(--accent); border-color: var(--accent); color: #fff; }
                .timing { font-size: 13px; color: var(--muted); margin-top: 8px; }
                .timing ul { margin: 4px 0 0; padding-left: 18px; }
                .timing .meta { color: var(--muted); font-size: 12px; margin-top: 2px; }
                .log-list { display: flex; flex-direction: column; gap: 10px; }
                .entry {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 10px;
                  padding: 12px 14px;
                }
                .entry-header { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 6px; font-size: 12px; color: var(--muted); }
                .entry-type { font-weight: 600; color: var(--ink); }
                .entry h3 { margin: 0 0 6px; font-size: 14px; font-weight: 600; }
                .entry pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.5; font-family: ui-monospace, monospace; }
                .entry-image { margin-top: 10px; }
                .entry-image img { display: block; max-width: 100%; border-radius: 8px; border: 1px solid var(--line); }
                .entry.error { border-left: 3px solid #d32f2f; }
                .entry.info { border-left: 3px solid #2e7d32; }
                .entry.action_executed { border-left: 3px solid #1976d2; }
                .entry.api_request, .entry.api_response { border-left: 3px solid #7b1fa2; }
                .empty { padding: 24px; text-align: center; color: var(--muted); font-size: 14px; background: var(--panel); border: 1px dashed var(--line); border-radius: 10px; }
              </style>
            </head>
            <body>
              <main class="page">
                <div class="head">
                  <h1>执行日志</h1>
                  <span class="meta">${escapeHtml(selectedTitle)} · 每 5 秒刷新</span>
                  <span class="status">${if (agentState.isRunning) "执行中" else "空闲"} · ${escapeHtml(agentState.statusMessage)}</span>
                </div>
                <div class="bar">
                  <span><strong>最近:</strong> ${escapeHtml(agentState.lastAction.ifBlank { "—" })}</span>
                  <span><strong>轮次:</strong> ${agentState.currentRound}/${agentState.maxRounds}</span>
                  <span class="links">
                    <a href="/">/</a> · <a href="$apiBase">/api/logs</a> · <a href="/api/sessions">/api/sessions</a> · <a href="/health">/health</a>
                  </span>
                </div>
                ${if (agentState.stepTimings.isNotEmpty()) """<div class="timing"><strong>步骤耗时</strong>$stepTimingSection</div>""" else ""}
                <div class="session-tabs">$sessionTabs</div>
                <section class="log-list">$entryCards</section>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun sendHtml(socket: Socket, body: String) {
        sendResponse(
            socket = socket,
            statusCode = 200,
            reason = "OK",
            contentType = "text/html; charset=utf-8",
            body = body.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendJson(socket: Socket, payload: JSONObject) {
        sendResponse(
            socket = socket,
            statusCode = 200,
            reason = "OK",
            contentType = "application/json; charset=utf-8",
            body = payload.toString(2).toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendText(socket: Socket, statusCode: Int, reason: String, message: String) {
        sendResponse(
            socket = socket,
            statusCode = statusCode,
            reason = reason,
            contentType = "text/plain; charset=utf-8",
            body = "$message\n".toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendResponse(
        socket: Socket,
        statusCode: Int,
        reason: String,
        contentType: String,
        body: ByteArray
    ) {
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 $statusCode $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun updateStatus(errorMessage: String? = _status.value.errorMessage) {
        val port = serverSocket?.localPort ?: 0
        _status.value = ExecutionLogServerStatus(
            isRunning = running && port > 0,
            port = port,
            accessUrls = if (running && port > 0) resolveAccessUrls(port) else emptyList(),
            errorMessage = errorMessage
        )
    }

    private fun resolveAccessUrls(port: Int): List<String> {
        val preferredUrls = linkedSetOf<String>()
        val fallbackUrls = linkedSetOf<String>()

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf("http://127.0.0.1:$port/")
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name.orEmpty().lowercase(Locale.US)
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                if (EXCLUDED_INTERFACE_PREFIXES.any { prefix -> name.startsWith(prefix) }) continue

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        val host = address.hostAddress ?: continue
                        val url = "http://$host:$port/"
                        if (PREFERRED_INTERFACE_PREFIXES.any { prefix -> name.startsWith(prefix) }) {
                            preferredUrls += url
                        } else {
                            fallbackUrls += url
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve access URLs", e)
        }

        val urls = linkedSetOf<String>()
        urls += preferredUrls
        urls += fallbackUrls
        urls += "http://127.0.0.1:$port/"
        return urls.toList()
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val parts = pair.split("=", limit = 2)
                val key = decode(parts[0])
                if (key.isBlank()) return@mapNotNull null
                val value = if (parts.size > 1) decode(parts[1]) else ""
                key to value
            }
            .toMap()
    }

    private fun buildEntryImagesHtml(images: List<com.control.app.agent.DebugLogImage>): String {
        if (images.isEmpty()) return ""
        return images.mapIndexed { index, image ->
            val mimeType = image.mimeType?.ifBlank { null } ?: "image/png"
            """
                <div class="entry-image">
                  <div class="meta">图片 ${index + 1}/${images.size}</div>
                  <img src="${escapeHtml("data:$mimeType;base64,${image.base64}")}" alt="日志图片预览 ${index + 1}" loading="lazy">
                </div>
            """.trimIndent()
        }.joinToString("\n")
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun String?.toBooleanFlag(): Boolean =
        this.equals("1", ignoreCase = true) ||
            this.equals("true", ignoreCase = true) ||
            this.equals("yes", ignoreCase = true)
}
