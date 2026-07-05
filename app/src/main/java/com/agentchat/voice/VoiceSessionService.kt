package com.agentchat.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.agentchat.AppGraph
import com.agentchat.R
import com.agentchat.agent.AgentAccessibilityService
import com.agentchat.agent.AgentLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts a system-wide voice session: a foreground service (microphone type) so
 * the mic keeps working while ANY app is in the foreground, plus a floating
 * status pill drawn through the accessibility service. Started by the assistant
 * gesture, the Quick Settings tile, or the AssistActivity; stops itself when
 * the session ends (idle timeout, close button, or the notification's Stop).
 */
class VoiceSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var pill: VoicePillOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppGraph.voiceController.stop()
            stopSelf()
            return START_NOT_STICKY
        }

        AppGraph.init(this)
        startAsForeground()

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Can't record from a service without the permission; open the app once.
            AgentLog.log("VOICE_SVC", "RECORD_AUDIO not granted — opening the app")
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(it) }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        if (AppGraph.voiceController.isActive) return START_NOT_STICKY

        val started = AppGraph.voiceController.start(systemWide = true) {
            scope.launch { stopSelf() }
        }
        if (!started) {
            // Missing keys — the controller posted the setup message; show the app.
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(it) }
            }
            stopSelf()
            return START_NOT_STICKY
        }

        showPill()
        return START_NOT_STICKY
    }

    private fun showPill() {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            AgentLog.log("VOICE_SVC", "accessibility service off — no floating pill")
            return
        }
        val overlay = VoicePillOverlay(service) {
            AppGraph.voiceController.stop()
        }
        overlay.show()
        pill = overlay
        stateJob = scope.launch {
            AppGraph.voiceController.voiceState.collect { state ->
                overlay.update(state)
            }
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Tappy voice",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while Tappy is listening" },
            )
        }
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Tappy is listening — speak anytime")
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    override fun onDestroy() {
        stateJob?.cancel()
        pill?.hide()
        pill = null
        AppGraph.voiceController.stop()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "com.agentchat.voice.STOP"

        fun start(context: Context) {
            val intent = Intent(context, VoiceSessionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
