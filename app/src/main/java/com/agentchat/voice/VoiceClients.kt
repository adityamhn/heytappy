package com.agentchat.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * One provider-agnostic live speech-to-text session. Implementations stream
 * mic PCM (16 kHz mono PCM16) to their backend and return one utterance once
 * the provider's endpointing decides the user stopped speaking.
 */
interface SttClient {
    /**
     * Streams [audio] until the speaker finishes one utterance, then returns it.
     * [onInterim] receives the rolling transcript for live display. Throws on
     * connection/auth failure. Implementations MUST start collecting [audio]
     * immediately (buffering while the backend connects) so speech from the
     * very first moment of the listening turn is never dropped.
     */
    suspend fun transcribeUtterance(
        scope: CoroutineScope,
        audio: Flow<ByteArray>,
        onInterim: (String) -> Unit,
    ): String
}

/** Provider-agnostic text-to-speech: synthesize and play through the speaker. */
interface TtsClient {
    /** Synthesizes and plays [text]; suspends until playback finishes or the caller is cancelled. */
    suspend fun speak(text: String)
}
