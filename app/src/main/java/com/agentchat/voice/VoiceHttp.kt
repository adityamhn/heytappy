package com.agentchat.voice

import com.agentchat.agent.AgentLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * One process-wide OkHttp stack for all Deepgram traffic. STT (websocket) and
 * TTS (streaming POST) both derive their clients from [base] via
 * `newBuilder()`, so they share a single [ConnectionPool] and dispatcher. That
 * means the TLS+TCP connection to api.deepgram.com stays warm between
 * utterances and across sessions — the second and later websocket upgrades
 * reuse a pooled connection instead of paying a fresh handshake.
 */
object VoiceHttp {

    /**
     * Shared base client. Connections are kept alive aggressively (5 per host
     * for 5 minutes) so a running voice session never re-handshakes mid-chat.
     */
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    /**
     * Fires a lightweight request to establish (and pool) a live TLS connection
     * to Deepgram before the user speaks, so the first websocket upgrade of a
     * session is instant. Failures are ignored — the real request will surface
     * any actual connectivity problem.
     */
    fun prewarm(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(PREWARM_URL).head().build()
                base.newCall(request).execute().use { /* connection now pooled */ }
            }.onFailure { AgentLog.log("VOICE", "prewarm skipped: ${it.message}") }
        }
    }

    private const val PREWARM_URL = "https://api.deepgram.com/"
}
