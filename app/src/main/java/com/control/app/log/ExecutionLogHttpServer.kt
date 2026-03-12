package com.control.app.log

import android.content.Context
import android.util.Log
import com.control.app.agent.AgentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
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
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String
    )

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

                val request = readHttpRequest(client) ?: run {
                    sendText(client, 400, "Bad Request", "请求格式无效")
                    return
                }

                if (request.method == "OPTIONS") {
                    sendEmpty(client, 204, "No Content")
                    return
                }

                if (request.path.isBlank()) {
                    sendText(client, 400, "Bad Request", "请求格式无效")
                    return
                }

                when (request.path) {
                    "/", "" -> {
                        if (request.method != "GET") {
                            sendText(client, 405, "Method Not Allowed", "仅支持 GET 请求")
                            return
                        }
                        sendHtml(client, buildHtmlPage(request.query))
                    }
                    "/api/logs" -> {
                        if (request.method != "GET") {
                            sendText(client, 405, "Method Not Allowed", "仅支持 GET 请求")
                            return
                        }
                        sendJson(client, buildLogsPayload(request.query))
                    }
                    "/api/sessions" -> {
                        if (request.method != "GET") {
                            sendText(client, 405, "Method Not Allowed", "仅支持 GET 请求")
                            return
                        }
                        sendJson(client, buildSessionsPayload())
                    }
                    "/health" -> {
                        if (request.method != "GET") {
                            sendText(client, 405, "Method Not Allowed", "仅支持 GET 请求")
                            return
                        }
                        sendJson(client, buildHealthPayload())
                    }
                    "/api/agent/command" -> {
                        if (request.method != "POST") {
                            sendText(client, 405, "Method Not Allowed", "仅支持 POST 请求")
                            return
                        }
                        handleCommandRequest(client, request.body)
                    }
                    "/api/agent/cancel" -> {
                        if (request.method != "POST") {
                            sendText(client, 405, "Method Not Allowed", "仅支持 POST 请求")
                            return
                        }
                        handleCancelRequest(client)
                    }
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

    private fun readHttpRequest(socket: Socket): HttpRequest? {
        val input = socket.getInputStream()
        val headerBytes = readHeaderBytes(input) ?: return null
        val headerText = headerBytes.toString(StandardCharsets.UTF_8)
        val lines = headerText.split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val uri = try {
            URI(target)
        } catch (_: Exception) {
            return null
        }

        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val separatorIndex = line.indexOf(':')
            if (separatorIndex <= 0) return@forEach
            val name = line.substring(0, separatorIndex).trim().lowercase(Locale.US)
            val value = line.substring(separatorIndex + 1).trim()
            headers[name] = value
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(bodyBytes, offset, contentLength - offset)
                if (read == -1) throw IOException("Unexpected end of request body")
                offset += read
            }
            bodyBytes.toString(StandardCharsets.UTF_8)
        } else {
            ""
        }

        return HttpRequest(
            method = method,
            path = uri.path ?: "/",
            query = parseQuery(uri.rawQuery),
            body = body
        )
    }

    private fun readHeaderBytes(input: java.io.InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        var matchCount = 0

        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (output.size() == 0) null else output.toByteArray()
            }
            output.write(value)
            matchCount = when {
                matchCount == 0 && value == '\r'.code -> 1
                matchCount == 1 && value == '\n'.code -> 2
                matchCount == 2 && value == '\r'.code -> 3
                matchCount == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matchCount == 4) {
                return output.toByteArray()
            }
            if (output.size() > 64 * 1024) {
                throw IOException("HTTP headers too large")
            }
        }
    }

    private fun handleCommandRequest(socket: Socket, body: String) {
        val payload = try {
            JSONObject(body.ifBlank { "{}" })
        } catch (_: Exception) {
            sendJson(
                socket,
                JSONObject().put("error", "请求体必须是 JSON"),
                statusCode = 400,
                reason = "Bad Request"
            )
            return
        }

        val command = payload.optString("command").trim()
        if (command.isBlank()) {
            sendJson(
                socket,
                JSONObject().put("error", "指令不能为空"),
                statusCode = 400,
                reason = "Bad Request"
            )
            return
        }

        if (!agentEngine.submitCommand(command, commandScope, inputTitle = "手动指令")) {
            sendJson(
                socket,
                JSONObject().apply {
                    put("error", "Agent 正在运行，请先停止当前任务")
                    put("agentState", ExecutionLogFormatter.buildAgentStateJson(agentEngine.agentState.value))
                },
                statusCode = 409,
                reason = "Conflict"
            )
            return
        }

        sendJson(
            socket,
            JSONObject().apply {
                put("ok", true)
                put("message", "指令已发送")
                put("command", command)
                put("agentState", ExecutionLogFormatter.buildAgentStateJson(agentEngine.agentState.value))
            },
            statusCode = 202,
            reason = "Accepted"
        )
    }

    private fun handleCancelRequest(socket: Socket) {
        agentEngine.cancel()
        sendJson(
            socket,
            JSONObject().apply {
                put("ok", true)
                put("message", "已请求停止当前任务")
                put("agentState", ExecutionLogFormatter.buildAgentStateJson(agentEngine.agentState.value))
            }
        )
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
        val payloadQuery = query.toMutableMap().apply {
            put("limit", limit.toString())
            put("includeImages", "1")
            if (sessionId == null) {
                remove("sessionId")
            }
        }
        val initialSnapshotJson = escapeForScript(buildLogsPayload(payloadQuery).toString())
        val selectedSessionIdJson = JSONObject.quote(sessionId ?: "")
        val selectedView = if (query["view"] == "dashboard" || sessionId == null) "dashboard" else "session"
        val selectedViewJson = JSONObject.quote(selectedView)

        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
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
                .page { max-width: 1280px; margin: 0 auto; padding: 20px 16px 40px; }
                .app-shell {
                  display: grid;
                  grid-template-columns: 280px minmax(0, 1fr);
                  gap: 16px;
                  align-items: start;
                }
                .sidebar {
                  position: sticky;
                  top: 16px;
                  padding: 16px;
                  background: #eee7df;
                  border: 1px solid var(--line);
                  border-radius: 18px;
                  max-height: calc(100vh - 32px);
                  overflow: auto;
                }
                .sidebar-title {
                  font-size: 18px;
                  font-weight: 700;
                  margin-bottom: 4px;
                }
                .sidebar-subtitle {
                  color: var(--muted);
                  font-size: 13px;
                  margin-bottom: 14px;
                }
                .session-list {
                  display: grid;
                  gap: 8px;
                }
                .nav-section {
                  margin-bottom: 18px;
                }
                .nav-label {
                  color: var(--muted);
                  font-size: 12px;
                  text-transform: uppercase;
                  letter-spacing: 0.08em;
                  margin-bottom: 8px;
                }
                .session-link {
                  display: block;
                  padding: 12px;
                  border-radius: 14px;
                  text-decoration: none;
                  color: inherit;
                  background: rgba(255,255,255,0.55);
                  border: 1px solid transparent;
                }
                .session-link:hover {
                  border-color: rgba(23, 98, 77, 0.2);
                  background: rgba(255,255,255,0.78);
                }
                .session-link.active {
                  background: #17624d;
                  color: #fff;
                }
                .session-link.active .meta,
                .session-link.active .session-link-time {
                  color: rgba(255,255,255,0.78);
                }
                .session-link-title {
                  font-size: 14px;
                  font-weight: 600;
                  margin-bottom: 4px;
                }
                .session-link-time {
                  color: var(--muted);
                  font-size: 12px;
                  margin-top: 6px;
                }
                .main-content {
                  min-width: 0;
                  padding-bottom: 132px;
                }
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
                .control-panel {
                  position: fixed;
                  left: max(312px, calc((100vw - 1280px) / 2 + 312px));
                  right: max(16px, calc((100vw - 1280px) / 2 + 16px));
                  bottom: 16px;
                  z-index: 20;
                  margin-bottom: 0;
                  padding: 14px;
                  background: rgba(255, 253, 250, 0.94);
                  border: 1px solid var(--line);
                  border-radius: 18px;
                  backdrop-filter: blur(14px);
                  box-shadow: 0 14px 40px rgba(64, 52, 43, 0.12);
                }
                .control-head {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px 12px;
                  align-items: baseline;
                  margin-bottom: 10px;
                }
                .control-head strong { font-size: 14px; }
                .control-row {
                  display: flex;
                  gap: 10px;
                  align-items: stretch;
                }
                .control-row textarea {
                  flex: 1;
                  min-height: 84px;
                  resize: vertical;
                  padding: 10px 12px;
                  border: 1px solid var(--line);
                  border-radius: 10px;
                  font: inherit;
                  color: var(--ink);
                  background: #fffdfa;
                }
                .control-row textarea:focus {
                  outline: 2px solid rgba(23, 98, 77, 0.18);
                  border-color: var(--accent);
                }
                .control-button {
                  min-width: 120px;
                  border: 0;
                  border-radius: 10px;
                  padding: 0 18px;
                  font: inherit;
                  font-weight: 600;
                  color: #fff;
                  background: var(--accent);
                  cursor: pointer;
                }
                .control-button.stop { background: var(--warn); }
                .control-button:disabled { opacity: 0.6; cursor: not-allowed; }
                .control-feedback {
                  min-height: 20px;
                  margin-top: 8px;
                  font-size: 13px;
                  color: var(--muted);
                }
                .control-feedback.success { color: var(--accent); }
                .control-feedback.error { color: #b3261e; }
                .session-tabs { display: none; }
                .section-head {
                  display: flex;
                  justify-content: space-between;
                  gap: 12px;
                  align-items: baseline;
                  margin: 18px 0 12px;
                }
                .section-head h2 {
                  margin: 0;
                  font-size: 18px;
                }
                .overview {
                  display: grid;
                  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                  gap: 12px;
                  margin-bottom: 16px;
                }
                .stat-card,
                .panel-card,
                .raw-panel,
                .session-shell,
                .session-event-card {
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 12px;
                }
                .stat-card {
                  padding: 14px;
                }
                .stat-label {
                  color: var(--muted);
                  font-size: 12px;
                  margin-bottom: 8px;
                }
                .stat-value {
                  font-size: 28px;
                  line-height: 1;
                  font-weight: 700;
                  margin-bottom: 6px;
                }
                .stat-note {
                  color: var(--muted);
                  font-size: 12px;
                }
                .insight-grid {
                  display: grid;
                  grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
                  gap: 16px;
                  margin-bottom: 16px;
                }
                .panel-card {
                  padding: 16px;
                }
                .panel-card h2 {
                  margin: 0 0 6px;
                  font-size: 16px;
                }
                .panel-card .meta {
                  color: var(--muted);
                  font-size: 13px;
                }
                .session-shell {
                  padding: 0;
                  margin-bottom: 16px;
                  background:
                    radial-gradient(circle at top right, rgba(23, 98, 77, 0.08), transparent 34%),
                    var(--panel);
                  overflow: hidden;
                }
                .session-layout {
                  display: grid;
                  grid-template-columns: minmax(0, 1.4fr) minmax(280px, 0.8fr);
                  gap: 0;
                }
                .session-main {
                  min-width: 0;
                }
                .session-sidepanel {
                  min-width: 0;
                  border-left: 1px solid var(--line);
                  background: rgba(250, 247, 243, 0.78);
                }
                .session-topline {
                  display: flex;
                  justify-content: space-between;
                  gap: 12px;
                  align-items: center;
                  padding: 18px 18px 12px;
                  border-bottom: 1px solid var(--line);
                }
                .session-title {
                  font-size: 20px;
                  font-weight: 700;
                }
                .session-status {
                  padding: 6px 10px;
                  border-radius: 999px;
                  font-size: 12px;
                  background: #edf5f1;
                  color: var(--accent);
                }
                .session-status.running { animation: pulseGlow 1.8s ease-in-out infinite; }
                .session-meta-row {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                  padding: 0 18px 16px;
                }
                .session-chip {
                  padding: 8px 10px;
                  border-radius: 999px;
                  background: #f3eee8;
                  color: #5d5751;
                  font-size: 12px;
                }
                .message-stream {
                  display: grid;
                  gap: 14px;
                  padding: 8px 18px 18px;
                }
                .session-sidecard {
                  padding: 18px;
                  display: grid;
                  gap: 14px;
                }
                .session-sidecard h3 {
                  margin: 0;
                  font-size: 16px;
                }
                .screenshot-stack {
                  display: grid;
                  gap: 12px;
                }
                .screenshot-card {
                  display: grid;
                  gap: 8px;
                  padding: 10px;
                  border-radius: 12px;
                  background: rgba(255, 253, 250, 0.88);
                  border: 1px solid #ebe3dc;
                }
                .screenshot-card img {
                  display: block;
                  width: 100%;
                  border-radius: 10px;
                  border: 1px solid var(--line);
                  aspect-ratio: 9 / 18;
                  object-fit: cover;
                  background: #efe7de;
                }
                .screenshot-card-title {
                  font-size: 13px;
                  font-weight: 600;
                }
                .screenshot-card-meta {
                  color: var(--muted);
                  font-size: 12px;
                  line-height: 1.4;
                }
                .message-row {
                  display: flex;
                  gap: 12px;
                  align-items: flex-start;
                }
                .message-avatar {
                  flex: 0 0 34px;
                  width: 34px;
                  height: 34px;
                  border-radius: 12px;
                  display: inline-flex;
                  align-items: center;
                  justify-content: center;
                  font-size: 14px;
                  font-weight: 700;
                  color: #fff;
                  background: #17624d;
                }
                .message-avatar.system {
                  background: #6d7f76;
                }
                .message-avatar.event {
                  background: #b68d57;
                }
                .message-bubble {
                  min-width: 0;
                  max-width: min(820px, 100%);
                  padding: 14px 16px;
                  border-radius: 18px;
                  background: #fffdfa;
                  border: 1px solid var(--line);
                  box-shadow: 0 4px 16px rgba(41, 31, 22, 0.04);
                }
                .message-bubble.stage {
                  position: relative;
                  overflow: hidden;
                }
                .message-bubble.stage::before {
                  content: "";
                  position: absolute;
                  inset: 0;
                  opacity: 0;
                  background: linear-gradient(120deg, transparent 0%, rgba(47, 143, 112, 0.18) 48%, transparent 100%);
                  transform: translateX(-120%);
                }
                .message-bubble.stage.running::before {
                  opacity: 1;
                  animation: stageSweep 2.4s linear infinite;
                }
                .message-bubble.stage.done {
                  border-color: rgba(23, 98, 77, 0.35);
                }
                .message-bubble.stage.pending {
                  opacity: 0.7;
                }
                .message-head {
                  display: flex;
                  justify-content: space-between;
                  gap: 12px;
                  align-items: baseline;
                  margin-bottom: 8px;
                }
                .message-role {
                  font-size: 12px;
                  color: var(--muted);
                  text-transform: uppercase;
                  letter-spacing: 0.06em;
                }
                .message-title {
                  font-size: 16px;
                  font-weight: 700;
                  margin-bottom: 8px;
                }
                .flow-caption {
                  color: var(--muted);
                  font-size: 13px;
                  line-height: 1.5;
                }
                .flow-tag {
                  display: inline-block;
                  margin-top: 12px;
                  padding: 4px 8px;
                  border-radius: 999px;
                  font-size: 12px;
                  background: #efe9e2;
                  color: #5f5a56;
                }
                .message-list {
                  display: grid;
                  gap: 8px;
                  margin-top: 10px;
                }
                .message-item {
                  padding-left: 16px;
                  position: relative;
                  color: var(--muted);
                  font-size: 13px;
                  line-height: 1.5;
                }
                .message-item::before {
                  content: "";
                  position: absolute;
                  left: 0;
                  top: 8px;
                  width: 6px;
                  height: 6px;
                  border-radius: 999px;
                  background: #b39b82;
                }
                .chart {
                  display: grid;
                  gap: 10px;
                  margin-top: 14px;
                }
                .chart-row {
                  display: grid;
                  grid-template-columns: minmax(92px, 120px) minmax(0, 1fr) auto;
                  gap: 10px;
                  align-items: center;
                  font-size: 13px;
                }
                .chart-label {
                  color: var(--ink);
                  font-weight: 500;
                }
                .chart-track {
                  position: relative;
                  height: 10px;
                  border-radius: 999px;
                  background: #eee7e0;
                  overflow: hidden;
                }
                .chart-fill {
                  height: 100%;
                  border-radius: 999px;
                  background: linear-gradient(90deg, #17624d 0%, #2f8f70 100%);
                }
                .timeline {
                  display: grid;
                  gap: 10px;
                  margin-top: 14px;
                }
                .timeline-item {
                  padding: 12px;
                  border-radius: 10px;
                  background: #faf7f3;
                  border: 1px solid #ebe3dc;
                }
                .timeline-top {
                  display: flex;
                  justify-content: space-between;
                  gap: 12px;
                  align-items: baseline;
                  margin-bottom: 6px;
                }
                .timeline-title {
                  font-size: 14px;
                  font-weight: 600;
                }
                .timeline-snippet {
                  font-size: 13px;
                  color: var(--muted);
                }
                .timeline-empty {
                  margin-top: 12px;
                  color: var(--muted);
                  font-size: 13px;
                }
                .raw-panel {
                  padding: 0;
                  overflow: hidden;
                }
                .raw-panel summary {
                  cursor: pointer;
                  list-style: none;
                  padding: 16px;
                  font-weight: 600;
                }
                .raw-panel summary::-webkit-details-marker {
                  display: none;
                }
                .raw-content {
                  padding: 0 16px 16px;
                  border-top: 1px solid var(--line);
                }
                .timing { font-size: 13px; color: var(--muted); margin-top: 8px; }
                .timing ul { margin: 4px 0 0; padding-left: 18px; }
                .timing .meta { color: var(--muted); font-size: 12px; margin-top: 2px; }
                .log-list { display: flex; flex-direction: column; gap: 10px; }
                .entry {
                  border-radius: 10px;
                  border: 1px solid var(--line);
                  background: #fffdfa;
                  overflow: hidden;
                }
                .entry summary {
                  list-style: none;
                  cursor: pointer;
                  padding: 12px 14px;
                }
                .entry summary::-webkit-details-marker {
                  display: none;
                }
                .entry-body {
                  padding: 0 14px 14px;
                  border-top: 1px solid var(--line);
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
                @keyframes stageSweep {
                  from { transform: translateX(-120%); }
                  to { transform: translateX(120%); }
                }
                @keyframes pulseGlow {
                  0%, 100% { box-shadow: 0 0 0 0 rgba(23, 98, 77, 0.16); }
                  50% { box-shadow: 0 0 0 8px rgba(23, 98, 77, 0); }
                }
                @media (max-width: 720px) {
                  .app-shell {
                    grid-template-columns: 1fr;
                  }
                  .sidebar {
                    position: static;
                    max-height: none;
                  }
                  .main-content {
                    padding-bottom: 156px;
                  }
                  .control-panel {
                    position: fixed;
                    left: 12px;
                    right: 12px;
                    bottom: 12px;
                  }
                  .insight-grid {
                    grid-template-columns: 1fr;
                  }
                  .session-layout {
                    grid-template-columns: 1fr;
                  }
                  .session-sidepanel {
                    border-left: 0;
                    border-top: 1px solid var(--line);
                  }
                  .control-row {
                    flex-direction: column;
                  }
                  .control-button {
                    min-height: 48px;
                    width: 100%;
                  }
                }
              </style>
            </head>
            <body>
              <main class="page">
                <div class="app-shell">
                  <aside class="sidebar">
                    <div class="sidebar-title">会话</div>
                    <div id="sidebarSummary" class="sidebar-subtitle">正在加载...</div>
                    <div class="nav-section">
                      <div class="nav-label">总览</div>
                      <nav id="overviewNav" class="session-list"></nav>
                    </div>
                    <div class="nav-section">
                      <div class="nav-label">会话</div>
                      <nav id="sessionList" class="session-list"></nav>
                    </div>
                  </aside>
                  <section class="main-content">
                    <div class="head">
                      <h1>执行日志</h1>
                    </div>
                    <section class="control-panel">
                  <div class="control-row">
                    <textarea id="commandInput" placeholder="例如：打开微信，给张三发消息说我十分钟后到"></textarea>
                    <button id="commandButton" class="control-button" type="button">发送指令</button>
                  </div>
                  <div id="commandFeedback" class="control-feedback"></div>
                    </section>
                    <section id="dashboardView">
                    <div class="section-head">
                  <h2>数据大盘</h2>
                  <div class="meta">聚合统计，快速看整体运行健康度。</div>
                    </div>
                    <section id="overview" class="overview"></section>
                    <div class="insight-grid">
                  <section class="panel-card">
                    <h2>日志分布</h2>
                    <div class="meta">默认先看结构化摘要，原始日志折叠在下方。</div>
                    <div id="distribution" class="chart"></div>
                  </section>
                  <section class="panel-card">
                    <h2>最近动态</h2>
                    <div class="meta">挑选近几条关键动作，便于快速浏览执行过程。</div>
                    <div id="timeline" class="timeline"></div>
                  </section>
                </div>
                    </section>
                    <section id="sessionView">
                    <div class="section-head">
                  <h2>当前会话详情</h2>
                  <div class="meta">语义化展示当前执行链路，而不是直接阅读原始日志。</div>
                    </div>
                    <section id="sessionBoard" class="session-shell"></section>
                    <details class="raw-panel">
                  <summary>查看原始日志与会话明细</summary>
                  <div class="raw-content">
                    <div id="timingSection" class="timing" hidden></div>
                    <div id="sessionTabs" class="session-tabs"></div>
                    <section id="logList" class="log-list"></section>
                  </div>
                    </details>
                    </section>
                  </section>
                </div>
              </main>
              <script>
                const DEFAULT_REFRESH_INTERVAL_MS = 5000;
                const REFRESH_INTERVAL_STORAGE_KEY = 'control-log-refresh-interval-ms';
                const TYPE_LABELS = {
                  ACTION_EXECUTED: '执行动作',
                  API_REQUEST: '请求模型',
                  API_RESPONSE: '模型响应',
                  SCREENSHOT: '截图',
                  ERROR: '异常',
                  INFO: '信息',
                  VOICE_INPUT: '语音输入'
                };
                const state = {
                  limit: $limit,
                  selectedSessionId: $selectedSessionIdJson,
                  selectedView: $selectedViewJson,
                  refreshTimer: null,
                  refreshIntervalMs: DEFAULT_REFRESH_INTERVAL_MS,
                  requestInFlight: false,
                  commandInFlight: false,
                  snapshot: $initialSnapshotJson
                };

                function escapeHtml(value) {
                  return String(value || '')
                    .replaceAll('&', '&amp;')
                    .replaceAll('<', '&lt;')
                    .replaceAll('>', '&gt;')
                    .replaceAll('"', '&quot;')
                    .replaceAll("'", '&#39;');
                }

                function getStoredRefreshIntervalMs() {
                  try {
                    const raw = window.localStorage.getItem(REFRESH_INTERVAL_STORAGE_KEY);
                    const parsed = Number(raw);
                    if (!Number.isFinite(parsed)) return DEFAULT_REFRESH_INTERVAL_MS;
                    return Math.min(Math.max(parsed, 1000), 60000);
                  } catch (_) {
                    return DEFAULT_REFRESH_INTERVAL_MS;
                  }
                }

                function setStoredRefreshIntervalMs(value) {
                  try {
                    window.localStorage.setItem(REFRESH_INTERVAL_STORAGE_KEY, String(value));
                  } catch (_) {}
                }

                function formatDurationMs(durationMs) {
                  const safe = Math.max(Number(durationMs) || 0, 0);
                  if (safe < 1000) return safe + 'ms';
                  const totalSeconds = Math.floor(safe / 1000);
                  const minutes = Math.floor(totalSeconds / 60);
                  const seconds = totalSeconds % 60;
                  return minutes > 0 ? minutes + 'm' + seconds + 's' : seconds + 's';
                }

                function formatType(type) {
                  return TYPE_LABELS[type] || type || '未知';
                }

                function summarizeText(value, maxLength) {
                  const compact = String(value || '').replace(/\s+/g, ' ').trim();
                  if (!compact) return '无附加内容';
                  return compact.length > maxLength ? compact.slice(0, maxLength - 1) + '…' : compact;
                }

                function buildTypeCounts(entries) {
                  const counts = {};
                  (entries || []).forEach(entry => {
                    const key = String(entry.type || 'UNKNOWN');
                    counts[key] = (counts[key] || 0) + 1;
                  });
                  return counts;
                }

                function findActiveSession(snapshot) {
                  const sessions = snapshot.sessions || [];
                  return snapshot.selectedSession || sessions.find(session => session.isActive) || sessions[0] || null;
                }

                function findLatestEntry(entries, matcher) {
                  const reversed = (entries || []).slice().reverse();
                  return reversed.find(matcher) || null;
                }

                function renderOverview(snapshot) {
                  const entries = snapshot.entries || [];
                  const agentState = snapshot.agentState || {};
                  const counts = buildTypeCounts(entries);
                  const errorCount = counts.ERROR || 0;
                  const actionCount = counts.ACTION_EXECUTED || 0;
                  const screenshotCount = counts.SCREENSHOT || 0;
                  const stepCount = (agentState.stepTimings || []).length;
                  const cards = [
                    {
                      label: '当前状态',
                      value: agentState.isRunning ? '执行中' : '空闲',
                      note: agentState.statusMessage || '暂无状态信息'
                    },
                    {
                      label: '本页日志',
                      value: String(entries.length),
                      note: '按当前筛选展示'
                    },
                    {
                      label: '执行动作',
                      value: String(actionCount),
                      note: '点击、输入、滚动等'
                    },
                    {
                      label: '异常数量',
                      value: String(errorCount),
                      note: errorCount > 0 ? '建议优先检查' : '当前未发现异常'
                    },
                    {
                      label: '截图次数',
                      value: String(screenshotCount),
                      note: '用于界面分析'
                    },
                    {
                      label: '步骤耗时',
                      value: String(stepCount),
                      note: stepCount > 0 ? '可下钻查看分解' : '当前没有步骤记录'
                    }
                  ];
                  document.getElementById('overview').innerHTML = cards.map(card =>
                    '<article class="stat-card">' +
                      '<div class="stat-label">' + escapeHtml(card.label) + '</div>' +
                      '<div class="stat-value">' + escapeHtml(card.value) + '</div>' +
                      '<div class="stat-note">' + escapeHtml(card.note) + '</div>' +
                    '</article>'
                  ).join('');
                }

                function renderDistribution(entries) {
                  const counts = Object.entries(buildTypeCounts(entries))
                    .sort((a, b) => b[1] - a[1]);
                  const container = document.getElementById('distribution');
                  if (!counts.length) {
                    container.innerHTML = '<div class="timeline-empty">暂无可统计的日志类型</div>';
                    return;
                  }
                  const max = counts[0][1] || 1;
                  container.innerHTML = counts.map(([type, count]) => {
                    const width = Math.max((count / max) * 100, count > 0 ? 8 : 0);
                    return '<div class="chart-row">' +
                      '<div class="chart-label">' + escapeHtml(formatType(type)) + '</div>' +
                      '<div class="chart-track"><div class="chart-fill" style="width:' + width.toFixed(1) + '%"></div></div>' +
                      '<div>' + count + '</div>' +
                    '</div>';
                  }).join('');
                }

                function renderTimeline(entries) {
                  const container = document.getElementById('timeline');
                  const items = (entries || []).slice().reverse().slice(0, 6);
                  if (!items.length) {
                    container.innerHTML = '<div class="timeline-empty">暂无最近动态</div>';
                    return;
                  }
                  container.innerHTML = items.map(entry =>
                    '<article class="timeline-item">' +
                      '<div class="timeline-top">' +
                        '<div class="timeline-title">' + escapeHtml(entry.title || formatType(entry.type)) + '</div>' +
                        '<div class="meta">' + escapeHtml(entry.timestampFormatted || '') + '</div>' +
                      '</div>' +
                      '<div class="timeline-snippet">' + escapeHtml(summarizeText(entry.content, 110)) + '</div>' +
                    '</article>'
                  ).join('');
                }

                function buildSessionFlow(snapshot) {
                  const entries = snapshot.entries || [];
                  const agentState = snapshot.agentState || {};
                  const stepTimings = agentState.stepTimings || [];
                  const voiceEntry = findLatestEntry(entries, entry => entry.type === 'VOICE_INPUT' || /手动指令|语音/i.test(String(entry.title || '')));
                  const screenEntry = findLatestEntry(entries, entry => entry.type === 'SCREENSHOT');
                  const aiEntry = findLatestEntry(entries, entry => entry.type === 'API_RESPONSE' || entry.type === 'API_REQUEST');
                  const actionEntry = findLatestEntry(entries, entry => entry.type === 'ACTION_EXECUTED');
                  const errorEntry = findLatestEntry(entries, entry => entry.type === 'ERROR');
                  const stages = [
                    {
                      title: '接收指令',
                      detail: summarizeText((voiceEntry && (voiceEntry.content || voiceEntry.title)) || agentState.lastAction || '等待新的输入', 72),
                      completed: !!voiceEntry || !!agentState.taskStartedAtMs
                    },
                    {
                      title: '理解界面',
                      detail: summarizeText((screenEntry && (screenEntry.title || screenEntry.content)) || '尚未进入截图分析', 72),
                      completed: !!screenEntry || stepTimings.some(step => /截图/.test(String(step.label || '')))
                    },
                    {
                      title: '规划动作',
                      detail: summarizeText((aiEntry && (aiEntry.title || aiEntry.content)) || agentState.lastThinking || '等待模型决策', 72),
                      completed: !!aiEntry || stepTimings.some(step => /ai|模型/i.test(String(step.label || '') + ' ' + String(step.tool || '')))
                    },
                    {
                      title: '执行操作',
                      detail: summarizeText((actionEntry && (actionEntry.title || actionEntry.content)) || agentState.activeTool || '尚未执行具体动作', 72),
                      completed: !!actionEntry || stepTimings.some(step => !!step.tool)
                    },
                    {
                      title: errorEntry ? '异常处理' : '完成收尾',
                      detail: summarizeText((errorEntry && errorEntry.content) || agentState.statusMessage || '等待最终状态', 72),
                      completed: !agentState.isRunning && (!!agentState.lastProgressAtMs || !!errorEntry),
                      forceRunning: !!errorEntry && agentState.isRunning
                    }
                  ];
                  const activeIndex = stages.findIndex(stage => !stage.completed);
                  return stages.map((stage, index) => Object.assign({}, stage, {
                    running: stage.forceRunning || (agentState.isRunning && activeIndex === index),
                    stateLabel: stage.completed ? '已完成' : ((stage.forceRunning || (agentState.isRunning && activeIndex === index)) ? '进行中' : '待开始')
                  }));
                }

                function buildRecentScreenshots(entries) {
                  return (entries || [])
                    .slice()
                    .reverse()
                    .filter(entry =>
                      entry.type === 'SCREENSHOT' &&
                      Array.isArray(entry.images) &&
                      entry.images.some(image => image && image.imageBase64)
                    )
                    .slice(0, 4);
                }

                function renderSessionBoard(snapshot) {
                  const container = document.getElementById('sessionBoard');
                  const session = findActiveSession(snapshot);
                  const agentState = snapshot.agentState || {};
                  const entries = snapshot.entries || [];
                  const stages = buildSessionFlow(snapshot);
                  const recentScreenshots = buildRecentScreenshots(entries);
                  const stepTimings = agentState.stepTimings || [];
                  const actionCount = buildTypeCounts(entries).ACTION_EXECUTED || 0;
                  const durationMs = agentState.taskStartedAtMs && agentState.lastProgressAtMs
                    ? Math.max(0, agentState.lastProgressAtMs - agentState.taskStartedAtMs)
                    : 0;
                  const metaCards = [
                    '轮次 ' + (agentState.currentRound || 0) + '/' + (agentState.maxRounds || 0),
                    '累计步骤 ' + stepTimings.length,
                    '执行时长 ' + (durationMs > 0 ? formatDurationMs(durationMs) : '刚开始'),
                    '活跃工具 ' + (agentState.activeTool || '暂无'),
                    '已执行动作 ' + actionCount
                  ];
                  const recentEvents = (entries || []).slice().reverse().slice(0, 3);
                  const stageMessages = stages.map((stage, index) =>
                    '<article class="message-row">' +
                      '<div class="message-avatar">' + (index + 1) + '</div>' +
                      '<div class="message-bubble stage ' + (stage.completed ? 'done' : (stage.running ? 'running' : 'pending')) + '">' +
                        '<div class="message-head">' +
                          '<div class="message-role">assistant</div>' +
                          '<div class="meta">' + escapeHtml(stage.stateLabel) + '</div>' +
                        '</div>' +
                        '<div class="message-title">' + escapeHtml(stage.title) + '</div>' +
                        '<div class="flow-caption">' + escapeHtml(stage.detail) + '</div>' +
                        '<div class="flow-tag">' + escapeHtml(stage.stateLabel) + '</div>' +
                      '</div>' +
                    '</article>'
                  ).join('');
                  const stepMessage = stepTimings.length ? (
                    '<article class="message-row">' +
                      '<div class="message-avatar system">S</div>' +
                      '<div class="message-bubble">' +
                        '<div class="message-head">' +
                          '<div class="message-role">system</div>' +
                          '<div class="meta">步骤分解</div>' +
                        '</div>' +
                        '<div class="message-title">本轮执行轨迹</div>' +
                        '<div class="message-list">' +
                          stepTimings.slice(-5).map((step, index) =>
                            '<div class="message-item">' +
                              escapeHtml((index + 1) + '. ' + (step.label || '未命名步骤') + ' · ' + formatDurationMs(step.durationMs)) +
                            '</div>'
                          ).join('') +
                        '</div>' +
                      '</div>' +
                    '</article>'
                  ) : '';
                  const eventMessages = recentEvents.map(entry =>
                    '<article class="message-row">' +
                      '<div class="message-avatar event">E</div>' +
                      '<div class="message-bubble">' +
                        '<div class="message-head">' +
                          '<div class="message-role">event</div>' +
                          '<div class="meta">' + escapeHtml(entry.timestampFormatted || '') + '</div>' +
                        '</div>' +
                        '<div class="message-title">' + escapeHtml(entry.title || formatType(entry.type)) + '</div>' +
                        '<div class="flow-caption">' + escapeHtml(summarizeText(entry.content, 180)) + '</div>' +
                      '</div>' +
                    '</article>'
                  ).join('');
                  const screenshotHtml = recentScreenshots.length ? (
                    '<div class="screenshot-stack">' +
                      recentScreenshots.map((entry, index) => {
                        const image = (entry.images || []).find(item => item && item.imageBase64);
                        if (!image) return '';
                        const mimeType = image.mimeType || 'image/png';
                        return '<article class="screenshot-card">' +
                          '<img src="data:' + escapeHtml(mimeType) + ';base64,' + escapeHtml(image.imageBase64) + '" alt="最近截屏 ' + (index + 1) + '" loading="lazy">' +
                          '<div class="screenshot-card-title">' + escapeHtml(entry.title || '截图') + '</div>' +
                          '<div class="screenshot-card-meta">' + escapeHtml(entry.timestampFormatted || '') + '</div>' +
                          '<div class="screenshot-card-meta">' + escapeHtml(summarizeText(entry.content, 56)) + '</div>' +
                        '</article>';
                      }).join('') +
                    '</div>'
                  ) : '<div class="timeline-empty">当前会话还没有可展示的截屏。</div>';
                  container.innerHTML =
                    '<div class="session-layout">' +
                      '<div class="session-main">' +
                        '<div class="session-topline">' +
                          '<div>' +
                            '<div class="session-title">' + escapeHtml((session && session.title) || '当前执行会话') + '</div>' +
                            '<div class="meta">' + escapeHtml(agentState.statusMessage || '等待新的任务') + '</div>' +
                          '</div>' +
                          '<div class="session-status' + (agentState.isRunning ? ' running' : '') + '">' + escapeHtml(agentState.isRunning ? '运行中' : '空闲') + '</div>' +
                        '</div>' +
                        '<div class="session-meta-row">' +
                          metaCards.map(text => '<div class="session-chip">' + escapeHtml(text) + '</div>').join('') +
                        '</div>' +
                        '<div class="message-stream">' +
                          stageMessages +
                          stepMessage +
                          (eventMessages || '<div class="timeline-empty">当前会话还没有更多事件。</div>') +
                        '</div>' +
                      '</div>' +
                      '<aside class="session-sidepanel">' +
                        '<section class="session-sidecard">' +
                          '<div>' +
                            '<h3>最近截屏</h3>' +
                            '<div class="meta">按时间倒序展示最近 4 张截图，方便在右侧快速回看界面变化。</div>' +
                          '</div>' +
                          screenshotHtml +
                        '</section>' +
                      '</aside>' +
                    '</div>';
                }

                function buildPageUrl(sessionId, view) {
                  const params = new URLSearchParams();
                  params.set('limit', String(state.limit));
                  const resolvedView = view || (sessionId ? 'session' : 'dashboard');
                  if (resolvedView === 'dashboard') {
                    params.set('view', 'dashboard');
                  }
                  if (sessionId) {
                    params.set('sessionId', sessionId);
                  }
                  return '/?' + params.toString();
                }

                function buildApiUrl(includeImages) {
                  const params = new URLSearchParams();
                  params.set('limit', String(state.limit));
                  if (includeImages) {
                    params.set('includeImages', '1');
                  }
                  if (state.selectedSessionId) {
                    params.set('sessionId', state.selectedSessionId);
                  }
                  return '/api/logs?' + params.toString();
                }

                function renderSidebarNav(sessions) {
                  document.getElementById('overviewNav').innerHTML =
                    '<a class="session-link' + (state.selectedView === 'dashboard' ? ' active' : '') + '" href="' + buildPageUrl('', 'dashboard') + '">' +
                      '<div class="session-link-title">数据大盘</div>' +
                      '<div class="meta">查看全部会话的聚合统计、分布和时间线</div>' +
                    '</a>';

                  const list = (sessions || []).map(session => ({
                    id: session.id || '',
                    title: session.title || session.id || '未命名会话',
                    subtitle: (session.entryCount || 0) + ' 条日志 · ' + (session.messageCount || 0) + ' 条消息',
                    time: session.lastActiveAtFormatted || session.createdAtFormatted || ''
                  }));
                  document.getElementById('sidebarSummary').textContent = '共 ' + list.length + ' 个会话';
                  document.getElementById('sessionList').innerHTML = list.length ? list.map(item => {
                    const active = state.selectedView === 'session' && item.id === state.selectedSessionId;
                    return '<a class="session-link' + (active ? ' active' : '') + '" href="' + buildPageUrl(item.id, 'session') + '">' +
                      '<div class="session-link-title">' + escapeHtml(item.title) + '</div>' +
                      '<div class="meta">' + escapeHtml(item.subtitle) + '</div>' +
                      '<div class="session-link-time">' + escapeHtml(item.time) + '</div>' +
                    '</a>';
                  }).join('') : '<div class="meta">暂无会话</div>';
                }

                function updateMainPanels() {
                  const dashboard = document.getElementById('dashboardView');
                  const sessionView = document.getElementById('sessionView');
                  const showDashboard = state.selectedView === 'dashboard';
                  dashboard.hidden = !showDashboard;
                  sessionView.hidden = showDashboard;
                }

                function applyRefreshInterval(intervalMs) {
                  state.refreshIntervalMs = intervalMs;
                  setStoredRefreshIntervalMs(intervalMs);
                  const select = document.getElementById('refreshIntervalSelect');
                  if (select) {
                    select.value = String(Math.round(intervalMs / 1000));
                  }
                  if (state.refreshTimer) {
                    window.clearInterval(state.refreshTimer);
                  }
                  state.refreshTimer = window.setInterval(loadSnapshot, state.refreshIntervalMs);
                }

                function handleRefreshIntervalChange() {
                  const select = document.getElementById('refreshIntervalSelect');
                  const seconds = Math.max(1, Number(select.value) || 5);
                  applyRefreshInterval(seconds * 1000);
                }

                function setFeedback(message, tone) {
                  const el = document.getElementById('commandFeedback');
                  el.textContent = message || '';
                  el.className = 'control-feedback' + (tone ? ' ' + tone : '');
                }

                function renderStepTimings(stepTimings) {
                  const container = document.getElementById('timingSection');
                  if (!stepTimings || !stepTimings.length) {
                    container.hidden = true;
                    container.innerHTML = '';
                    return;
                  }
                  container.hidden = false;
                  container.innerHTML =
                    '<strong>步骤耗时</strong>' +
                    '<ul class="meta">' +
                    stepTimings.map((step, index) => {
                      const lines = [
                        '<strong>' + (index + 1) + '. ' + escapeHtml(step.label || '未命名步骤') + '</strong>',
                        '<div class="meta">耗时: ' + escapeHtml(formatDurationMs(step.durationMs)) + '</div>'
                      ];
                      if (step.tool) {
                        lines.push('<div class="meta">工具: ' + escapeHtml(step.tool) + '</div>');
                      }
                      if (step.toolArguments) {
                        lines.push('<div class="meta">参数: ' + escapeHtml(step.toolArguments) + '</div>');
                      }
                      if (step.intent) {
                        lines.push('<div class="meta">意图: ' + escapeHtml(step.intent) + '</div>');
                      }
                      return '<li>' + lines.join('') + '</li>';
                    }).join('') +
                    '</ul>';
                }

                function renderSessionTabs(sessions) {
                  renderSidebarNav(sessions);
                  document.getElementById('sessionTabs').innerHTML = '';
                }

                function renderEntries(entries) {
                  const list = document.getElementById('logList');
                  if (!entries || !entries.length) {
                    list.innerHTML = '<div class="empty">暂无执行日志</div>';
                    return;
                  }

                  const rendered = entries.slice().reverse().map(entry => {
                    const images = Array.isArray(entry.images) ? entry.images : [];
                    const imageHtml = images.map((image, index) => {
                      if (!image.imageBase64) return '';
                      const mimeType = image.mimeType || 'image/png';
                      return '<div class="entry-image">' +
                        '<div class="meta">图片 ' + (index + 1) + '/' + images.length + '</div>' +
                        '<img src="data:' + escapeHtml(mimeType) + ';base64,' + escapeHtml(image.imageBase64) + '" alt="日志图片预览 ' + (index + 1) + '" loading="lazy">' +
                      '</div>';
                    }).join('');
                    return '<details class="entry ' + escapeHtml(String(entry.type || '').toLowerCase()) + '">' +
                      '<summary>' +
                        '<div class="entry-header">' +
                          '<span class="entry-type">' + escapeHtml(formatType(entry.type || '')) + '</span>' +
                          '<span class="entry-time">' + escapeHtml(entry.timestampFormatted || '') + '</span>' +
                        '</div>' +
                        '<h3>' + escapeHtml(entry.title || '') + '</h3>' +
                        '<div class="meta">' + escapeHtml(summarizeText(entry.content, 120)) + '</div>' +
                      '</summary>' +
                      '<div class="entry-body">' +
                        '<pre>' + escapeHtml(entry.content || '') + '</pre>' +
                        imageHtml +
                      '</div>' +
                    '</details>';
                  }).join('');

                  list.innerHTML = rendered;
                }

                function updateCommandButton(isRunning) {
                  const button = document.getElementById('commandButton');
                  button.textContent = isRunning ? '停止运行' : '发送指令';
                  button.classList.toggle('stop', !!isRunning);
                  button.disabled = state.commandInFlight;
                }

                function renderSnapshot(snapshot) {
                  state.snapshot = snapshot || {};
                  const agentState = state.snapshot.agentState || {};
                  const isRunning = !!agentState.isRunning;

                  renderOverview(state.snapshot);
                  renderDistribution(state.snapshot.entries || []);
                  renderTimeline(state.snapshot.entries || []);
                  renderSessionBoard(state.snapshot);
                  updateMainPanels();
                  renderStepTimings(agentState.stepTimings || []);
                  renderSessionTabs(state.snapshot.sessions || []);
                  renderEntries(state.snapshot.entries || []);
                  updateCommandButton(isRunning);
                }

                async function loadSnapshot() {
                  if (state.requestInFlight) return;
                  state.requestInFlight = true;
                  try {
                    const response = await fetch(buildApiUrl(true), { cache: 'no-store' });
                    if (!response.ok) {
                      throw new Error('HTTP ' + response.status);
                    }
                    const snapshot = await response.json();
                    renderSnapshot(snapshot);
                  } catch (error) {
                    setFeedback('刷新日志失败: ' + error.message, 'error');
                  } finally {
                    state.requestInFlight = false;
                  }
                }

                async function handleCommandButton() {
                  const agentState = (state.snapshot && state.snapshot.agentState) || {};
                  const isRunning = !!agentState.isRunning;
                  const input = document.getElementById('commandInput');
                  const command = input.value.trim();

                  if (state.commandInFlight) return;

                  if (!isRunning && !command) {
                    setFeedback('请输入要执行的指令。', 'error');
                    return;
                  }

                  state.commandInFlight = true;
                  updateCommandButton(isRunning);

                  try {
                    if (isRunning) {
                      const response = await fetch('/api/agent/cancel', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' }
                      });
                      const data = await response.json();
                      if (!response.ok) {
                        throw new Error(data.error || ('HTTP ' + response.status));
                      }
                      setFeedback(data.message || '已请求停止当前任务。', 'success');
                    } else {
                      const response = await fetch('/api/agent/command', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ command: command })
                      });
                      const data = await response.json();
                      if (!response.ok) {
                        throw new Error(data.error || ('HTTP ' + response.status));
                      }
                      input.value = '';
                      if (state.selectedSessionId) {
                        state.selectedSessionId = '';
                        state.selectedView = 'dashboard';
                        history.replaceState(null, '', buildPageUrl('', 'dashboard'));
                      }
                      setFeedback(data.message || '指令已发送。', 'success');
                    }
                    await loadSnapshot();
                    window.setTimeout(loadSnapshot, 1000);
                  } catch (error) {
                    setFeedback(error.message || '操作失败', 'error');
                    await loadSnapshot();
                  } finally {
                    state.commandInFlight = false;
                    updateCommandButton(((state.snapshot || {}).agentState || {}).isRunning);
                  }
                }

                document.getElementById('commandButton').addEventListener('click', handleCommandButton);
                document.getElementById('commandInput').addEventListener('keydown', function(event) {
                  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
                    event.preventDefault();
                    handleCommandButton();
                  }
                });

                state.refreshIntervalMs = getStoredRefreshIntervalMs();
                renderSnapshot(state.snapshot);
                applyRefreshInterval(state.refreshIntervalMs);
              </script>
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

    private fun sendJson(
        socket: Socket,
        payload: JSONObject,
        statusCode: Int = 200,
        reason: String = "OK"
    ) {
        sendResponse(
            socket = socket,
            statusCode = statusCode,
            reason = reason,
            contentType = "application/json; charset=utf-8",
            body = payload.toString(2).toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sendEmpty(socket: Socket, statusCode: Int, reason: String) {
        sendResponse(
            socket = socket,
            statusCode = statusCode,
            reason = reason,
            contentType = "text/plain; charset=utf-8",
            body = ByteArray(0)
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
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
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

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun escapeHtml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun escapeForScript(value: String): String =
        value.replace("</", "<\\/")

    private fun String?.toBooleanFlag(): Boolean =
        this.equals("1", ignoreCase = true) ||
            this.equals("true", ignoreCase = true) ||
            this.equals("yes", ignoreCase = true)
}
