package com.agentchat.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.agentchat.agent.llm.AnthropicClient

/**
 * On-device store for the Anthropic API key and model id. Backed by
 * [EncryptedSharedPreferences] so the key is encrypted at rest with an
 * Android-Keystore-held master key — never written in plaintext, never leaves
 * the device except in the Authorization header to Anthropic.
 */
class AgentSettings(context: Context) {

    private val prefs: SharedPreferences = run {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value.trim())
            }.apply()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() }
            ?: AnthropicClient.DEFAULT_MODEL
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.trim()).apply()
        }

    /** Deepgram API key powering voice mode (streaming STT + Aura TTS). */
    var deepgramKey: String?
        get() = prefs.getString(KEY_DEEPGRAM, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_DEEPGRAM) else putString(KEY_DEEPGRAM, value.trim())
            }.apply()
        }

    val hasApiKey: Boolean get() = apiKey != null

    val hasDeepgramKey: Boolean get() = deepgramKey != null

    /** A masked preview like "sk-ant-…4f2a" for display in the setup sheet. */
    fun maskedKey(): String? = mask(apiKey)

    fun maskedDeepgramKey(): String? = mask(deepgramKey)

    private fun mask(key: String?): String? {
        if (key == null) return null
        if (key.length <= 10) return "•".repeat(key.length)
        return key.take(7) + "…" + key.takeLast(4)
    }

    companion object {
        private const val FILE_NAME = "agent_settings"
        private const val KEY_API = "anthropic_api_key"
        private const val KEY_MODEL = "anthropic_model"
        private const val KEY_DEEPGRAM = "deepgram_api_key"
    }
}
