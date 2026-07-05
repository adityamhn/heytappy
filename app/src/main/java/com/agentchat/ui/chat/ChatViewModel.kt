package com.agentchat.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentchat.AppGraph
import com.agentchat.agent.AgentAccessibilityService
import com.agentchat.agent.AgentLog
import com.agentchat.apps.InstalledApp
import com.agentchat.data.Message
import com.agentchat.data.MessageStatus
import com.agentchat.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    init {
        AppGraph.init(app)
        AgentLog.init(app)
    }

    private val repository = AppGraph.repository
    private val settings = AppGraph.settings
    private val orchestrator = AppGraph.orchestrator
    private val voiceController = AppGraph.voiceController

    val messages: StateFlow<List<Message>> = repository.messages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val installedApps: StateFlow<List<InstalledApp>> = AppGraph.installedApps

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val voiceState: StateFlow<VoiceState> = voiceController.voiceState

    private var agentJob: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val job = viewModelScope.launch {
            repository.addUserMessage(trimmed)
            if (trimmed.startsWith("/")) {
                handleDebugCommand(trimmed)
                return@launch
            }
            _running.value = true
            try {
                val handled = orchestrator.handle(trimmed)
                if (!handled) {
                    repository.addAgentMessage(
                        "Mention an app with @ and tell me what to do — e.g. " +
                            "@spotify play lofi beats, or @maps navigate to the airport.\n" +
                            "Engine self-tests: /status, /dump, /overlay",
                    )
                }
            } finally {
                _running.value = false
            }
        }
        agentJob = job
    }

    /** Cancels the in-flight agent run; the orchestrator posts a "Stopped" bubble. */
    fun stop() {
        agentJob?.cancel()
        agentJob = null
        _running.value = false
    }

    /**
     * Conversational voice session, hosted by the shared [voiceController] so
     * the same loop powers the in-app mic and system-wide activation. Callers
     * must hold RECORD_AUDIO before starting.
     */
    fun startVoice() {
        voiceController.start(systemWide = false)
    }

    fun stopVoice() {
        voiceController.stop()
    }

    private suspend fun handleDebugCommand(command: String) {
        val service = AgentAccessibilityService.instance

        // "/tap <x> <y>" — dispatches a gesture tap at real pixels after 4s so
        // another app can be foregrounded first. Verifies gesture injection.
        if (command.startsWith("/tap ")) {
            val parts = command.removePrefix("/tap ").trim().split(Regex("\\s+"))
            val x = parts.getOrNull(0)?.toIntOrNull()
            val y = parts.getOrNull(1)?.toIntOrNull()
            if (service == null || x == null || y == null) {
                repository.addAgentMessage("Usage: /tap <x> <y> (service must be enabled)")
            } else {
                repository.addAgentMessage("Tapping ($x, $y) in 4s — switch to the target app.")
                delay(4_000)
                val ok = service.driver.tapAt(x, y)
                repository.addAgentMessage("Gesture tap at ($x, $y): ${if (ok) "completed" else "FAILED"}")
            }
            return
        }

        when (command.lowercase()) {
            "/status", "/help" -> {
                val status = if (service != null) "CONNECTED" else "NOT ENABLED"
                val key = if (settings.hasApiKey) "set" else "MISSING"
                repository.addAgentMessage(
                    "Engine status: $status\nAPI key: $key\nModel: ${settings.model}\n" +
                        "Commands:\n" +
                        "• /status - show engine status\n" +
                        "• /dump - print the current screen's node tree\n" +
                        "• /log - where to find the model input/output trace\n" +
                        "• /overlay - flash the \"working\" overlay for 3s",
                )
            }

            "/log" -> {
                val file = AgentLog.currentRunFile
                repository.addAgentMessage(
                    buildString {
                        append("Every run's model input/output is traced to logcat (tag Tappy) ")
                        append("and to a per-run file.\n\n")
                        append("Live: adb logcat -s Tappy\n")
                        if (file != null) {
                            append("Latest run file: ${file.absolutePath}\n")
                            append("Pull it: adb pull ${file.absolutePath}")
                        } else {
                            append("No run has been logged yet in this session.")
                        }
                    },
                )
            }

            "/dump" -> {
                if (service == null) {
                    repository.addAgentMessage(
                        "Engine not enabled. Open setup (top-right) and turn on the Tappy service.",
                        status = MessageStatus.ERROR,
                    )
                } else {
                    val dump = service.driver.dumpActiveWindow()
                    repository.addAgentMessage("Node tree of the active window:\n\n$dump")
                }
            }

            "/overlay" -> {
                if (service == null) {
                    repository.addAgentMessage(
                        "Engine not enabled. Open setup (top-right) and turn on the Tappy service.",
                        status = MessageStatus.ERROR,
                    )
                } else {
                    service.overlay.show("Tappy working…")
                    repository.addAgentMessage("Overlay shown for 3 seconds.")
                    delay(3_000)
                    service.overlay.hide()
                }
            }

            else -> repository.addAgentMessage(
                "Unknown command \"$command\". Try /status, /dump, or /overlay.",
                status = MessageStatus.ERROR,
            )
        }
    }

    companion object {
        /** How long the mic stays open waiting for one utterance before dismissing. */
        private const val LISTEN_TIMEOUT_MS = 30_000L
    }
}

