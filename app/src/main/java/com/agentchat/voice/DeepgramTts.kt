package com.agentchat.voice

import com.agentchat.agent.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Speaks text through Deepgram Aura (POST /v1/speak, linear16 @ 24 kHz) and
 * plays the raw PCM as it streams in via [PcmStreamPlayer] — first audio is
 * audible before the full response has downloaded.
 */
class DeepgramTts(private val apiKey: String) : TtsClient {

    // Derived from the shared warm client so speech playback reuses the same
    // pooled TLS connection the STT websocket keeps alive.
    private val http = VoiceHttp.base.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun speak(text: String) = withContext(Dispatchers.IO) {
        val trimmed = text.trim().take(MAX_CHARS)
        if (trimmed.isEmpty()) return@withContext

        val body = JSONObject().put("text", trimmed).toString()
        val request = Request.Builder()
            .url(SPEAK_URL)
            .addHeader("Authorization", "Token $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string()?.take(200) ?: ""
                AgentLog.log("VOICE_TTS", "speak failed HTTP ${response.code}: $detail")
                throw IllegalStateException(
                    if (response.code == 401) {
                        "Deepgram rejected the API key compiled into this build."
                    } else {
                        "Text-to-speech failed (HTTP ${response.code})."
                    },
                )
            }
            val stream = response.body?.byteStream() ?: return@use
            PcmStreamPlayer.play(stream, SAMPLE_RATE)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val MAX_CHARS = 1_900 // Aura hard limit is 2000 chars/request

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val SPEAK_URL =
            "https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=linear16&sample_rate=24000"
    }
}
