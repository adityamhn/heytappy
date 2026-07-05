package com.agentchat

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.agentchat.agent.AccessibilityUtil
import com.agentchat.ui.chat.ChatScreen
import com.agentchat.ui.chat.ChatViewModel
import com.agentchat.ui.theme.TappyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private var serviceEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TappyTheme {
                val messages by viewModel.messages.collectAsState()
                val installedApps by viewModel.installedApps.collectAsState()
                val voiceState by viewModel.voiceState.collectAsState()
                ChatScreen(
                    messages = messages,
                    installedApps = installedApps,
                    serviceEnabled = serviceEnabled,
                    voiceState = voiceState,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onStartVoice = viewModel::startVoice,
                    onStopVoice = viewModel::stopVoice,
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenAssistantSettings = ::openAssistantSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = AccessibilityUtil.isServiceEnabled(this)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Opens the default-assistant picker (falls back to app-default settings). */
    private fun openAssistantSettings() {
        val direct = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        runCatching { startActivity(direct) }
            .onFailure { runCatching { startActivity(fallback) } }
    }
}
