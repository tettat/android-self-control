package com.control.app.agent

import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Represents a single tool call from the AI response.
 */
data class ToolCall(
    val id: String,
    val functionName: String,
    val arguments: JsonObject
)

/**
 * Result of an AI chat completion with tool support.
 */
sealed class AIChatResult {
    /** The AI responded with plain text (no tool calls) -- typically means task is done. */
    data class TextResponse(
        val content: String,
        val rawMessage: JsonObject
    ) : AIChatResult()

    /** The AI requested one or more tool calls to be executed. */
    data class ToolCallResponse(
        val toolCalls: List<ToolCall>,
        val rawMessage: JsonObject
    ) : AIChatResult()
}

// --- Debug / state models (unchanged) ---

data class DebugLogImage(
    val base64: String,
    val mimeType: String? = null
)

data class DebugLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: DebugLogType,
    val title: String,
    val content: String,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    val images: List<DebugLogImage> = imageBase64?.let {
        listOf(DebugLogImage(base64 = it, mimeType = imageMimeType))
    } ?: emptyList()
)

enum class DebugLogType {
    VOICE_INPUT,
    SCREENSHOT,
    API_REQUEST,
    API_RESPONSE,
    ACTION_EXECUTED,
    ERROR,
    INFO
}

data class TimingBreakdown(
    val screenshotMs: Long? = null,
    val uiDumpMs: Long? = null,
    val encodeMs: Long? = null,
    val uploadMs: Long? = null,
    val modelMs: Long? = null,
    val toolMs: Long? = null
) {
    fun merge(other: TimingBreakdown): TimingBreakdown = TimingBreakdown(
        screenshotMs = other.screenshotMs ?: screenshotMs,
        uiDumpMs = other.uiDumpMs ?: uiDumpMs,
        encodeMs = other.encodeMs ?: encodeMs,
        uploadMs = other.uploadMs ?: uploadMs,
        modelMs = other.modelMs ?: modelMs,
        toolMs = other.toolMs ?: toolMs
    )

    fun isEmpty(): Boolean =
        screenshotMs == null &&
            uiDumpMs == null &&
            encodeMs == null &&
            uploadMs == null &&
            modelMs == null &&
            toolMs == null
}

data class StepTiming(
    val label: String,
    val tool: String = "",
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val durationMs: Long,
    val breakdown: TimingBreakdown = TimingBreakdown()
)

data class AgentState(
    val isRunning: Boolean = false,
    val currentRound: Int = 0,
    val maxRounds: Int = 50,
    val statusMessage: String = "就绪",
    val isListening: Boolean = false,
    val lastThinking: String = "",
    val activeTool: String = "",
    val lastAction: String = "",
    val taskStartedAtMs: Long = 0L,
    val phaseStartedAtMs: Long = 0L,
    val lastProgressAtMs: Long = 0L,
    val currentPhaseTiming: TimingBreakdown = TimingBreakdown(),
    val stepTimings: List<StepTiming> = emptyList()
)
