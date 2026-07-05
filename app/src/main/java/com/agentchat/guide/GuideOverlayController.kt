package com.agentchat.guide

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import com.agentchat.agent.CoordinateScaler

/**
 * Owns the full-screen HeyClicky-style annotation overlay: a transparent,
 * touch-pass-through TYPE_ACCESSIBILITY_OVERLAY window hosting a
 * [GuideCanvasView]. The window is added lazily on the first annotation and
 * removed when cleared, so it costs nothing while the guide is quiet.
 *
 * Callers pass annotations in the model's image space along with the
 * [CoordinateScaler] from the observation they were generated against; scaling
 * to device pixels happens here so the canvas only ever sees real pixels.
 */
class GuideOverlayController(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager

    private var canvas: GuideCanvasView? = null

    /** Shows the cursor flying to an image-space point, plus an optional label chip. */
    fun showAnnotation(annotation: GuideAnnotation, scaler: CoordinateScaler) {
        handler.post {
            val view = ensureWindow() ?: return@post
            view.addAnnotation(scale(annotation, scaler))
        }
    }

    /** Fades out and removes everything, then tears the window down. */
    fun clear() {
        handler.post {
            val view = canvas ?: return@post
            view.clearAll { removeWindow() }
        }
    }

    /** Immediate teardown (service unbind). */
    fun destroy() {
        handler.post { removeWindow() }
    }

    // ---- Scaling ---------------------------------------------------------------

    private fun scale(a: GuideAnnotation, s: CoordinateScaler): GuideAnnotation = when (a) {
        is GuideAnnotation.Point -> a.copy(
            x = s.x(a.x.toInt()).toFloat(),
            y = s.y(a.y.toInt()).toFloat(),
        )
        is GuideAnnotation.Circle -> a.copy(
            x = s.x(a.x.toInt()).toFloat(),
            y = s.y(a.y.toInt()).toFloat(),
            // Radius follows the horizontal scale; aspect ratio is preserved by
            // the downscaler so either axis gives the same factor.
            radius = s.x(a.radius.toInt()).toFloat().coerceAtLeast(MIN_RADIUS_PX),
        )
        is GuideAnnotation.Box -> a.copy(
            left = s.x(a.left.toInt()).toFloat(),
            top = s.y(a.top.toInt()).toFloat(),
            right = s.x(a.right.toInt()).toFloat(),
            bottom = s.y(a.bottom.toInt()).toFloat(),
        )
        is GuideAnnotation.Arrow -> a.copy(
            x1 = s.x(a.x1.toInt()).toFloat(),
            y1 = s.y(a.y1.toInt()).toFloat(),
            x2 = s.x(a.x2.toInt()).toFloat(),
            y2 = s.y(a.y2.toInt()).toFloat(),
        )
        is GuideAnnotation.Path -> a.copy(
            points = a.points.map { p ->
                android.graphics.PointF(s.x(p.x.toInt()).toFloat(), s.y(p.y.toInt()).toFloat())
            },
        )
    }

    // ---- Window management -------------------------------------------------------

    private fun ensureWindow(): GuideCanvasView? {
        canvas?.let { return it }
        val view = GuideCanvasView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return runCatching { windowManager.addView(view, params) }
            .map {
                canvas = view
                view
            }
            .getOrNull()
    }

    private fun removeWindow() {
        canvas?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        canvas = null
    }

    companion object {
        private const val MIN_RADIUS_PX = 40f
    }
}
