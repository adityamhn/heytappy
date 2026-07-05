package com.agentchat.settings

import com.agentchat.BuildConfig
import com.agentchat.agent.llm.AnthropicClient

/**
 * API keys and model configuration, compiled into the app from the repo-root
 * .env at build time (see app/build.gradle.kts). There is no key-entry UI —
 * a build fails when the Anthropic key or the Deepgram key is missing.
 */
class AgentSettings {

    /** Anthropic API key powering the agent and the voice guide. */
    val apiKey: String? = BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }

    /** Deepgram API key (streaming STT + Aura TTS) — the voice provider. */
    val deepgramKey: String? = BuildConfig.DEEPGRAM_API_KEY.takeIf { it.isNotBlank() }

    val model: String = AnthropicClient.DEFAULT_MODEL

    val hasApiKey: Boolean get() = apiKey != null
}
