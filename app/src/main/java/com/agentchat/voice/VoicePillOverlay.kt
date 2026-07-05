package com.agentchat.voice

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating Siri-style status pill shown during system-wide voice sessions,
 * drawn over whatever app is in the foreground via a TYPE_ACCESSIBILITY_OVERLAY
 * window. Unlike the annotation canvas this window IS touchable — its close
 * button ("✕") ends the session — but it stays small at the bottom of the
 * screen so it never blocks the app underneath.
 */
class VoicePillOverlay(
    private val service: AccessibilityService,
    private val onClose: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var container: LinearLayout? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null

    fun show() {
        handler.post {
            if (container != null) return@post
            val density = service.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            val title = TextView(service).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            val body = TextView(service).apply {
                setTextColor(Color.parseColor("#E6FFFFFF"))
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val textColumn = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(body)
            }
            val close = TextView(service).apply {
                text = "✕"
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = 16f
                setPadding(dp(14), dp(4), dp(4), dp(4))
                setOnClickListener { onClose() }
            }
            val row = LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(26).toFloat()
                    setColor(Color.parseColor("#F0201A35"))
                }
                addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(close)
            }

            val params = WindowManager.LayoutParams(
                (service.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(28)
            }

            runCatching { windowManager.addView(row, params) }
                .onSuccess {
                    container = row
                    titleView = title
                    bodyView = body
                }
        }
    }

    fun update(state: VoiceState) {
        handler.post {
            val (title, body) = when (state) {
                is VoiceState.Listening ->
                    "Listening…" to state.transcript.ifBlank { "Say something…" }
                is VoiceState.Working -> "Working on it…" to state.task
                is VoiceState.Speaking -> "Speaking" to state.text
                VoiceState.Idle -> "Tappy" to ""
            }
            titleView?.text = title
            bodyView?.text = body
        }
    }

    fun hide() {
        handler.post {
            container?.let { view ->
                runCatching { windowManager.removeView(view) }
            }
            container = null
            titleView = null
            bodyView = null
        }
    }
}
