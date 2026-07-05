package com.agentchat.agent

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured trace of every agent run: what the model saw (observations,
 * screenshots), what it said, which tools it called with what input, what they
 * returned, and token/latency stats. Mirrored to logcat (tag "Tappy") and
 * to one file per run under Android/data/<pkg>/files/agent-logs/ so it can be
 * pulled with `adb pull` or any file manager.
 *
 * Watch live with: adb logcat -s Tappy
 */
object AgentLog {

    private const val TAG = "Tappy"

    // logcat truncates entries around ~4k bytes; chunk long payloads.
    private const val LOGCAT_CHUNK = 3_500
    private const val MAX_RUN_FILES = 20

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile
    private var dir: File? = null

    @Volatile
    private var runFile: File? = null

    private val lock = Any()

    fun init(context: Context) {
        if (dir != null) return
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        dir = File(base, "agent-logs").apply { mkdirs() }
    }

    /** File the current/most recent run is being written to, if any. */
    val currentRunFile: File? get() = runFile

    /** Opens a fresh log file for a new agent run. */
    fun startRun(task: String) {
        dir?.let { d ->
            synchronized(lock) {
                runFile = File(d, "run-${fileNameFormat.format(Date())}.log")
                pruneOldRuns(d)
            }
        }
        log("RUN_START", task)
    }

    fun log(event: String, detail: String = "") {
        val ts = timeFormat.format(Date())
        val line = if (detail.isEmpty()) "[$ts] $event" else "[$ts] $event\n$detail"
        toLogcat(line)
        synchronized(lock) {
            runFile?.let { file ->
                runCatching { file.appendText(line + "\n\n") }
            }
        }
    }

    private fun toLogcat(message: String) {
        var start = 0
        while (start < message.length) {
            val end = (start + LOGCAT_CHUNK).coerceAtMost(message.length)
            Log.i(TAG, message.substring(start, end))
            start = end
        }
    }

    private fun pruneOldRuns(d: File) {
        val files = d.listFiles() ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_RUN_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
