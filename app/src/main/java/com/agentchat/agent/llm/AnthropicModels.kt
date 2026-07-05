package com.agentchat.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Data-transfer objects for the Anthropic Messages API
 * (`POST https://api.anthropic.com/v1/messages`).
 *
 * A single flexible [ContentBlock] models every block kind (text, image,
 * tool_use, tool_result) in both requests and responses. Nullable fields are
 * omitted on the wire because the [AnthropicClient] serializer is configured
 * with `explicitNulls = false`.
 */

@Serializable
data class CacheControl(val type: String = "ephemeral")

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

@Serializable
data class ContentBlock(
    val type: String,
    // text
    val text: String? = null,
    // image
    val source: ImageSource? = null,
    // tool_use (assistant) — the model's chosen action
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
    // tool_result (user) — our reply to a tool_use
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: List<ContentBlock>? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    // caching breakpoint
    @SerialName("cache_control") val cacheControl: CacheControl? = null,
) {
    companion object {
        fun text(value: String, cache: Boolean = false): ContentBlock =
            ContentBlock(
                type = "text",
                text = value,
                cacheControl = if (cache) CacheControl() else null,
            )

        fun image(base64: String, mediaType: String = "image/jpeg"): ContentBlock =
            ContentBlock(
                type = "image",
                source = ImageSource(mediaType = mediaType, data = base64),
            )

        fun toolResult(
            toolUseId: String,
            blocks: List<ContentBlock>,
            isError: Boolean = false,
        ): ContentBlock =
            ContentBlock(
                type = "tool_result",
                toolUseId = toolUseId,
                content = blocks,
                isError = if (isError) true else null,
            )
    }
}

@Serializable
data class ToolDef(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
    @SerialName("cache_control") val cacheControl: CacheControl? = null,
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: List<ContentBlock>,
)

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: List<ContentBlock>? = null,
    val tools: List<ToolDef>? = null,
    val messages: List<ApiMessage>,
)

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
)

@Serializable
data class MessagesResponse(
    val id: String? = null,
    val role: String? = null,
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: Usage? = null,
)

@Serializable
data class ApiErrorEnvelope(
    val type: String? = null,
    val error: ApiErrorDetail? = null,
)

@Serializable
data class ApiErrorDetail(
    val type: String? = null,
    val message: String? = null,
)
