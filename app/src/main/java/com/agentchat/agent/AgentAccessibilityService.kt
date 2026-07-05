package com.agentchat.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.agentchat.guide.GuideOverlayController
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * The engine's window into the system. While enabled, it holds a live handle
 * that [UiDriver], [ScreenObserver], and the agent loop use to read and drive
 * whatever app is on screen. Exposed as a process-wide singleton via [instance]
 * so the chat layer can reach it without binding manually.
 */
class AgentAccessibilityService : AccessibilityService() {

    lateinit var driver: UiDriver
        private set

    lateinit var overlay: OverlayController
        private set

    lateinit var guideOverlay: GuideOverlayController
        private set

    /**
     * Uptime (ms) of the most recent UI change event. The agent loop watches this
     * to know when a screen has stopped mutating (event-debounced idle) instead
     * of sleeping a fixed amount after each action.
     */
    @Volatile
    var lastEventUptime: Long = 0L
        private set

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentLog.init(this)
        driver = UiDriver(this)
        overlay = OverlayController(this)
        guideOverlay = GuideOverlayController(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> lastEventUptime = SystemClock.uptimeMillis()
        }
    }

    override fun onInterrupt() = Unit

    /**
     * Captures the current screen via the platform accessibility screenshot API
     * (available API 30+). Returns null if capture fails or isn't supported.
     */
    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = runCatching {
                                val buffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val bmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                                buffer.close()
                                bmp
                            }.onFailure {
                                AgentLog.log("SCREENSHOT_FAIL", "bitmap conversion: $it")
                            }.getOrNull()
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            AgentLog.log("SCREENSHOT_FAIL", "errorCode=$errorCode " + screenshotError(errorCode))
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure {
                AgentLog.log("SCREENSHOT_FAIL", "takeScreenshot threw: $it")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        overlay.hide()
        guideOverlay.destroy()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val isConnected: Boolean get() = instance != null

        private fun screenshotError(code: Int): String = when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "(internal error)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "(no accessibility access)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "(rate limited — captures too close together)"
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "(invalid display)"
            else -> ""
        }
    }
}
