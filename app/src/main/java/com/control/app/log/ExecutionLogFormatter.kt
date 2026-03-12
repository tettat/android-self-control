package com.control.app.log

import android.content.Context
import android.os.Build
import com.control.app.agent.AgentState
import com.control.app.agent.DebugLogEntry
import com.control.app.agent.Session
import com.control.app.agent.StepTiming
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExecutionLogFormatter {

    data class StepTimingPresentation(
        val category: String,
        val title: String,
        val subtitle: String = ""
    )

    fun buildExportJson(
        context: Context,
        agentState: AgentState,
        entries: List<DebugLogEntry>,
        sessions: List<Session> = emptyList(),
        serverPort: Int? = null,
        accessUrls: List<String> = emptyList(),
        selectedSession: Session? = null,
        includeImages: Boolean = false,
        requestedLimit: Int = entries.size
    ): JSONObject {
        val limitedEntries = entries.takeLast(requestedLimit.coerceAtLeast(0))

        return JSONObject().apply {
            put("exportTime", buildExportTime())
            put("deviceInfo", buildDeviceInfo())
            put("appVersion", buildAppVersion(context))
            put("agentState", buildAgentStateJson(agentState))
            put("entryCount", entries.size)
            put("returnedEntryCount", limitedEntries.size)
            put("sessions", buildSessionsJson(sessions))
            put("entries", buildEntriesJson(limitedEntries, includeImages))

            if (selectedSession != null) {
                put("selectedSession", buildSessionJson(selectedSession))
            }

            if (serverPort != null && serverPort > 0) {
                put(
                    "logServer",
                    JSONObject().apply {
                        put("port", serverPort)
                        put("accessUrls", JSONArray(accessUrls))
                    }
                )
            }
        }
    }

    fun buildAgentStateJson(agentState: AgentState): JSONObject =
        JSONObject().apply {
            put("isRunning", agentState.isRunning)
            put("currentRound", agentState.currentRound)
            put("maxRounds", agentState.maxRounds)
            put("statusMessage", agentState.statusMessage)
            put("isListening", agentState.isListening)
            put("lastThinking", agentState.lastThinking)
            put("activeTool", agentState.activeTool)
            put("lastAction", agentState.lastAction)
            put("taskStartedAtMs", agentState.taskStartedAtMs)
            put("taskStartedAt", formatTimestamp(agentState.taskStartedAtMs))
            put("phaseStartedAtMs", agentState.phaseStartedAtMs)
            put("phaseStartedAt", formatTimestamp(agentState.phaseStartedAtMs))
            put("lastProgressAtMs", agentState.lastProgressAtMs)
            put("lastProgressAt", formatTimestamp(agentState.lastProgressAtMs))
            put("stepTimings", buildStepTimingsJson(agentState.stepTimings))
        }

    fun buildSessionsJson(sessions: List<Session>): JSONArray =
        JSONArray().apply {
            sessions.forEach { put(buildSessionJson(it)) }
        }

    fun buildEntriesJson(entries: List<DebugLogEntry>, includeImages: Boolean = false): JSONArray =
        JSONArray().apply {
            entries.forEach { put(buildEntryJson(it, includeImages)) }
        }

    fun buildSessionJson(session: Session): JSONObject =
        JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("createdAt", session.createdAt)
            put("createdAtFormatted", formatTimestamp(session.createdAt))
            put("lastActiveAt", session.lastActiveAt)
            put("lastActiveAtFormatted", formatTimestamp(session.lastActiveAt))
            put("isActive", session.isActive)
            put("result", session.result ?: JSONObject.NULL)
            put("messageCount", session.messageHistory.size)
            put("entryCount", session.debugEntries.size)
        }

    fun buildEntryJson(entry: DebugLogEntry, includeImages: Boolean = false): JSONObject =
        JSONObject().apply {
            put("id", entry.id)
            put("timestamp", entry.timestamp)
            put("timestampFormatted", formatTimestamp(entry.timestamp))
            put("type", entry.type.name)
            put("title", entry.title)
            put("content", entry.content)
            put("hasImage", entry.images.isNotEmpty())
            put("imageCount", entry.images.size)
            if (entry.images.isNotEmpty()) {
                put("imageSizeKB", entry.images.sumOf { it.base64.length * 3 / 4 / 1024 })
                put("imageMimeType", entry.images.firstOrNull()?.mimeType ?: entry.imageMimeType ?: "image/png")
                put(
                    "images",
                    JSONArray().apply {
                        entry.images.forEach { image ->
                            put(
                                JSONObject().apply {
                                    put("mimeType", image.mimeType ?: "image/png")
                                    put("imageSizeKB", image.base64.length * 3 / 4 / 1024)
                                    if (includeImages) {
                                        put("imageBase64", image.base64)
                                    }
                                }
                            )
                        }
                    }
                )
                if (includeImages) {
                    put("imageBase64", entry.images.firstOrNull()?.base64 ?: entry.imageBase64)
                }
            }
        }

    fun buildStepTimingsJson(stepTimings: List<StepTiming>): JSONArray =
        JSONArray().apply {
            stepTimings.forEachIndexed { index, step ->
                put(
                    JSONObject().apply {
                        put("index", index + 1)
                        put("label", step.label)
                        put("tool", step.tool)
                        val presentation = describeStepTiming(step)
                        put("category", presentation.category)
                        put("title", presentation.title)
                        put("subtitle", presentation.subtitle)
                        put("startedAtMs", step.startedAtMs)
                        put("startedAt", formatTimestamp(step.startedAtMs))
                        put("finishedAtMs", step.finishedAtMs)
                        put("finishedAt", formatTimestamp(step.finishedAtMs))
                        put("durationMs", step.durationMs)
                        put("duration", formatDurationMs(step.durationMs))
                    }
                )
            }
        }

    fun buildExportTime(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }

    fun formatDurationMs(durationMs: Long): String {
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

    fun describeStepTiming(step: StepTiming): StepTimingPresentation {
        val tool = step.tool.lowercase(Locale.US)
        val label = step.label

        return when {
            "截图" in label -> StepTimingPresentation(
                category = "截图分析",
                title = "截图并分析当前界面",
                subtitle = label
            )
            "调用ai" in label.lowercase(Locale.US) -> StepTimingPresentation(
                category = "AI 规划",
                title = "调用模型生成下一步动作",
                subtitle = label
            )
            tool in setOf("tap", "tap_element", "tap_region") -> StepTimingPresentation(
                category = "界面点击",
                title = "点击界面元素",
                subtitle = tool
            )
            tool in setOf("swipe", "scroll_down", "scroll_up") -> StepTimingPresentation(
                category = "界面滚动",
                title = "滑动或滚动页面",
                subtitle = tool
            )
            tool in setOf("input_text", "input_element") -> StepTimingPresentation(
                category = "文本输入",
                title = "输入文本内容",
                subtitle = tool
            )
            tool == "launch_app" -> StepTimingPresentation(
                category = "应用切换",
                title = "启动目标应用",
                subtitle = tool
            )
            tool in setOf("press_back", "press_home", "key_event") -> StepTimingPresentation(
                category = "系统按键",
                title = "发送系统按键",
                subtitle = tool
            )
            tool == "adb_shell" -> StepTimingPresentation(
                category = "系统命令",
                title = "执行 ADB Shell 命令",
                subtitle = tool
            )
            tool == "zoom_region" -> StepTimingPresentation(
                category = "区域聚焦",
                title = "放大目标区域继续分析",
                subtitle = tool
            )
            tool == "wait" -> StepTimingPresentation(
                category = "等待",
                title = "等待界面或状态变化",
                subtitle = tool
            )
            tool == "complete" || "任务完成" in label || "任务已取消" in label || "已取消" in label -> StepTimingPresentation(
                category = "任务收尾",
                title = label,
                subtitle = if (tool.isNotBlank()) tool else ""
            )
            "正在执行" in label -> StepTimingPresentation(
                category = "执行动作",
                title = label.substringAfter("正在执行 ").ifBlank { label },
                subtitle = if (tool.isNotBlank()) tool else ""
            )
            else -> StepTimingPresentation(
                category = "执行步骤",
                title = label,
                subtitle = if (tool.isNotBlank()) tool else ""
            )
        }
    }

    fun formatStepTimingLine(index: Int, step: StepTiming): String {
        val presentation = describeStepTiming(step)
        return buildString {
            append(index + 1)
            append(". [")
            append(presentation.category)
            append("] ")
            append(presentation.title)
            append(" | 耗时 ")
            append(formatDurationMs(step.durationMs))
            if (presentation.subtitle.isNotBlank()) {
                append(" | ")
                append(presentation.subtitle)
            }
        }
    }

    fun buildDeviceInfo(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"

    fun buildAppVersion(context: Context): String =
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
}
