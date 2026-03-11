package com.control.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.1,
    @SerialName("max_tokens")
    val maxTokens: Int = 4096
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: JsonElement
)

@Serializable
data class ChatContentText(
    val type: String = "text",
    val text: String
)

@Serializable
data class ChatContentImageUrl(
    val type: String = "image_url",
    @SerialName("image_url")
    val imageUrl: ImageUrlDetail
)

@Serializable
data class ImageUrlDetail(
    val url: String,
    val detail: String = "high"
)

@Serializable
data class ChatResponse(
    val id: String = "",
    val `object`: String = "",
    val created: Long = 0,
    val model: String = "",
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatResponseMessage? = null,
    @SerialName("finish_reason")
    val finishReason: String? = null
)

@Serializable
data class ChatResponseMessage(
    val role: String = "",
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ResponseToolCall>? = null
)

@Serializable
data class ResponseToolCall(
    val id: String = "",
    val type: String = "function",
    val function: ResponseToolCallFunction = ResponseToolCallFunction()
)

@Serializable
data class ResponseToolCallFunction(
    val name: String = "",
    val arguments: String = ""
)

@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerialName("completion_tokens")
    val completionTokens: Int = 0,
    @SerialName("total_tokens")
    val totalTokens: Int = 0
)

@Serializable
data class ApiErrorResponse(
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val message: String = "",
    val type: String = "",
    val code: String? = null
)
