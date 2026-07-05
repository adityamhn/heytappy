package com.agentchat.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Captures microphone audio as 16 kHz mono PCM16 — the exact format streamed to
 * Deepgram live transcription (encoding=linear16&sample_rate=16000&channels=1).
 * Emits ~100 ms chunks; collection must happen while the app holds the
 * RECORD_AUDIO permission and is in the foreground.
 */
class VoiceRecorder {

    /** Streams audio until the collector is cancelled. */
    @SuppressLint("MissingPermission") // callers gate on RECORD_AUDIO before starting
    fun stream(): Flow<ByteArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Microphone unavailable (AudioRecord failed to initialize).")
        }
        try {
            record.startRecording()
            com.agentchat.agent.AgentLog.log("VOICE_MIC", "recording started")
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        // 100 ms of 16 kHz mono PCM16 — inside Deepgram's 20–250 ms sweet spot.
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2
    }
}
