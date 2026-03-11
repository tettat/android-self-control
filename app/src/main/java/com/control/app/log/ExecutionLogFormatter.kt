package com.control.app.log

import android.content.Context
import android.os.Build
import com.control.app.agent.AgentState
import com.control.app.agent.DebugLogEntry
import com.control.app.agent.Session
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExecutionLogFormatter {

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
            put("hasImage", entry.imageBase64 != null)
            if (entry.imageBase64 != null) {
                put("imageSizeKB", entry.imageBase64.length * 3 / 4 / 1024)
                if (includeImages) {
                    put("imageBase64", entry.imageBase64)
                }
            }
        }

    fun buildExportTime(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
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
