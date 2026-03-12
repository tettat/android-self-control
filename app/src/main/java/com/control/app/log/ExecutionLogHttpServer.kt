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
                .status { font-size: 13px; padding: 4px 10px; border-radius: 6px; }
                .status.running { background: #e8f4f0; color: var(--accent); }
                .status.idle { background: #fce8e4; color: var(--warn); }
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
                .control-panel {
                  margin-bottom: 16px;
                  padding: 14px;
                  background: var(--panel);
                  border: 1px solid var(--line);
                  border-radius: 12px;
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
                @media (max-width: 720px) {
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
                <div class="head">
                  <h1>执行日志</h1>
                  <span id="selectedTitle" class="meta">正在加载...</span>
                  <span id="agentStatus" class="status idle">正在加载...</span>
                </div>
                <div class="bar">
                  <span><strong>最近:</strong> <span id="lastActionText">—</span></span>
                  <span><strong>轮次:</strong> <span id="roundText">0/0</span></span>
                  <span class="links">
                    <a id="pageLink" href="/">/</a> · <a id="apiLogsLink" href="/api/logs">/api/logs</a> · <a href="/api/sessions">/api/sessions</a> · <a href="/health">/health</a>
                  </span>
                </div>
                <section class="control-panel">
                  <div class="control-head">
                    <strong>手动输入指令</strong>
                    <span class="meta">这里提交的文本会按语音指令同样的流程交给 Agent。</span>
                  </div>
                  <div class="control-row">
                    <textarea id="commandInput" placeholder="例如：打开微信，给张三发消息说我十分钟后到"></textarea>
                    <button id="commandButton" class="control-button" type="button">发送指令</button>
                  </div>
                  <div id="commandFeedback" class="control-feedback"></div>
                </section>
                <div id="timingSection" class="timing" hidden></div>
                <div id="sessionTabs" class="session-tabs"></div>
                <section id="logList" class="log-list"></section>
              </main>
              <script>
                const REFRESH_INTERVAL_MS = 5000;
                const state = {
                  limit: $limit,
                  selectedSessionId: $selectedSessionIdJson,
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

                function formatDurationMs(durationMs) {
                  const safe = Math.max(Number(durationMs) || 0, 0);
                  if (safe < 1000) return safe + 'ms';
                  const totalSeconds = Math.floor(safe / 1000);
                  const minutes = Math.floor(totalSeconds / 60);
                  const seconds = totalSeconds % 60;
                  return minutes > 0 ? minutes + 'm' + seconds + 's' : seconds + 's';
                }

                function buildPageUrl(sessionId) {
                  const params = new URLSearchParams();
                  params.set('limit', String(state.limit));
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
                  const tabs = ['<a class="session-tab' + (!state.selectedSessionId ? ' active' : '') + '" href="' + buildPageUrl('') + '">全部日志</a>'];
                  (sessions || []).forEach(session => {
                    const isActive = state.selectedSessionId && session.id === state.selectedSessionId;
                    tabs.push(
                      '<a class="session-tab' + (isActive ? ' active' : '') + '" href="' + buildPageUrl(session.id) + '">' +
                        escapeHtml(session.title || session.id || '未命名会话') +
                      '</a>'
                    );
                  });
                  document.getElementById('sessionTabs').innerHTML = tabs.join('');
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
                    return '<article class="entry ' + escapeHtml(String(entry.type || '').toLowerCase()) + '">' +
                      '<div class="entry-header">' +
                        '<span class="entry-type">' + escapeHtml(entry.type || '') + '</span>' +
                        '<span class="entry-time">' + escapeHtml(entry.timestampFormatted || '') + '</span>' +
                      '</div>' +
                      '<h3>' + escapeHtml(entry.title || '') + '</h3>' +
                      '<pre>' + escapeHtml(entry.content || '') + '</pre>' +
                      imageHtml +
                    '</article>';
                  }).join('');

                  list.innerHTML = rendered;
                }

                function updateLinks() {
                  document.getElementById('pageLink').href = buildPageUrl(state.selectedSessionId);
                  document.getElementById('apiLogsLink').href = buildApiUrl(false);
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
                  const selectedSession = state.snapshot.selectedSession || null;
                  const sessionTitle = selectedSession && selectedSession.title ? selectedSession.title : '全部日志';

                  document.getElementById('selectedTitle').textContent = sessionTitle + ' · 每 5 秒刷新';

                  const statusEl = document.getElementById('agentStatus');
                  const isRunning = !!agentState.isRunning;
                  statusEl.className = 'status ' + (isRunning ? 'running' : 'idle');
                  statusEl.textContent = (isRunning ? '执行中' : '空闲') + ' · ' + (agentState.statusMessage || '无状态');

                  document.getElementById('lastActionText').textContent = agentState.lastAction || '—';
                  document.getElementById('roundText').textContent = (agentState.currentRound || 0) + '/' + (agentState.maxRounds || 0);

                  renderStepTimings(agentState.stepTimings || []);
                  renderSessionTabs(state.snapshot.sessions || []);
                  renderEntries(state.snapshot.entries || []);
                  updateLinks();
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
                        history.replaceState(null, '', buildPageUrl(''));
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

                renderSnapshot(state.snapshot);
                window.setInterval(loadSnapshot, REFRESH_INTERVAL_MS);
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
