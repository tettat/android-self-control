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

data class DebugLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: DebugLogType,
    val title: String,
    val content: String,
    val imageBase64: String? = null
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

data class AgentState(
    val isRunning: Boolean = false,
    val currentRound: Int = 0,
    val maxRounds: Int = 50,
    val statusMessage: String = "就绪",
    val isListening: Boolean = false,
    val lastThinking: String = ""
)
