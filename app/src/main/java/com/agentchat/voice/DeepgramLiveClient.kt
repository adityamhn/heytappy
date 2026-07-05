package com.agentchat.voice

import com.agentchat.agent.AgentLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * One live-transcription session against Deepgram's streaming API
 * (wss://api.deepgram.com/v1/listen, nova-3, linear16 @ 16 kHz). Mic PCM chunks
 * go up the socket; interim transcripts stream back for live UI, and the call
 * returns the full utterance once Deepgram's endpointing decides the user
 * stopped speaking.
 *
 * The mic is opened and buffered the moment [transcribeUtterance] is called —
 * chunks captured while the websocket is still connecting are queued and
 * flushed on open, so speech from the first instant is never lost.
 */
class DeepgramLiveClient(private val apiKey: String) : SttClient {

    // Derived from the shared warm client so the websocket upgrade reuses a
    // pooled TLS connection to Deepgram instead of a cold handshake.
    private val http = VoiceHttp.base.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // websocket stays open
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribeUtterance(
        scope: CoroutineScope,
        audio: Flow<ByteArray>,
        onInterim: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<String>()
        val finalized = StringBuilder()

        // Start the mic NOW: chunks queue here while the socket handshakes, so
        // words spoken in the first second of the listening turn still count.
        val buffered = Channel<ByteArray>(Channel.UNLIMITED)
        val captureJob = scope.launch(Dispatchers.IO) {
            try {
                audio.collect { chunk -> buffered.send(chunk) }
            } finally {
                buffered.close()
            }
        }
        var sendJob: Job? = null

        val request = Request.Builder()
            .url(LISTEN_URL)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                AgentLog.log("VOICE_STT", "deepgram websocket open")
                sendJob = scope.launch(Dispatchers.IO) {
                    try {
                        for (chunk in buffered) {
                            webSocket.send(chunk.toByteString(0, chunk.size))
                        }
                    } finally {
                        // Mic stopped (utterance done or cancelled): tell Deepgram
                        // to flush and finish instead of waiting for a timeout.
                        webSocket.send("""{"type":"CloseStream"}""")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (root["type"]?.jsonPrimitive?.content) {
                    "Results" -> {
                        val transcript = root["channel"]?.jsonObject
                            ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
                            ?.get("transcript")?.jsonPrimitive?.content
                            .orEmpty()
                        val isFinal = root["is_final"]?.jsonPrimitive?.booleanOrNull == true
                        val speechFinal = root["speech_final"]?.jsonPrimitive?.booleanOrNull == true

                        if (transcript.isNotBlank()) {
                            if (isFinal) {
                                if (finalized.isNotEmpty()) finalized.append(' ')
                                finalized.append(transcript.trim())
                                onInterim(finalized.toString())
                            } else {
                                onInterim(
                                    if (finalized.isEmpty()) transcript
                                    else "$finalized $transcript",
                                )
                            }
                        }
                        // speech_final fires when endpointing detects silence after
                        // speech — the Siri-style "user finished talking" signal.
                        if (speechFinal && finalized.isNotBlank()) {
                            result.complete(finalized.toString())
                        }
                    }

                    "UtteranceEnd" -> {
                        if (finalized.isNotBlank()) result.complete(finalized.toString())
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = response?.let { "HTTP ${it.code}" } ?: t.message ?: "connection failed"
                AgentLog.log("VOICE_STT", "deepgram websocket failure: $detail")
                result.completeExceptionally(
                    IllegalStateException(
                        if (response?.code == 401) {
                            "Deepgram rejected the API key compiled into this build."
                        } else {
                            "Voice connection failed: $detail"
                        },
                    ),
                )
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // Server closed after CloseStream: return whatever we have.
                if (!result.isCompleted) result.complete(finalized.toString())
            }
        }

        val socket = http.newWebSocket(request, listener)
        try {
            result.await()
        } finally {
            // Must run even when the caller was cancelled (listen timeout, user
            // stop) — otherwise the mic stays hot into the next listening turn.
            withContext(NonCancellable) {
                captureJob.cancelAndJoin()
                sendJob?.cancelAndJoin()
                socket.close(1000, "done")
            }
        }
    }

    companion object {
        private val LISTEN_URL =
            "wss://api.deepgram.com/v1/listen" +
                "?model=nova-3" +
                "&encoding=linear16" +
                "&sample_rate=${VoiceRecorder.SAMPLE_RATE}" +
                "&channels=1" +
                "&interim_results=true" +
                "&smart_format=true" +
                "&no_delay=true" +
                "&endpointing=250" +
                "&utterance_end_ms=1000" +
                "&vad_events=true"
    }
}
