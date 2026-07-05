package com.agentchat.agent.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin OkHttp wrapper around the Anthropic Messages API. Handles auth headers,
 * (de)serialization, one automatic retry for transient failures, and turns
 * everything else into a typed [AnthropicResult] so the loop never has to guess
 * from a raw exception.
 */
class AnthropicClient(
    private val apiKey: String,
    private val model: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        system: List<ContentBlock>,
        tools: List<ToolDef>,
        messages: List<ApiMessage>,
        maxTokens: Int = 1024,
    ): AnthropicResult = withContext(Dispatchers.IO) {
        val request = MessagesRequest(
            model = model,
            maxTokens = maxTokens,
            system = system,
            tools = tools,
            messages = messages,
        )
        val body = json.encodeToString(MessagesRequest.serializer(), request)

        var attempt = 0
        while (true) {
            attempt++
            val result = executeOnce(body)
            if (result is AnthropicResult.Error && result.retryable && attempt <= MAX_ATTEMPTS) {
                Log.w(TAG, "Transient failure (${result.message}); retry $attempt")
                delay(BACKOFF_MS * attempt)
                continue
            }
            return@withContext result
        }
        @Suppress("UNREACHABLE_CODE")
        AnthropicResult.Error("Unreachable", retryable = false)
    }

    private fun executeOnce(body: String): AnthropicResult {
        val httpRequest = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            http.newCall(httpRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val parsed = json.decodeFromString(MessagesResponse.serializer(), raw)
                        parsed.usage?.let {
                            Log.d(
                                TAG,
                                "usage in=${it.inputTokens} out=${it.outputTokens} " +
                                    "cacheWrite=${it.cacheCreationInputTokens} " +
                                    "cacheRead=${it.cacheReadInputTokens}",
                            )
                        }
                        AnthropicResult.Success(parsed)
                    }

                    response.code == 401 -> AnthropicResult.Error(
                        "Your Anthropic API key was rejected. Open setup (top-right) and paste a valid key.",
                        retryable = false,
                    )

                    response.code == 429 -> AnthropicResult.Error(
                        "Anthropic rate limit hit. Try again in a moment.",
                        retryable = true,
                    )

                    response.code in 500..599 -> AnthropicResult.Error(
                        apiMessage(raw) ?: "Anthropic service error (${response.code}).",
                        retryable = true,
                    )

                    else -> AnthropicResult.Error(
                        apiMessage(raw) ?: "Anthropic request failed (${response.code}).",
                        retryable = false,
                    )
                }
            }
        } catch (e: IOException) {
            AnthropicResult.Error(
                "Network error reaching Anthropic: ${e.message ?: "unknown"}.",
                retryable = true,
            )
        } catch (e: Exception) {
            AnthropicResult.Error(
                "Couldn't parse Anthropic response: ${e.message ?: e.javaClass.simpleName}.",
                retryable = false,
            )
        }
    }

    private fun apiMessage(raw: String): String? =
        runCatching {
            json.decodeFromString(ApiErrorEnvelope.serializer(), raw).error?.message
        }.getOrNull()

    companion object {
        private const val TAG = "AnthropicClient"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val MAX_ATTEMPTS = 2
        private const val BACKOFF_MS = 1_200L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_MODEL = "claude-sonnet-4-5"
    }
}

sealed interface AnthropicResult {
    data class Success(val response: MessagesResponse) : AnthropicResult
    data class Error(val message: String, val retryable: Boolean) : AnthropicResult
}
