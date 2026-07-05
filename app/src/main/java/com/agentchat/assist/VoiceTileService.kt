package com.agentchat.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.agentchat.AppGraph

/**
 * Quick Settings tile: one tap from any screen starts (or stops) the
 * system-wide voice session. Goes through [AssistActivity] so the mic
 * permission dialog can appear on first use.
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        AppGraph.init(this)
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        AppGraph.init(this)
        if (AppGraph.voiceController.isActive) {
            AppGraph.voiceController.stop()
            refreshTile()
            return
        }

        val intent = Intent(this, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTile() {
        qsTile?.apply {
            state = if (AppGraph.voiceController.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
