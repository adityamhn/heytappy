package com.agentchat.assist

import android.service.voice.VoiceInteractionService

/**
 * Minimal digital-assistant registration. Selecting AgentChat under
 * Settings → Apps → Default apps → Digital assistant app makes the system
 * assistant gesture (corner swipe / home long-press / power long-press on
 * supported devices) open our session, which just kicks off the system-wide
 * voice service. All real logic lives in [com.agentchat.voice.VoiceSessionService].
 */
class AgentVoiceInteractionService : VoiceInteractionService()
