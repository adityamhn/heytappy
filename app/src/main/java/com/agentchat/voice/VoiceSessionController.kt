package com.agentchat.voice

import com.agentchat.agent.AgentAccessibilityService
import com.agentchat.agent.AgentLog
import com.agentchat.agent.AgentOrchestrator
import com.agentchat.agent.llm.AnthropicClient
import com.agentchat.data.ChatRepository
import com.agentchat.data.MessageStatus
import com.agentchat.guide.GuideOutcome
import com.agentchat.guide.GuideSession
import com.agentchat.settings.AgentSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversational voice loop, extracted from the chat ViewModel so it can be
 * hosted anywhere — the in-app mic button and the system-wide foreground
 * service both drive this one instance (via [com.agentchat.AppGraph]).
 *
 * Flow per utterance: listen (Deepgram live STT) → route → respond → listen
 * again. Routing:
 *  1. A parked agent ask_user question always gets the reply first.
 *  2. Otherwise the utterance goes to the [GuideSession] tutor, which can see
 *     the screen, talk, and point via the annotation overlay.
 *  3. If the guide decides the user wants it DONE, it delegates and the task
 *     runs through [AgentOrchestrator.handleVoice]; the result is spoken and
 *     fed back into the guide conversation.
 */
class VoiceSessionController(
    private val repository: ChatRepository,
    private val settings: AgentSettings,
    private val orchestrator: AgentOrchestrator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private var sessionJob: Job? = null
    private var guide: GuideSession? = null

    val isActive: Boolean get() = sessionJob?.isActive == true

    /**
     * Starts a session. [systemWide] sessions don't pull the chat activity to
     * the foreground when a run finishes — the user stays in the app they're in.
     * Callers must hold RECORD_AUDIO. Returns false if a key is missing.
     */
    fun start(systemWide: Boolean, onSessionEnd: () -> Unit = {}): Boolean {
        if (isActive) return true
        val dgKey = settings.deepgramKey
        val anthropicKey = settings.apiKey
        if (dgKey == null || anthropicKey == null) {
            scope.launch {
                repository.addAgentMessage(
                    if (dgKey == null) {
                        "This build has no voice API key — rebuild with DEEPGRAM_API_KEY in .env."
                    } else {
                        "This build has no Anthropic API key — rebuild with ANTHROPIC_API_KEY in .env."
                    },
                    status = MessageStatus.ERROR,
                )
            }
            return false
        }

        val stt: SttClient = DeepgramLiveClient(dgKey)
        val tts: TtsClient = DeepgramTts(dgKey)
        AgentLog.log("VOICE", "provider: Deepgram")

        // Warm the TLS connection to Deepgram now so the first utterance's
        // websocket upgrade reuses a live socket instead of a cold handshake.
        VoiceHttp.prewarm(scope)

        val recorder = VoiceRecorder()
        orchestrator.bringChatForwardOnFinish = !systemWide
        guide = null // fresh tutor context (and current keys) per session

        sessionJob = scope.launch {
            try {
                while (isActive) {
                    guide?.clearOverlay()
                    _voiceState.value = VoiceState.Listening("")
                    val utterance = withTimeoutOrNull(LISTEN_TIMEOUT_MS) {
                        stt.transcribeUtterance(this, recorder.stream()) { interim ->
                            _voiceState.value = VoiceState.Listening(interim)
                        }
                    }?.trim()

                    // Nothing said within the window — dismiss like Siri does.
                    if (utterance == null) break
                    if (utterance.isEmpty()) continue

                    repository.addUserMessage(utterance)
                    _voiceState.value = VoiceState.Working(utterance)
                    handleUtterance(utterance, tts)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AgentLog.log("VOICE", "session error: ${e.message}")
                repository.addAgentMessage(
                    e.message ?: "Voice mode hit an error.",
                    status = MessageStatus.ERROR,
                )
            } finally {
                guide?.clearOverlay()
                _voiceState.value = VoiceState.Idle
                orchestrator.bringChatForwardOnFinish = true
                onSessionEnd()
            }
        }
        return true
    }

    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        _voiceState.value = VoiceState.Idle
    }

    private suspend fun handleUtterance(utterance: String, tts: TtsClient) {
        // A parked agent question gets the user's reply before anything else.
        if (orchestrator.isAwaitingUser) {
            val reply = orchestrator.handleVoice(utterance)
            _voiceState.value = VoiceState.Speaking(reply)
            speak(tts, reply)
            return
        }

        val guideSession = ensureGuide(tts)
        if (guideSession == null) {
            // No accessibility service: the guide can't see the screen. Fall back
            // to the plain agent path, which posts its own setup-error message.
            val reply = orchestrator.handleVoice(utterance)
            _voiceState.value = VoiceState.Speaking(reply)
            speak(tts, reply)
            return
        }

        when (val outcome = guideSession.handle(utterance)) {
            is GuideOutcome.Spoken -> {
                // The guide already spoke segment-by-segment; log it to chat.
                repository.addAgentMessage(outcome.text)
            }

            is GuideOutcome.Delegate -> {
                _voiceState.value = VoiceState.Working(outcome.task)
                val reply = orchestrator.handleVoice(outcome.task)
                guideSession.recordAgentResult(reply)
                _voiceState.value = VoiceState.Speaking(reply)
                speak(tts, reply)
            }
        }
    }

    /**
     * (Re)creates the guide when the service or key becomes available. One guide
     * persists across utterances so follow-up questions keep their context.
     */
    private fun ensureGuide(tts: TtsClient): GuideSession? {
        val service = AgentAccessibilityService.instance ?: run {
            guide = null
            return null
        }
        guide?.let { return it }
        val anthropicKey = settings.apiKey ?: return null
        return GuideSession(
            client = AnthropicClient(anthropicKey, settings.model),
            service = service,
            tts = tts,
            onSpeaking = { text -> _voiceState.value = VoiceState.Speaking(text) },
        ).also { guide = it }
    }

    private suspend fun speak(tts: TtsClient, text: String) {
        runCatching { tts.speak(text) }
            .onFailure { AgentLog.log("VOICE_TTS", "speak error: ${it.message}") }
    }

    companion object {
        /** How long the mic stays open waiting for one utterance before dismissing. */
        private const val LISTEN_TIMEOUT_MS = 30_000L
    }
}
