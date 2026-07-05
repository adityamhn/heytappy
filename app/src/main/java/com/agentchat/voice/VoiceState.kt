package com.agentchat.voice

/** UI state of the conversational voice session. */
sealed interface VoiceState {
    /** No session running; mic button shows in the input bar. */
    data object Idle : VoiceState

    /** Mic open, streaming to Deepgram; [transcript] is the live rolling text. */
    data class Listening(val transcript: String) : VoiceState

    /** Utterance accepted, the agent is operating the phone. */
    data class Working(val task: String) : VoiceState

    /** Speaking the agent's reply aloud. */
    data class Speaking(val text: String) : VoiceState
}
