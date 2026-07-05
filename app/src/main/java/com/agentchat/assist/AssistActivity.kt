package com.agentchat.assist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.agentchat.AppGraph
import com.agentchat.voice.VoiceSessionService

/**
 * Invisible trampoline that starts a system-wide voice session and finishes.
 * It's the target for every "activate from anywhere" path that needs an
 * activity: ACTION_ASSIST / ACTION_VOICE_COMMAND, the Quick Settings tile,
 * and a Samsung side-key "double press → open app" mapping.
 *
 * Being an activity (unlike the services) it can also show the runtime
 * permission dialogs on first use — RECORD_AUDIO for the mic and, on
 * Android 13+, POST_NOTIFICATIONS for the foreground-service notification —
 * so activation works out of the box.
 */
class AssistActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            VoiceSessionService.start(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)

        val missing = buildList {
            if (!isGranted(Manifest.permission.RECORD_AUDIO)) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            VoiceSessionService.start(this)
            finish()
        } else {
            permissions.launch(missing.toTypedArray())
        }
    }

    private fun isGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
