package com.agentchat.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import kotlin.coroutines.coroutineContext

/**
 * Plays a raw PCM16 mono byte stream through an [AudioTrack] as it arrives —
 * no container parsing, first audio audible before the full response has
 * downloaded.
 */
object PcmStreamPlayer {

    /** Streams [input] (PCM16 mono at [sampleRate]) to the speaker; suspends until drained. */
    suspend fun play(input: InputStream, sampleRate: Int) {
        val track = buildTrack(sampleRate)
        try {
            track.play()
            val buffer = ByteArray(CHUNK_BYTES)
            var framesWritten = 0L
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    val written = track.write(buffer, offset, read - offset)
                    if (written < 0) return
                    offset += written
                    framesWritten += written / BYTES_PER_FRAME
                }
            }
            // All PCM handed to the track; stop() lets the buffered tail play out.
            track.stop()
            awaitDrain(track, framesWritten)
        } finally {
            runCatching { track.release() }
        }
    }

    /**
     * Suspends until the speaker has actually played every frame we wrote.
     * [AudioTrack.getPlaybackHeadPosition] counts frames rendered so far, so we
     * wait until it reaches [framesWritten]. A stall guard covers the rare case
     * where the head stops just shy of the total (e.g. underrun on release).
     */
    private suspend fun awaitDrain(track: AudioTrack, framesWritten: Long) {
        if (framesWritten <= 0) return
        var last = -1
        var stalls = 0
        while (true) {
            coroutineContext.ensureActive()
            val head = track.playbackHeadPosition
            if (head >= framesWritten) return
            if (head == last) {
                if (++stalls >= MAX_STALLS) return
            } else {
                stalls = 0
                last = head
            }
            delay(DRAIN_POLL_MS)
        }
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
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
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private const val CHUNK_BYTES = 8_192
    private const val BYTES_PER_FRAME = 2 // PCM16 mono
    private const val DRAIN_POLL_MS = 20L

    // ~1.6 s of no head movement (20 ms poll) before we give up waiting.
    private const val MAX_STALLS = 80
}
