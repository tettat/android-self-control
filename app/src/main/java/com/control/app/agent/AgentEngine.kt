package com.control.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.control.app.adb.AdbExecutor
import com.control.app.ai.OpenAIClient
import com.control.app.data.AppSettings
import com.control.app.data.SettingsStore
import com.control.app.log.ExecutionLogFormatter
import com.control.app.prompt.DefaultPrompts
import com.control.app.prompt.PromptManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentEngine(
    appContext: Context,
    private val adbExecutor: AdbExecutor,
    private val openAIClient: OpenAIClient,
    private val promptManager: PromptManager,
    private val settingsStore: SettingsStore,
    val sessionManager: SessionManager = SessionManager(),
    private val skillStore: SkillStore? = null
) {

    companion object {
        private const val TAG = "AgentEngine"
        private const val ACTION_SETTLE_DELAY_MS = 500L
        private const val WAIT_DEFAULT_DURATION_MS = 400
        private const val WAIT_MIN_DURATION_MS = 150
        private const val WAIT_MAX_DURATION_MS = 5_000
        private const val MODEL_IMAGE_DETAIL_DEFAULT = "low"
        private const val MODEL_IMAGE_DETAIL_ZOOM = "high"
        private const val MODEL_IMAGE_JPEG_QUALITY = 52
        private const val MODEL_ZOOM_IMAGE_JPEG_QUALITY = 64
        private const val MAX_TOOL_CALLS_PER_STEP = 3
        private const val UI_DUMP_FAILURES_BEFORE_COOLDOWN = 2
        private const val UI_DUMP_COOLDOWN_CAPTURES = 5
        /** Compress history when message count exceeds this threshold. */
        private const val MAX_MESSAGES_BEFORE_COMPRESSION = 30
        /** Number of recent messages to keep verbatim during compression. */
        private const val KEEP_RECENT_COUNT = 8
        /** Minimum number of middle messages to justify a compression call. */
        private const val MIN_MESSAGES_TO_COMPRESS = 5
    }

    private val _debugLog = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val debugLog: StateFlow<List<DebugLogEntry>> = _debugLog.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _overlayVisible = MutableStateFlow(true)
    val overlayVisible: StateFlow<Boolean> = _overlayVisible.asStateFlow()

    private val appContext = appContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentJob: Job? = null

    /** Maps element number (#N) to center (x, y) coordinates for the current screenshot. */
    private var currentElementMap: Map<Int, Pair<Int, Int>> = emptyMap()

    /** Scale factor used for the current screenshot, for coordinate conversion. */
    private var currentScreenshotScale: Float = 1.0f

    /** Most recent screenshot base64 for tap annotation overlay. */
    private var lastScreenshotBase64: String? = null

    /**
     * Current zoom viewport in REAL screen coordinates.
     * null means the full screen is being viewed.
     * When zoomed, this represents the sub-rectangle being viewed.
     */
    private var currentZoomRect: android.graphics.RectF? = null

    /** How many upcoming captures should skip uiautomator dump after repeated failures. */
    private var uiDumpCooldownRemaining = 0

    /** Count consecutive uiautomator dump failures within the current task. */
    private var uiDumpFailureStreak = 0

    private fun setProgress(
        currentRound: Int = _agentState.value.currentRound,
        statusMessage: String = _agentState.value.statusMessage,
        activeTool: String = _agentState.value.activeTool,
        lastThinking: String = _agentState.value.lastThinking,
        lastAction: String = _agentState.value.lastAction,
        toolArguments: String = _agentState.value.currentToolArguments,
        stepIntent: String = _agentState.value.currentStepIntent,
        phaseTiming: TimingBreakdown? = null
    ) {
        val prev = _agentState.value
        val now = System.currentTimeMillis()
        val statusChanged = statusMessage != prev.statusMessage
        _agentState.value = prev.copy(
            currentRound = currentRound,
            statusMessage = statusMessage,
            activeTool = activeTool,
            lastThinking = lastThinking,
            lastAction = lastAction,
            currentToolArguments = toolArguments,
            currentStepIntent = stepIntent,
            phaseStartedAtMs = if (statusChanged) now else prev.phaseStartedAtMs,
            stepTimings = if (statusChanged) finalizeCurrentStep(prev, now) else prev.stepTimings,
            currentPhaseTiming = when {
                statusChanged -> phaseTiming ?: TimingBreakdown()
                phaseTiming != null -> prev.currentPhaseTiming.merge(phaseTiming)
                else -> prev.currentPhaseTiming
            },
            lastProgressAtMs = now
        )
    }

    private fun finalizeCurrentStep(state: AgentState, endTimeMs: Long): List<StepTiming> {
        if (state.phaseStartedAtMs <= 0L || state.statusMessage.isBlank()) {
            return state.stepTimings
        }

        val durationMs = (endTimeMs - state.phaseStartedAtMs).coerceAtLeast(0L)
        val previous = state.stepTimings.lastOrNull()
        if (previous != null &&
            previous.label == state.statusMessage &&
            previous.startedAtMs == state.phaseStartedAtMs
        ) {
            return state.stepTimings
        }

        return state.stepTimings + StepTiming(
            label = state.statusMessage,
            tool = state.activeTool,
            toolArguments = state.currentToolArguments,
            intent = state.currentStepIntent,
            startedAtMs = state.phaseStartedAtMs,
            finishedAtMs = endTimeMs,
            durationMs = durationMs,
            breakdown = state.currentPhaseTiming
        )
    }

    private fun buildStepTimingSummary(stepTimings: List<StepTiming>): String {
        if (stepTimings.isEmpty()) return "无步骤耗时记录"
        return buildString {
            stepTimings.forEachIndexed { index, step ->
                append(ExecutionLogFormatter.formatStepTimingLine(index, step))
                if (index != stepTimings.lastIndex) {
                    append('\n')
                }
            }
        }
    }

    private fun formatDurationMs(durationMs: Long): String {
        val safe = durationMs.coerceAtLeast(0L)
        return if (safe < 1_000L) {
            "${safe}ms"
        } else {
            val totalSeconds = safe / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            if (minutes > 0) "${minutes}m${seconds}s" else "${seconds}s"
        }
    }

    private fun formatTimingBreakdown(breakdown: TimingBreakdown): String {
        return ExecutionLogFormatter.formatTimingBreakdown(breakdown)
    }

    fun addLog(entry: DebugLogEntry) {
        _debugLog.value = _debugLog.value + entry
        sessionManager.addEntryToCurrentSession(entry)
    }

    fun clearLogs() {
        _debugLog.value = emptyList()
    }

    private fun showTaskCompletionToast(message: String) {
        val singleLineMessage = message
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "任务完成"
        val toastMessage = "任务结束: ${singleLineMessage.take(80)}" +
            if (singleLineMessage.length > 80) "..." else ""

        mainHandler.post {
            Toast.makeText(appContext, toastMessage, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun executeCommand(voiceCommand: String): String = coroutineScope {
        val settings = settingsStore.settings.first()

        if (settings.apiEndpoint.isBlank()) {
            val errorMsg = "API 端点未配置，请先在设置中填写"
            addLog(DebugLogEntry(type = DebugLogType.ERROR, title = "配置错误", content = errorMsg))
            _agentState.value = AgentState(statusMessage = errorMsg)
            showTaskCompletionToast(errorMsg)
            return@coroutineScope errorMsg
        }

        if (settings.apiKey.isBlank()) {
            val errorMsg = "API Key 未配置，请先在设置中填写"
            addLog(DebugLogEntry(type = DebugLogType.ERROR, title = "配置错误", content = errorMsg))
            _agentState.value = AgentState(statusMessage = errorMsg)
            showTaskCompletionToast(errorMsg)
            return@coroutineScope errorMsg
        }

        if (!adbExecutor.isConnected()) {
            val errorMsg = "ADB 未连接，请先在设置中连接无线调试"
            addLog(DebugLogEntry(type = DebugLogType.ERROR, title = "ADB 错误", content = errorMsg))
            _agentState.value = AgentState(statusMessage = errorMsg)
            showTaskCompletionToast(errorMsg)
            return@coroutineScope errorMsg
        }

        // Create a new session for this task
        val session = sessionManager.createSession(voiceCommand)
        val startTime = System.currentTimeMillis()
        var finalStepTimings: List<StepTiming> = emptyList()

        resetCaptureStateForNewTask()

        _agentState.value = AgentState(
            isRunning = true,
            currentRound = 0,
            maxRounds = settings.maxRounds,
            statusMessage = "正在执行: $voiceCommand",
            lastAction = "收到指令",
            taskStartedAtMs = startTime,
            phaseStartedAtMs = startTime,
            lastProgressAtMs = startTime
        )

        addLog(
            DebugLogEntry(
                type = DebugLogType.VOICE_INPUT,
                title = "语音指令",
                content = voiceCommand
            )
        )

        // Use the session's message history so context survives across the session lifecycle
        val messageHistory = session.messageHistory
        var resultMessage = "任务完成"
        var step = 0
        var reflectionPhaseRequested = false
        var reflectionPromptInjected = false
        var reflectionSummary = ""
        val savedSkillsInReflection = mutableListOf<String>()

        try {
            // System message
            val systemPrompt = promptManager.getPrompt(DefaultPrompts.PROMPT_KEY_SYSTEM)
            messageHistory.add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })

            // Take initial screenshot + UI tree
            setProgress(
                statusMessage = "正在截图...",
                activeTool = "",
                lastAction = "准备初始截图与界面树",
                stepIntent = "准备初始截图与界面树"
            )

            val initialCapture = captureScreen(settings.screenshotScale)
            setProgress(phaseTiming = initialCapture.timing)

            val imageBase64 = initialCapture.imageBase64
            val screenshotWidth = initialCapture.screenshotWidth
            val screenshotHeight = initialCapture.screenshotHeight
            val uiTree = initialCapture.uiTree

            val gridImage = drawZoneGrid(imageBase64)
            val initialCaptureTimingSummary = formatTimingBreakdown(initialCapture.timing)

            addLog(
                DebugLogEntry(
                    type = DebugLogType.SCREENSHOT,
                    title = "初始截图",
                    content = buildString {
                        append("截图: ${screenshotWidth}x${screenshotHeight}")
                        append(if (uiTree != null) ", ${currentElementMap.size}个可交互元素" else ", 控件树不可用")
                        if (initialCaptureTimingSummary.isNotBlank()) {
                            appendLine()
                            append("耗时拆分: $initialCaptureTimingSummary")
                        }
                    },
                    imageBase64 = gridImage
                )
            )

            // Build initial user message with screenshot + task（注入已保存技巧名称供模型选择加载）
            val skillNames = skillStore?.getAllSkills()?.map { "${it.appName} (${it.packageName})" } ?: emptyList()
            val actionPrompt = promptManager.buildActionPrompt(
                userCommand = voiceCommand,
                screenshotWidth = screenshotWidth,
                screenshotHeight = screenshotHeight,
                uiTree = uiTree,
                skillNames = skillNames
            )

            val userMessage = buildUserMessageWithImage(actionPrompt, gridImage)
            messageHistory.add(userMessage)

            // Main tool-calling loop
            while (step < settings.maxRounds) {
                step++
                setProgress(
                    currentRound = step,
                    statusMessage = "第 ${step}/${settings.maxRounds} 步: 正在调用AI...",
                    activeTool = "",
                    lastAction = "等待 AI 决策",
                    stepIntent = "调用模型生成下一步动作"
                )

                Log.d(TAG, "=== Step $step/${settings.maxRounds} ===")

                addLog(
                    DebugLogEntry(
                        type = DebugLogType.API_REQUEST,
                        title = "API 请求 (第${step}步)",
                        content = buildString {
                            appendLine("准备发送 AI 请求")
                            appendLine("History messages: ${messageHistory.size}")
                            appendLine("Tools: ${ToolDefinitions.AGENT_TOOLS.size} defined")
                        }
                    )
                )

                // Call AI with tools
                val apiResult = openAIClient.chatWithTools(
                    messages = JsonArray(messageHistory),
                    tools = ToolDefinitions.AGENT_TOOLS,
                    apiEndpoint = settings.apiEndpoint,
                    apiKey = settings.apiKey,
                    modelName = settings.modelName,
                    logHttp = { httpLog ->
                        addLog(
                            DebugLogEntry(
                                type = if (httpLog.title.contains("响应")) {
                                    DebugLogType.API_RESPONSE
                                } else {
                                    DebugLogType.API_REQUEST
                                },
                                title = "${httpLog.title} (第${step}步)",
                                content = httpLog.content,
                                imageBase64 = httpLog.imageBase64,
                                imageMimeType = httpLog.imageMimeType,
                                images = httpLog.images
                            )
                        )
                    }
                )

                val apiCall = apiResult.getOrElse { e ->
                    val errMsg = "API 调用失败: ${e.message}"
                    addLog(DebugLogEntry(type = DebugLogType.ERROR, title = "API 错误", content = errMsg))
                    Log.e(TAG, errMsg, e)
                    throw RuntimeException(errMsg, e)
                }
                val apiTiming = TimingBreakdown(
                    uploadMs = apiCall.timing.uploadMs,
                    modelMs = apiCall.timing.modelMs
                )
                val apiTimingSummary = formatTimingBreakdown(apiTiming)
                setProgress(
                    phaseTiming = apiTiming
                )
                val result = apiCall.result

                when (result) {
                    is AIChatResult.TextResponse -> {
                        // AI chose to respond with text -- task complete or communication
                        messageHistory.add(result.rawMessage)

                        addLog(
                            DebugLogEntry(
                                type = DebugLogType.API_RESPONSE,
                                title = "AI 文本响应 (第${step}步)",
                                content = buildString {
                                    append(result.content)
                                    if (apiTimingSummary.isNotBlank()) {
                                        appendLine()
                                        append("耗时拆分: $apiTimingSummary")
                                    }
                                }
                            )
                        )

                        setProgress(
                            lastThinking = result.content.take(200),
                            activeTool = "",
                            lastAction = "AI 返回了最终文本响应"
                        )

                        // In reflection phase: capture summary and ask model to call complete, then continue
                        if (reflectionPhaseRequested && reflectionPromptInjected) {
                            reflectionSummary = result.content
                            messageHistory.add(
                                buildTextOnlyUserMessage("若已反思完毕，请调用 complete 工具结束任务。")
                            )
                            setProgress(
                                statusMessage = "反思中：已收到总结，请调用 complete 结束",
                                lastAction = "等待模型调用 complete…",
                                stepIntent = "等待模型调用 complete 结束"
                            )
                            addLog(
                                DebugLogEntry(
                                    type = DebugLogType.INFO,
                                    title = "反思阶段",
                                    content = "已收到反思总结，等待模型调用 complete 结束"
                                )
                            )
                        } else {
                            resultMessage = result.content.ifBlank { "任务完成" }
                            break
                        }
                    }

                    is AIChatResult.ToolCallResponse -> {
                        // Add assistant's message (with tool_calls) to history
                        messageHistory.add(result.rawMessage)

                        val toolExecutionPlan = planToolExecution(result.toolCalls)
                        val toolNames = result.toolCalls.map { it.functionName }
                        val executableToolNames = toolExecutionPlan
                            .filter { it.shouldExecute }
                            .map { it.toolCall.functionName }
                        val skippedToolCalls = toolExecutionPlan.filterNot { it.shouldExecute }
                        addLog(
                            DebugLogEntry(
                                type = DebugLogType.API_RESPONSE,
                                title = "AI 工具调用 (第${step}步)",
                                content = buildString {
                                    appendLine("工具调用: ${toolNames.joinToString(", ")}")
                                    for (tc in result.toolCalls) {
                                        appendLine("  ${tc.functionName}(${tc.arguments})")
                                    }
                                    if (skippedToolCalls.isNotEmpty()) {
                                        appendLine()
                                        appendLine("批量执行裁剪:")
                                        skippedToolCalls.forEach { skipped ->
                                            appendLine("  跳过 ${skipped.toolCall.functionName}: ${skipped.skipReason}")
                                        }
                                    }
                                    if (apiTimingSummary.isNotBlank()) {
                                        append("耗时拆分: $apiTimingSummary")
                                    }
                                }
                            )
                        )

                        Log.d(TAG, "AI requested tools: $toolNames")

                        val stepIntentFromAssistant = extractTextContent(result.rawMessage).trim().take(400)

                        setProgress(
                            statusMessage = "第 ${step}/${settings.maxRounds} 步: 正在执行 ${executableToolNames.firstOrNull().orEmpty()}...",
                            activeTool = executableToolNames.firstOrNull().orEmpty(),
                            lastAction = "AI 规划了 ${toolNames.size} 个动作，本轮执行 ${executableToolNames.size} 个",
                            stepIntent = stepIntentFromAssistant
                        )

                        var taskCompleted = false
                        var completionMessage = ""
                        var zoomImageOverride: String? = null

                        // Execute each tool call and add results to history
                        for (plannedTool in toolExecutionPlan) {
                            val toolCall = plannedTool.toolCall
                            if (!plannedTool.shouldExecute) {
                                messageHistory.add(
                                    buildToolResultMessage(
                                        toolCall.id,
                                        "SKIPPED: ${plannedTool.skipReason}"
                                    )
                                )
                                continue
                            }
                            var toolStartedAt = 0L
                            try {
                                setProgress(
                                    statusMessage = "第 ${step}/${settings.maxRounds} 步: 正在执行 ${toolCall.functionName}...",
                                    activeTool = toolCall.functionName,
                                    lastAction = "开始执行 ${toolCall.functionName}",
                                    toolArguments = formatToolArguments(toolCall.arguments),
                                    stepIntent = stepIntentFromAssistant
                                )

                                // Hide overlay before interactive actions
                                val isInteractive = toolCall.functionName in listOf(
                                    "tap", "tap_element", "input_element", "swipe",
                                    "input_text", "scroll_down", "scroll_up", "adb_shell",
                                    "launch_app", "press_back", "press_home", "key_event",
                                    "tap_region"
                                )
                                if (isInteractive) {
                                    _overlayVisible.value = false
                                    delay(50)
                                }

                                toolStartedAt = System.currentTimeMillis()
                                val toolResult = executeToolCall(toolCall)
                                val toolMs = (System.currentTimeMillis() - toolStartedAt).coerceAtLeast(0L)

                                if (isInteractive) {
                                    _overlayVisible.value = true
                                }

                                // Add tool result to history
                                messageHistory.add(buildToolResultMessage(toolCall.id, toolResult))

                                if (toolCall.functionName == "save_skill" && reflectionPromptInjected) {
                                    savedSkillsInReflection.add(toolResult)
                                }

                                addLog(
                                    DebugLogEntry(
                                        type = DebugLogType.ACTION_EXECUTED,
                                        title = "执行: ${toolCall.functionName}",
                                        content = buildString {
                                            appendLine("参数: ${toolCall.arguments}")
                                            appendLine("结果: ${toolResult.take(200)}")
                                            append("耗时拆分: tool_ms=${toolMs}ms")
                                        }
                                    )
                                )

                                Log.d(TAG, "Tool ${toolCall.functionName} done: ${toolResult.take(80)}")
                                setProgress(
                                    activeTool = toolCall.functionName,
                                    lastAction = "${toolCall.functionName}: ${toolResult.take(80)}",
                                    phaseTiming = TimingBreakdown(toolMs = toolMs)
                                )

                                // Check if this was the "complete" tool
                                if (toolCall.functionName == "complete") {
                                    if (reflectionPhaseRequested) {
                                        taskCompleted = true
                                        val msg = toolCall.arguments["message"]?.jsonPrimitive?.content
                                        completionMessage = msg ?: "任务完成"
                                    } else {
                                        reflectionPhaseRequested = true
                                        // Will inject reflection prompt after this for-loop
                                    }
                                }

                                // If zoom_region was called, capture the zoomed+grid image for override
                                if (toolCall.functionName == "zoom_region") {
                                    lastScreenshotBase64?.let { cropped ->
                                        zoomImageOverride = drawZoneGrid(cropped)
                                    }
                                }

                                // Wait for UI to settle after interactive actions
                                if (isInteractive) {
                                    delay(ACTION_SETTLE_DELAY_MS)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                _overlayVisible.value = true
                                val toolMs = if (toolStartedAt > 0L) {
                                    (System.currentTimeMillis() - toolStartedAt).coerceAtLeast(0L)
                                } else {
                                    0L
                                }

                                val errMsg = "工具执行失败 [${toolCall.functionName}]: ${e.message}"
                                messageHistory.add(buildToolResultMessage(toolCall.id, "ERROR: $errMsg"))

                                addLog(
                                    DebugLogEntry(
                                        type = DebugLogType.ERROR,
                                        title = "工具失败: ${toolCall.functionName}",
                                        content = if (toolMs > 0L) {
                                            "$errMsg\n耗时拆分: tool_ms=${toolMs}ms"
                                        } else {
                                            errMsg
                                        }
                                    )
                                )
                                Log.e(TAG, errMsg, e)
                                setProgress(
                                    activeTool = toolCall.functionName,
                                    lastAction = errMsg.take(120),
                                    phaseTiming = TimingBreakdown(toolMs = toolMs)
                                )
                                setProgress(
                                    statusMessage = "第 ${step}/${settings.maxRounds} 步: ${toolCall.functionName} 失败",
                                    activeTool = toolCall.functionName,
                                    lastAction = errMsg.take(120),
                                    toolArguments = formatToolArguments(toolCall.arguments),
                                    stepIntent = stepIntentFromAssistant
                                )
                            }
                        }

                        if (taskCompleted) {
                            resultMessage = completionMessage
                            if (reflectionSummary.isNotBlank() || savedSkillsInReflection.isNotEmpty()) {
                                resultMessage += "\n\n【反思】\n"
                                if (reflectionSummary.isNotBlank()) {
                                    resultMessage += reflectionSummary.trim() + "\n"
                                }
                                if (savedSkillsInReflection.isNotEmpty()) {
                                    resultMessage += "已保存技巧：\n" + savedSkillsInReflection.joinToString("\n")
                                }
                            }
                            break
                        }

                        if (reflectionPhaseRequested && !reflectionPromptInjected) {
                            messageHistory.add(
                                buildTextOnlyUserMessage(
                                    "请进行任务结束反思：\n1) 本次执行中做对的地方\n2) 做错或可改进的地方\n3) 若有可复用经验请调用 save_skill 保存。\n完成反思后再次调用 complete 工具表示真正结束。"
                                )
                            )
                            reflectionPromptInjected = true
                            addLog(
                                DebugLogEntry(
                                    type = DebugLogType.INFO,
                                    title = "反思阶段",
                                    content = "已插入反思提示，等待模型总结并可选保存技巧"
                                )
                            )
                            setProgress(
                                statusMessage = "反思中：总结做对/做错的地方，并可选保存技巧",
                                lastAction = "等待反思结果…",
                                stepIntent = "总结做对/做错的地方并可选保存技巧"
                            )
                        } else {
                        val shouldCaptureFreshScreen = shouldCaptureAfterToolCalls(
                            toolExecutionPlan.filter { it.shouldExecute }.map { it.toolCall }
                        )
                        if (zoomImageOverride != null || shouldCaptureFreshScreen) {
                            stripImagesFromHistory(messageHistory)
                        }

                        if (zoomImageOverride != null) {
                            // zoom_region was called: send the zoomed image instead of taking a new screenshot
                            val zoomMsg = buildUserMessageWithImage(
                                "已放大到指定区域，请查看上图。继续选择子区域放大(zoom_region)或点击(tap_region)。区域编号: 第1行123，第2行456，第3行789。",
                                zoomImageOverride!!,
                                detail = MODEL_IMAGE_DETAIL_ZOOM
                            )
                            messageHistory.add(zoomMsg)
                        } else if (shouldCaptureFreshScreen) {
                            // Reset zoom state for fresh screenshot
                            currentZoomRect = null

                            // After executing tool calls, take a fresh screenshot and append as user message
                            // This gives the AI up-to-date visual context for its next decision
                            setProgress(
                                statusMessage = "第 ${step}/${settings.maxRounds} 步: 正在截图...",
                                activeTool = "",
                                lastAction = "动作执行完成，刷新界面",
                                stepIntent = "动作执行完成，刷新界面"
                            )

                            val followUpCapture = captureScreen(settings.screenshotScale)
                            setProgress(phaseTiming = followUpCapture.timing)

                            val newImageBase64 = followUpCapture.imageBase64
                            val newWidth = followUpCapture.screenshotWidth
                            val newHeight = followUpCapture.screenshotHeight
                            val newUiTree = followUpCapture.uiTree
                            val gridFollowUp = drawZoneGrid(newImageBase64)
                            val followUpTimingSummary = formatTimingBreakdown(followUpCapture.timing)

                            addLog(
                                DebugLogEntry(
                                    type = DebugLogType.SCREENSHOT,
                                    title = "截图 (第${step}步后)",
                                    content = buildString {
                                        append("截图: ${newWidth}x${newHeight}")
                                        append(if (newUiTree != null) ", ${currentElementMap.size}个可交互元素" else ", 控件树不可用")
                                        if (followUpTimingSummary.isNotBlank()) {
                                            appendLine()
                                            append("耗时拆分: $followUpTimingSummary")
                                        }
                                    },
                                    imageBase64 = gridFollowUp
                                )
                            )

                            val followUpSkillNames = skillStore?.getAllSkills()?.map { "${it.appName} (${it.packageName})" } ?: emptyList()
                            val followUpPrompt = promptManager.buildFollowUpPrompt(
                                screenshotWidth = newWidth,
                                screenshotHeight = newHeight,
                                uiTree = newUiTree,
                                skillNames = followUpSkillNames
                            )

                            val followUpMessage = buildUserMessageWithImage(followUpPrompt, gridFollowUp)
                            messageHistory.add(followUpMessage)
                        } else {
                            addLog(
                                DebugLogEntry(
                                    type = DebugLogType.INFO,
                                    title = "跳过截图",
                                    content = "刚才执行的工具不会直接改变界面，复用上一张截图继续推理"
                                )
                            )
                            messageHistory.add(
                                buildTextOnlyUserMessage(
                                    "没有新的屏幕截图。刚才执行的工具不会直接改变界面，请结合最近一次截图和最新工具结果继续。"
                                )
                            )
                        }

                        // Compress conversation history if it has grown too long
                        compressHistoryIfNeeded(messageHistory, settings)
                        }
                    }
                }
            }

            if (step >= settings.maxRounds && resultMessage == "任务完成") {
                resultMessage = "已达到最大步数限制 (${settings.maxRounds})，任务可能未完成"
            }

        } catch (e: CancellationException) {
            resultMessage = "任务已取消"
            addLog(DebugLogEntry(type = DebugLogType.INFO, title = "已取消", content = "用户取消了任务"))
            throw e
        } catch (e: Exception) {
            resultMessage = "任务失败: ${e.message}"
            addLog(
                DebugLogEntry(
                    type = DebugLogType.ERROR,
                    title = "任务失败",
                    content = "Error: ${e.message}\n${e.stackTraceToString().take(500)}"
                )
            )
            Log.e(TAG, "Task failed", e)
        } finally {
            _overlayVisible.value = true
            val finishTime = System.currentTimeMillis()
            finalStepTimings = finalizeCurrentStep(_agentState.value, finishTime)
            _agentState.value = AgentState(
                isRunning = false,
                currentRound = step,
                maxRounds = settings.maxRounds,
                statusMessage = resultMessage,
                lastAction = resultMessage,
                taskStartedAtMs = startTime,
                phaseStartedAtMs = finishTime,
                lastProgressAtMs = finishTime,
                stepTimings = finalStepTimings
            )
            addLog(
                DebugLogEntry(
                    type = DebugLogType.INFO,
                    title = "任务结束",
                    content = buildString {
                        appendLine("结果: $resultMessage")
                        appendLine("总步数: $step")
                        append("总耗时: ${formatDurationMs(finishTime - startTime)}")
                        if (finalStepTimings.isNotEmpty()) {
                            appendLine()
                            appendLine()
                            appendLine("步骤耗时:")
                            append(buildStepTimingSummary(finalStepTimings))
                        }
                    }
                )
            )
            // Mark session as ended
            sessionManager.endCurrentSession(resultMessage)
            showTaskCompletionToast(resultMessage)
        }

        resultMessage
    }

    /**
     * Compress conversation history when it exceeds the threshold.
     *
     * Strategy:
     * 1. Always keep the system message (index 0)
     * 2. Always keep the most recent [KEEP_RECENT_COUNT] messages
     * 3. Summarize everything in between via a separate AI call
     * 4. Replace the middle messages with a compact summary + acknowledgment
     */
    private suspend fun compressHistoryIfNeeded(
        messageHistory: MutableList<JsonObject>,
        settings: AppSettings
    ) {
        if (messageHistory.size <= MAX_MESSAGES_BEFORE_COMPRESSION) return

        val systemMessage = messageHistory[0]
        val middleEnd = messageHistory.size - KEEP_RECENT_COUNT
        if (middleEnd <= 1) return  // Nothing meaningful to compress

        val middleMessages = messageHistory.subList(1, middleEnd).toList()
        val recentMessages = messageHistory.takeLast(KEEP_RECENT_COUNT)

        if (middleMessages.size < MIN_MESSAGES_TO_COMPRESS) return

        Log.d(TAG, "Compressing history: ${messageHistory.size} messages, " +
                "${middleMessages.size} middle messages to summarize")

        val summaryPrompt = buildString {
            appendLine("以下是之前的对话历史。请用简洁的中文总结关键操作和状态：")
            appendLine("- 用户的任务是什么")
            appendLine("- 已经完成了哪些步骤")
            appendLine("- 当前停留在什么界面/状态")
            appendLine("- 遇到了什么问题（如果有）")
            appendLine()
            appendLine("请用不超过500字总结。")
        }

        // Build a temporary conversation for the summarization request.
        // Only extract text content (skip images to save tokens).
        val summaryMessages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", "你是一个对话历史总结助手。请简洁准确地总结以下对话历史。")
            })
            for (msg in middleMessages) {
                val role = (msg["role"] as? JsonPrimitive)?.content ?: continue
                if (role == "system") continue
                val content = extractTextContent(msg)
                if (content.isNotBlank()) {
                    add(buildJsonObject {
                        // The "tool" role is not valid in a standalone conversation,
                        // so we present it as a user message with a role prefix.
                        put("role", if (role == "tool") "user" else role)
                        put("content", "[$role] ${content.take(1000)}")
                    })
                }
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", summaryPrompt)
            })
        }

        val summary = openAIClient.chatSimple(
            messages = summaryMessages,
            apiEndpoint = settings.apiEndpoint,
            apiKey = settings.apiKey,
            modelName = settings.modelName
        ).getOrElse { e ->
            // If summarization fails, build a best-effort dumb summary from the first few messages
            Log.w(TAG, "Summarization API call failed: ${e.message}")
            buildString {
                appendLine("(自动压缩的历史摘要 - 摘要生成失败，仅保留片段)")
                middleMessages.take(3).forEach { msg ->
                    val role = (msg["role"] as? JsonPrimitive)?.content
                    val content = extractTextContent(msg).take(100)
                    if (role != null && content.isNotBlank()) {
                        appendLine("[$role] $content...")
                    }
                }
            }
        }

        val originalSize = messageHistory.size

        // Rebuild: system + summary + assistant ack + recent messages
        messageHistory.clear()
        messageHistory.add(systemMessage)
        messageHistory.add(buildJsonObject {
            put("role", "user")
            put("content", "以下是之前操作的摘要：\n$summary\n\n请基于这个上下文继续任务。")
        })
        messageHistory.add(buildJsonObject {
            put("role", "assistant")
            put("content", "了解，我已掌握之前的操作上下文，将继续执行任务。")
        })
        messageHistory.addAll(recentMessages)

        addLog(
            DebugLogEntry(
                type = DebugLogType.INFO,
                title = "上下文压缩",
                content = "历史消息已从 ${originalSize} 条压缩为 ${messageHistory.size} 条\n" +
                        "摘要: ${summary.take(200)}..."
            )
        )

        Log.d(TAG, "History compressed: $originalSize -> ${messageHistory.size} messages")
    }

    /** Format tool call arguments for step timing log (compact one-line). */
    private fun formatToolArguments(args: JsonObject): String {
        if (args.isEmpty()) return ""
        return args.entries.joinToString(", ") { (k, v) ->
            val vStr = (v as? JsonPrimitive)?.content ?: v.toString()
            "$k=${vStr.take(80)}"
        }.take(400)
    }

    /**
     * Extract text content from a message, handling both plain-string and content-array formats.
     */
    private fun extractTextContent(message: JsonObject): String {
        val content = message["content"] ?: return ""
        return when (content) {
            is JsonPrimitive -> if (content.isString) content.content else ""
            is JsonArray -> {
                content.filterIsInstance<JsonObject>()
                    .filter { (it["type"] as? JsonPrimitive)?.content == "text" }
                    .joinToString("\n") { (it["text"] as? JsonPrimitive)?.content ?: "" }
            }
            is kotlinx.serialization.json.JsonNull -> ""
            else -> content.toString()
        }
    }

    /**
     * Capture screenshot and UI tree. Returns a data class with all needed info.
     * Handles overlay hiding/showing and coordinate scaling.
     */
    private data class ScreenCapture(
        val imageBase64: String,
        val screenshotWidth: Int,
        val screenshotHeight: Int,
        val uiTree: String?,
        val timing: TimingBreakdown
    )

    private suspend fun captureScreen(screenshotScale: Float): ScreenCapture {
        // Hide overlay before taking screenshot and UI dump
        _overlayVisible.value = false
        delay(150)

        // Take screenshot
        val screenshotStartedAt = System.currentTimeMillis()
        val screenshotResult = adbExecutor.takeScreenshot(screenshotScale)
        val screenshotMs = (System.currentTimeMillis() - screenshotStartedAt).coerceAtLeast(0L)

        // Dump UI hierarchy while overlay is still hidden
        currentElementMap = emptyMap()
        val uiDumpStartedAt = System.currentTimeMillis()
        val uiTreeResult = if (uiDumpCooldownRemaining > 0) {
            uiDumpCooldownRemaining--
            Log.d(TAG, "Skipping UI dump due to cooldown, remaining=$uiDumpCooldownRemaining")
            null
        } else {
            try {
                adbExecutor.dumpUiHierarchy().fold(
                    onSuccess = { rawXml ->
                        if (rawXml.isBlank()) {
                            registerUiDumpFailure("uiautomator dump returned empty XML")
                            null
                        } else {
                            uiDumpFailureStreak = 0
                            simplifyUiTree(rawXml)
                        }
                    },
                    onFailure = { error ->
                        registerUiDumpFailure(error.message ?: "unknown error")
                        Log.w(TAG, "UI tree dump failed: ${error.message}")
                        null
                    }
                )
            } catch (e: Exception) {
                registerUiDumpFailure(e.message ?: "unknown error")
                Log.w(TAG, "UI tree dump failed: ${e.message}")
                null
            }
        }
        val uiDumpMs = (System.currentTimeMillis() - uiDumpStartedAt).coerceAtLeast(0L)
        val uiTree = uiTreeResult?.text
        if (uiTreeResult != null) {
            currentElementMap = uiTreeResult.elementMap
        }

        // Show overlay after both screenshot and UI dump
        _overlayVisible.value = true

        val screenshot = screenshotResult.getOrElse { e ->
            val msg = e.message ?: ""
            val errMsg = if (msg.contains("reconnect failed", ignoreCase = true) ||
                msg.contains("connect", ignoreCase = true)
            ) {
                "ADB 连接断开，自动重连失败: ${e.message}"
            } else {
                "截图失败: ${e.message}"
            }
            addLog(DebugLogEntry(type = DebugLogType.ERROR, title = "截图错误", content = errMsg))
            Log.e(TAG, errMsg, e)
            throw RuntimeException(errMsg, e)
        }

        // Encode screenshot to base64
        val encodeStartedAt = System.currentTimeMillis()
        val imageBase64 = bitmapToBase64(screenshot)
        val encodeMs = (System.currentTimeMillis() - encodeStartedAt).coerceAtLeast(0L)
        val screenshotWidth = screenshot.width
        val screenshotHeight = screenshot.height
        screenshot.recycle()

        // Update scale factor for coordinate conversion
        currentScreenshotScale = screenshotScale

        // Save for tap annotation overlay
        lastScreenshotBase64 = imageBase64

        return ScreenCapture(
            imageBase64 = imageBase64,
            screenshotWidth = screenshotWidth,
            screenshotHeight = screenshotHeight,
            uiTree = uiTree,
            timing = TimingBreakdown(
                screenshotMs = screenshotMs,
                uiDumpMs = uiDumpMs,
                encodeMs = encodeMs
            )
        )
    }

    /**
     * Execute a single tool call from the AI. Returns a result string.
     */
    private suspend fun executeToolCall(toolCall: ToolCall): String {
        val args = toolCall.arguments
        return when (toolCall.functionName) {
            "tap_element" -> {
                val elementId = args["element"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("tap_element requires 'element' parameter")
                val (x, y) = currentElementMap[elementId]
                    ?: throw IllegalArgumentException("Element #$elementId not found (available: ${currentElementMap.keys.sorted()})")
                Log.d(TAG, "tap_element #$elementId -> ($x, $y)")
                adbExecutor.tap(x, y).getOrThrow()
                // Annotate screenshot with tap position
                val scale = currentScreenshotScale
                lastScreenshotBase64?.let { screenshot ->
                    val scrX = (x * scale).toInt()
                    val scrY = (y * scale).toInt()
                    val label = "tap_element #$elementId ($x,$y)"
                    val annotated = annotateScreenshotWithTap(screenshot, scrX, scrY, label)
                    addLog(DebugLogEntry(
                        type = DebugLogType.INFO,
                        title = "点击位置标注",
                        content = "tap_element #$elementId 实际坐标($x,$y) 截图坐标($scrX,$scrY)",
                        imageBase64 = annotated
                    ))
                }
                "Tapped element #$elementId at ($x, $y)"
            }

            "input_element" -> {
                val elementId = args["element"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("input_element requires 'element' parameter")
                val text = args["text"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("input_element requires 'text' parameter")
                val (x, y) = currentElementMap[elementId]
                    ?: throw IllegalArgumentException("Element #$elementId not found (available: ${currentElementMap.keys.sorted()})")
                Log.d(TAG, "input_element #$elementId -> tap($x, $y) then input '$text'")
                adbExecutor.tap(x, y).getOrThrow()
                delay(300)
                adbExecutor.inputText(text).getOrThrow()
                // Annotate screenshot with tap position
                val scale = currentScreenshotScale
                lastScreenshotBase64?.let { screenshot ->
                    val scrX = (x * scale).toInt()
                    val scrY = (y * scale).toInt()
                    val label = "input_element #$elementId ($x,$y)"
                    val annotated = annotateScreenshotWithTap(screenshot, scrX, scrY, label)
                    addLog(DebugLogEntry(
                        type = DebugLogType.INFO,
                        title = "点击位置标注",
                        content = "input_element #$elementId 实际坐标($x,$y) 截图坐标($scrX,$scrY) 输入'$text'",
                        imageBase64 = annotated
                    ))
                }
                "Tapped element #$elementId at ($x, $y) and input '$text'"
            }

            "tap" -> {
                val rawX = args["x"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("tap requires 'x' parameter")
                val rawY = args["y"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("tap requires 'y' parameter")
                val scale = currentScreenshotScale
                val x = if (scale > 0f && scale < 1f) (rawX / scale).toInt() else rawX
                val y = if (scale > 0f && scale < 1f) (rawY / scale).toInt() else rawY
                Log.d(TAG, "tap: AI coords ($rawX, $rawY) -> screen coords ($x, $y) [scale=$scale]")
                adbExecutor.tap(x, y).getOrThrow()
                // Annotate screenshot with tap position (rawX/rawY are already in screenshot coords)
                lastScreenshotBase64?.let { screenshot ->
                    val label = "tap ($rawX,$rawY)->($x,$y)"
                    val annotated = annotateScreenshotWithTap(screenshot, rawX, rawY, label)
                    addLog(DebugLogEntry(
                        type = DebugLogType.INFO,
                        title = "点击位置标注",
                        content = "tap 截图坐标($rawX,$rawY) 实际坐标($x,$y)",
                        imageBase64 = annotated
                    ))
                }
                "Tapped at ($x, $y) [screenshot coords: ($rawX, $rawY)]"
            }

            "swipe" -> {
                val rawSx = args["startX"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("swipe requires 'startX'")
                val rawSy = args["startY"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("swipe requires 'startY'")
                val rawEx = args["endX"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("swipe requires 'endX'")
                val rawEy = args["endY"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("swipe requires 'endY'")
                val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 300
                val scale = currentScreenshotScale
                val sx = if (scale > 0f && scale < 1f) (rawSx / scale).toInt() else rawSx
                val sy = if (scale > 0f && scale < 1f) (rawSy / scale).toInt() else rawSy
                val ex = if (scale > 0f && scale < 1f) (rawEx / scale).toInt() else rawEx
                val ey = if (scale > 0f && scale < 1f) (rawEy / scale).toInt() else rawEy
                adbExecutor.swipe(sx, sy, ex, ey, duration).getOrThrow()
                "Swiped from ($sx,$sy) to ($ex,$ey) in ${duration}ms"
            }

            "launch_app" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("launch_app requires 'package_name'")
                adbExecutor.launchApp(pkg).getOrThrow()
                "Launched $pkg"
            }

            "press_back" -> {
                adbExecutor.pressBack().getOrThrow()
                "Pressed back button"
            }

            "press_home" -> {
                adbExecutor.pressHome().getOrThrow()
                "Pressed home button"
            }

            "scroll_down" -> {
                adbExecutor.scrollDown().getOrThrow()
                "Scrolled down"
            }

            "scroll_up" -> {
                adbExecutor.scrollUp().getOrThrow()
                "Scrolled up"
            }

            "input_text" -> {
                val text = args["text"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("input_text requires 'text'")
                adbExecutor.inputText(text).getOrThrow()
                "Input text: '$text'"
            }

            "key_event" -> {
                val keyCode = args["keyCode"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("key_event requires 'keyCode'")
                adbExecutor.sendKeyEvent(keyCode).getOrThrow()
                "Sent key event $keyCode"
            }

            "adb_shell" -> {
                val command = args["command"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("adb_shell requires 'command'")
                Log.d(TAG, "adb_shell: $command")
                val output = adbExecutor.executeCommand(command).getOrThrow()
                "Shell output: ${output.take(500)}"
            }

            "zoom_region" -> {
                val region = args["region"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("zoom_region requires 'region' parameter")
                if (region !in 1..9) throw IllegalArgumentException("region must be 1-9, got $region")

                val screenshot = lastScreenshotBase64
                    ?: throw IllegalStateException("No screenshot available for zoom")

                // Calculate the new zoom rect in real screen coordinates
                val parentRect = currentZoomRect
                val parentWidth: Float
                val parentHeight: Float
                val parentLeft: Float
                val parentTop: Float

                if (parentRect != null) {
                    parentWidth = parentRect.width()
                    parentHeight = parentRect.height()
                    parentLeft = parentRect.left
                    parentTop = parentRect.top
                } else {
                    val bytes = Base64.decode(screenshot, Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    parentWidth = bmp.width / currentScreenshotScale
                    parentHeight = bmp.height / currentScreenshotScale
                    bmp.recycle()
                    parentLeft = 0f
                    parentTop = 0f
                }

                val col = (region - 1) % 3
                val row = (region - 1) / 3
                val cellW = parentWidth / 3f
                val cellH = parentHeight / 3f
                val newLeft = parentLeft + col * cellW
                val newTop = parentTop + row * cellH
                currentZoomRect = android.graphics.RectF(newLeft, newTop, newLeft + cellW, newTop + cellH)

                // Crop the zone from the screenshot and overlay grid
                val cropped = cropZone(screenshot, region)
                val gridImage = drawZoneGrid(cropped)

                // Update lastScreenshotBase64 to the cropped version for further zoom
                lastScreenshotBase64 = cropped

                addLog(DebugLogEntry(
                    type = DebugLogType.INFO,
                    title = "放大区域 $region",
                    content = "放大区域 $region, 新视口: (${newLeft.toInt()},${newTop.toInt()})-(${(newLeft+cellW).toInt()},${(newTop+cellH).toInt()})",
                    imageBase64 = gridImage
                ))

                "已放大区域 $region，当前视口: (${newLeft.toInt()},${newTop.toInt()})-(${(newLeft+cellW).toInt()},${(newTop+cellH).toInt()})。请查看放大后的截图，选择子区域继续放大或点击。"
            }

            "tap_region" -> {
                val region = args["region"]?.jsonPrimitive?.int
                    ?: throw IllegalArgumentException("tap_region requires 'region' parameter")
                if (region !in 1..9) throw IllegalArgumentException("region must be 1-9, got $region")

                val (x, y) = getZoneCenterCoords(region)
                Log.d(TAG, "tap_region $region -> ($x, $y), zoomRect=$currentZoomRect")

                adbExecutor.tap(x, y).getOrThrow()

                // Annotate
                lastScreenshotBase64?.let { screenshot ->
                    val label = "tap_region $region ($x,$y)"
                    val bytes = Base64.decode(screenshot, Base64.NO_WRAP)
                    val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val scrCol = (region - 1) % 3
                    val scrRow = (region - 1) / 3
                    val scrX = (bmp.width / 3 * scrCol + bmp.width / 6)
                    val scrY = (bmp.height / 3 * scrRow + bmp.height / 6)
                    bmp.recycle()
                    val annotated = annotateScreenshotWithTap(screenshot, scrX, scrY, label)
                    addLog(DebugLogEntry(
                        type = DebugLogType.INFO,
                        title = "点击区域 $region",
                        content = "tap_region $region 实际屏幕坐标($x,$y)",
                        imageBase64 = annotated
                    ))
                }

                // Reset zoom state after tap
                currentZoomRect = null

                "Tapped region $region at screen coordinates ($x, $y)"
            }

            "wait" -> {
                val ms = (args["duration"]?.jsonPrimitive?.intOrNull ?: WAIT_DEFAULT_DURATION_MS)
                    .coerceIn(WAIT_MIN_DURATION_MS, WAIT_MAX_DURATION_MS)
                delay(ms.toLong())
                "Waited ${ms}ms"
            }

            "complete" -> {
                val message = args["message"]?.jsonPrimitive?.content ?: "任务完成"
                "Task completed: $message"
            }

            "load_skills" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("load_skills requires 'package_name'")
                val store = skillStore
                    ?: return "技巧系统不可用"
                val skill = store.getSkill(pkg)
                if (skill != null && skill.tips.isNotEmpty()) {
                    buildString {
                        appendLine("已加载 ${skill.appName} (${skill.packageName}) 的操作技巧:")
                        skill.tips.forEachIndexed { i, tip ->
                            appendLine("${i + 1}. $tip")
                        }
                        appendLine("最后更新: ${formatTimestamp(skill.lastUpdated)}")
                    }
                } else {
                    "没有找到 $pkg 的操作技巧。这是第一次操作该App。"
                }
            }

            "save_skill" -> {
                val pkg = args["package_name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("save_skill requires 'package_name'")
                val appName = args["app_name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("save_skill requires 'app_name'")
                val tip = args["tip"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("save_skill requires 'tip'")
                val store = skillStore
                    ?: return "技巧系统不可用"
                store.addTip(pkg, appName, tip)
                "已保存操作技巧: $tip"
            }

            "detect_current_app" -> {
                val output = adbExecutor.executeCommand(
                    "dumpsys activity activities | grep mResumedActivity"
                ).getOrThrow()
                val pkgRegex = Regex("""(\w+(?:\.\w+)+)/""")
                val match = pkgRegex.find(output)
                val pkg = match?.groupValues?.get(1) ?: "unknown"
                "当前前台应用: $pkg\n原始输出: ${output.trim()}"
            }

            else -> throw IllegalArgumentException("Unknown tool: ${toolCall.functionName}")
        }
    }

    /**
     * Build a tool result message for the conversation history.
     */
    private fun buildToolResultMessage(toolCallId: String, content: String): JsonObject {
        return buildJsonObject {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        }
    }

    private fun buildTextOnlyUserMessage(text: String): JsonObject {
        return buildJsonObject {
            put("role", "user")
            put("content", text)
        }
    }

    /**
     * Build a user message with an image attachment.
     */
    private fun buildUserMessageWithImage(
        text: String,
        imageBase64: String,
        detail: String = MODEL_IMAGE_DETAIL_DEFAULT
    ): JsonObject {
        val modelImage = prepareImageForModel(imageBase64, detail)
        return buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:${modelImage.mimeType};base64,${modelImage.base64}")
                        put("detail", detail)
                    })
                })
            })
        }
    }

    /**
     * Strip images from all previous user messages to save tokens.
     * Replaces image_url content arrays with just the text portion.
     */
    private fun stripImagesFromHistory(messageHistory: MutableList<JsonObject>) {
        for (i in messageHistory.indices) {
            val msg = messageHistory[i]
            val role = (msg["role"] as? JsonPrimitive)?.content
            if (role == "user") {
                val content = msg["content"]
                if (content is JsonArray) {
                    val textPart = content.filterIsInstance<JsonObject>()
                        .firstOrNull { (it["type"] as? JsonPrimitive)?.content == "text" }
                    val text = (textPart?.get("text") as? JsonPrimitive)?.content ?: ""
                    messageHistory[i] = buildJsonObject {
                        put("role", "user")
                        put("content", text)
                    }
                }
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _overlayVisible.value = true
        val current = _agentState.value
        if (!current.isRunning) {
            val now = System.currentTimeMillis()
            _agentState.value = current.copy(
                statusMessage = "已取消",
                lastAction = "用户取消任务",
                phaseStartedAtMs = now,
                lastProgressAtMs = now
            )
        }
    }

    fun setCurrentJob(job: Job) {
        currentJob = job
    }

    private fun resetCaptureStateForNewTask() {
        currentElementMap = emptyMap()
        currentScreenshotScale = 1.0f
        lastScreenshotBase64 = null
        currentZoomRect = null
        uiDumpCooldownRemaining = 0
        uiDumpFailureStreak = 0
    }

    private data class PlannedToolExecution(
        val toolCall: ToolCall,
        val shouldExecute: Boolean,
        val skipReason: String = ""
    )

    private fun planToolExecution(toolCalls: List<ToolCall>): List<PlannedToolExecution> {
        if (toolCalls.isEmpty()) return emptyList()

        val firstZoomToolId = toolCalls.firstOrNull { it.functionName == "zoom_region" }?.id
        val planned = mutableListOf<PlannedToolExecution>()
        var executedCount = 0
        var seenComplete = false

        for (toolCall in toolCalls) {
            val skipReason = when {
                firstZoomToolId != null && toolCall.id != firstZoomToolId ->
                    "zoom_region 需要单独执行，放大后必须先观察新截图"

                seenComplete ->
                    "complete 必须是本轮最后一个工具"

                executedCount >= MAX_TOOL_CALLS_PER_STEP ->
                    "单轮最多连续执行 $MAX_TOOL_CALLS_PER_STEP 个工具，请根据最新结果继续规划"

                else -> ""
            }

            if (skipReason.isNotBlank()) {
                planned += PlannedToolExecution(
                    toolCall = toolCall,
                    shouldExecute = false,
                    skipReason = skipReason
                )
                continue
            }

            planned += PlannedToolExecution(toolCall = toolCall, shouldExecute = true)
            executedCount++
            if (toolCall.functionName == "complete") {
                seenComplete = true
            }
        }

        return planned
    }

    private fun shouldCaptureAfterToolCalls(toolCalls: List<ToolCall>): Boolean {
        return toolCalls.any { toolCall ->
            when (toolCall.functionName) {
                "zoom_region",
                "load_skills",
                "save_skill",
                "detect_current_app",
                "complete" -> false

                else -> true
            }
        }
    }

    private fun registerUiDumpFailure(reason: String) {
        uiDumpFailureStreak++
        if (uiDumpFailureStreak < UI_DUMP_FAILURES_BEFORE_COOLDOWN) return

        uiDumpFailureStreak = 0
        uiDumpCooldownRemaining = UI_DUMP_COOLDOWN_CAPTURES
        addLog(
            DebugLogEntry(
                type = DebugLogType.INFO,
                title = "控件树降级",
                content = "控件树连续抓取失败，接下来 $UI_DUMP_COOLDOWN_CAPTURES 次截图将跳过界面树抓取。最近错误: $reason"
            )
        )
    }

    private data class ModelImagePayload(
        val base64: String,
        val mimeType: String
    )

    private fun prepareImageForModel(imageBase64: String, detail: String): ModelImagePayload {
        val bytes = runCatching { Base64.decode(imageBase64, Base64.NO_WRAP) }.getOrNull()
            ?: return ModelImagePayload(imageBase64, "image/png")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return ModelImagePayload(imageBase64, "image/png")
        val quality = if (detail == MODEL_IMAGE_DETAIL_ZOOM) {
            MODEL_ZOOM_IMAGE_JPEG_QUALITY
        } else {
            MODEL_IMAGE_JPEG_QUALITY
        }

        return try {
            ModelImagePayload(
                base64 = bitmapToBase64(
                    bitmap = bitmap,
                    format = Bitmap.CompressFormat.JPEG,
                    quality = quality
                ),
                mimeType = "image/jpeg"
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Draw a tap marker on the screenshot at the given position (in screenshot pixel coordinates).
     * Returns the annotated image as a base64 string.
     */
    private fun annotateScreenshotWithTap(
        imageBase64: String,
        tapX: Int,
        tapY: Int,
        label: String
    ): String {
        val bytes = Base64.decode(imageBase64, Base64.NO_WRAP)
        val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val annotated = Bitmap.createBitmap(original.width, original.height, original.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(annotated)
        canvas.drawBitmap(original, 0f, 0f, null)
        original.recycle()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Red circle outline
        paint.color = Color.RED
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(tapX.toFloat(), tapY.toFloat(), 25f, paint)

        // Crosshair lines
        canvas.drawLine((tapX - 40).toFloat(), tapY.toFloat(), (tapX + 40).toFloat(), tapY.toFloat(), paint)
        canvas.drawLine(tapX.toFloat(), (tapY - 40).toFloat(), tapX.toFloat(), (tapY + 40).toFloat(), paint)

        // Center dot (filled)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(tapX.toFloat(), tapY.toFloat(), 5f, paint)

        // Text label with semi-transparent black background
        paint.textSize = 28f
        paint.color = Color.WHITE
        val textBounds = Rect()
        paint.getTextBounds(label, 0, label.length, textBounds)
        val textX = (tapX + 30).toFloat()
        val textY = (tapY - 30).toFloat()

        // Background rect
        val bgPaint = Paint()
        bgPaint.color = Color.argb(160, 0, 0, 0)
        bgPaint.style = Paint.Style.FILL
        canvas.drawRect(
            textX - 4f,
            textY + textBounds.top - 4f,
            textX + textBounds.width() + 4f,
            textY + textBounds.bottom + 4f,
            bgPaint
        )

        // Label text
        canvas.drawText(label, textX, textY, paint)

        val result = bitmapToBase64(annotated)
        annotated.recycle()
        return result
    }

    /**
     * Draw a 3x3 grid overlay with zone numbers on the screenshot.
     * Returns the annotated image as base64.
     */
    private fun drawZoneGrid(imageBase64: String): String {
        val bytes = Base64.decode(imageBase64, Base64.NO_WRAP)
        val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val annotated = Bitmap.createBitmap(original.width, original.height, original.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(annotated)
        canvas.drawBitmap(original, 0f, 0f, null)
        original.recycle()

        val w = annotated.width.toFloat()
        val h = annotated.height.toFloat()
        val cellW = w / 3f
        val cellH = h / 3f

        // Draw grid lines
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(128, 0, 255, 0) // semi-transparent green
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        // Vertical lines
        canvas.drawLine(cellW, 0f, cellW, h, linePaint)
        canvas.drawLine(cellW * 2, 0f, cellW * 2, h, linePaint)
        // Horizontal lines
        canvas.drawLine(0f, cellH, w, cellH, linePaint)
        canvas.drawLine(0f, cellH * 2, w, cellH * 2, linePaint)

        // Draw zone numbers
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 255, 0)
            textSize = 48f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val bgPaint = Paint().apply {
            color = Color.argb(100, 0, 0, 0)
            style = Paint.Style.FILL
        }

        for (row in 0..2) {
            for (col in 0..2) {
                val zone = row * 3 + col + 1
                val cx = cellW * col + cellW / 2
                val cy = cellH * row + cellH / 2
                // Background circle behind number
                canvas.drawCircle(cx, cy, 30f, bgPaint)
                // Zone number
                canvas.drawText(zone.toString(), cx, cy + 16f, textPaint)
            }
        }

        val result = bitmapToBase64(annotated)
        annotated.recycle()
        return result
    }

    /**
     * Crop a zone (1-9) from the given base64 image and return the cropped image as base64.
     */
    private fun cropZone(imageBase64: String, zone: Int): String {
        val bytes = Base64.decode(imageBase64, Base64.NO_WRAP)
        val original = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        val col = (zone - 1) % 3
        val row = (zone - 1) / 3
        val cellW = original.width / 3
        val cellH = original.height / 3
        val x = col * cellW
        val y = row * cellH

        val cropped = Bitmap.createBitmap(original, x, y, cellW, cellH)
        original.recycle()

        val result = bitmapToBase64(cropped)
        cropped.recycle()
        return result
    }

    /**
     * Calculate the center point of a zone (1-9) within the current zoom viewport.
     * Returns (x, y) in REAL screen coordinates suitable for adbExecutor.tap().
     */
    private fun getZoneCenterCoords(zone: Int): Pair<Int, Int> {
        val rect = currentZoomRect
        val screenWidth: Float
        val screenHeight: Float
        val offsetX: Float
        val offsetY: Float

        if (rect != null) {
            screenWidth = rect.width()
            screenHeight = rect.height()
            offsetX = rect.left
            offsetY = rect.top
        } else {
            val lastBase64 = lastScreenshotBase64
            if (lastBase64 != null) {
                val bytes = Base64.decode(lastBase64, Base64.NO_WRAP)
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                screenWidth = (bmp.width / currentScreenshotScale)
                screenHeight = (bmp.height / currentScreenshotScale)
                bmp.recycle()
            } else {
                screenWidth = 1080f
                screenHeight = 2400f
            }
            offsetX = 0f
            offsetY = 0f
        }

        val col = (zone - 1) % 3
        val row = (zone - 1) / 3
        val cellW = screenWidth / 3f
        val cellH = screenHeight / 3f
        val centerX = (offsetX + cellW * col + cellW / 2f).toInt()
        val centerY = (offsetY + cellH * row + cellH / 2f).toInt()

        return Pair(centerX, centerY)
    }

    private fun bitmapToBase64(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
        quality: Int = 100
    ): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(format, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    /**
     * Result of simplifying the UI tree.
     * @param text Compact text representation with numbered interactive elements
     * @param elementMap Maps element number (#N) to center (x, y) screen coordinates
     */
    data class UiTreeResult(
        val text: String,
        val elementMap: Map<Int, Pair<Int, Int>>
    )

    /**
     * Simplify raw uiautomator XML into a compact text representation.
     * Interactive elements (clickable or focusable) are numbered sequentially (#1, #2, ...)
     * and their center coordinates are stored in the elementMap for tap_element/input_element.
     * Non-interactive elements with text are included without numbers for context.
     * Raw bounds are omitted from the text output to save tokens.
     */
    private fun simplifyUiTree(xml: String): UiTreeResult? {
        try {
            val lines = mutableListOf<String>()
            val elementMap = mutableMapOf<Int, Pair<Int, Int>>()
            var elementNumber = 0
            val boundsRegex = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")

            val nodeRegex = Regex("""<node\s+([^>]+?)/?>\s*""")
            val attrRegex = Regex("""(\w[\w-]*)="([^"]*?)"""")

            for (nodeMatch in nodeRegex.findAll(xml)) {
                val attrs = mutableMapOf<String, String>()
                for (attrMatch in attrRegex.findAll(nodeMatch.groupValues[1])) {
                    attrs[attrMatch.groupValues[1]] = attrMatch.groupValues[2]
                }

                val text = attrs["text"] ?: ""
                val contentDesc = attrs["content-desc"] ?: ""
                val resourceId = attrs["resource-id"] ?: ""
                val className = attrs["class"] ?: ""
                val bounds = attrs["bounds"] ?: ""
                val clickable = attrs["clickable"] == "true"
                val focusable = attrs["focusable"] == "true"
                val enabled = attrs["enabled"] != "false"
                val visible = attrs["visible-to-user"] != "false"

                if (!visible) continue
                if (text.isEmpty() && contentDesc.isEmpty() && resourceId.isEmpty() && !clickable) continue

                val isInteractive = clickable || focusable

                val shortClass = className.substringAfterLast(".")
                val parts = mutableListOf<String>()

                if (isInteractive) {
                    elementNumber++
                    parts.add("#$elementNumber")

                    val boundsMatch = boundsRegex.find(bounds)
                    if (boundsMatch != null) {
                        val left = boundsMatch.groupValues[1].toInt()
                        val top = boundsMatch.groupValues[2].toInt()
                        val right = boundsMatch.groupValues[3].toInt()
                        val bottom = boundsMatch.groupValues[4].toInt()
                        val centerX = (left + right) / 2
                        val centerY = (top + bottom) / 2
                        elementMap[elementNumber] = Pair(centerX, centerY)
                    }
                }

                parts.add("[$shortClass]")
                if (text.isNotEmpty()) parts.add("\"${text.take(50)}\"")
                if (contentDesc.isNotEmpty() && contentDesc != text) parts.add("desc=\"${contentDesc.take(40)}\"")
                if (resourceId.isNotEmpty()) parts.add("id=${resourceId.substringAfterLast("/")}")

                val flags = mutableListOf<String>()
                if (clickable) flags.add("clickable")
                if (focusable) flags.add("focusable")
                if (!enabled) flags.add("disabled")
                if (flags.isNotEmpty()) parts.add(flags.joinToString(","))

                lines.add(parts.joinToString(" "))
            }

            if (lines.isEmpty()) return null
            val resultText = if (lines.size > 150) {
                lines.take(150).joinToString("\n") + "\n... (${lines.size - 150} more nodes)"
            } else {
                lines.joinToString("\n")
            }
            return UiTreeResult(text = resultText, elementMap = elementMap)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to simplify UI tree: ${e.message}")
            return null
        }
    }
}
