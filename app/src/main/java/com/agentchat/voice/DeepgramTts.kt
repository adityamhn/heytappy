package com.agentchat.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.agentchat.agent.AgentLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Speaks text through Deepgram Aura (POST /v1/speak, linear16 @ 24 kHz) and
 * plays the raw PCM as it streams in via AudioTrack — no container parsing, and
 * first audio is audible before the full response has downloaded.
 */
class DeepgramTts(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Synthesizes and plays [text]; suspends until playback finishes or the caller is cancelled. */
    suspend fun speak(text: String) = withContext(Dispatchers.IO) {
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
                        "Deepgram rejected the API key. Open setup and paste a valid key."
                    } else {
                        "Text-to-speech failed (HTTP ${response.code})."
                    },
                )
            }

            val track = buildTrack()
            try {
                track.play()
                val stream = response.body?.byteStream() ?: return@use
                val buffer = ByteArray(CHUNK_BYTES)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    var offset = 0
                    while (offset < read) {
                        val written = track.write(buffer, offset, read - offset)
                        if (written < 0) return@use
                        offset += written
                    }
                }
                // Let the buffered tail drain before releasing the track.
                track.stop()
                awaitDrain(track)
            } finally {
                runCatching { track.release() }
            }
        }
    }

    private suspend fun awaitDrain(track: AudioTrack) {
        val total = track.playbackHeadPosition
        var last = -1
        // Head position stops advancing once the buffer is empty after stop().
        repeat(50) {
            val head = track.playbackHeadPosition
            if (head == last || head >= total) return
            last = head
            kotlinx.coroutines.delay(60)
        }
    }

    private fun buildTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val CHUNK_BYTES = 8_192
        private const val MAX_CHARS = 1_900 // Aura hard limit is 2000 chars/request

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val SPEAK_URL =
            "https://api.deepgram.com/v1/speak?model=aura-2-thalia-en&encoding=linear16&sample_rate=24000"
    }
}
