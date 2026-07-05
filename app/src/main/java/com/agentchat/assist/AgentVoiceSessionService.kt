package com.agentchat.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.agentchat.voice.VoiceSessionService

/**
 * The session the system spawns when the assistant gesture fires. Instead of
 * drawing the standard assist UI we immediately start the voice foreground
 * service (mic + floating pill over the current app) and dismiss ourselves.
 */
class AgentVoiceSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AgentVoiceSession(this)

    private class AgentVoiceSession(context: Context) : VoiceInteractionSession(context) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            // Route through the trampoline activity so a missing RECORD_AUDIO
            // permission gets its dialog instead of silently failing.
            val intent = Intent(context, com.agentchat.assist.AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .onFailure { VoiceSessionService.start(context) }
            hide()
        }
    }
}
