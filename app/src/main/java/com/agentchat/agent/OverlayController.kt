package com.agentchat.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws a small "working" pill on top of every app using a
 * TYPE_ACCESSIBILITY_OVERLAY window, so it's always visible that the agent is
 * driving the screen. This overlay type is granted to accessibility services
 * without needing the SYSTEM_ALERT_WINDOW permission.
 */
class OverlayController(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var overlayView: View? = null
    private var label: TextView? = null

    fun show(text: String) {
        handler.post {
            if (overlayView != null) {
                label?.text = text
                return@post
            }
            val density = service.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            val textView = TextView(service).apply {
                this.text = text
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(18), dp(10), dp(18), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(Color.parseColor("#E64C5FD5"))
                }
            }
            val container = LinearLayout(service).apply {
                gravity = Gravity.CENTER
                addView(textView)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(80)
            }

            runCatching { windowManager.addView(container, params) }
                .onSuccess {
                    overlayView = container
                    label = textView
                }
        }
    }

    fun update(text: String) {
        handler.post { label?.text = text }
    }

    fun hide() {
        handler.post {
            overlayView?.let { view ->
                runCatching { windowManager.removeView(view) }
            }
            overlayView = null
            label = null
        }
    }
}
