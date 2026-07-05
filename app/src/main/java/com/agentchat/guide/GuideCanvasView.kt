package com.agentchat.guide

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The HeyClicky-style drawing surface: a glowing triangle cursor that springs
 * between targets, plus neon annotations (circles, boxes, arrows, curves,
 * polygons) each with an optional label chip. Lives inside a full-screen
 * pass-through accessibility overlay; it never receives touches.
 *
 * All coordinates are real device pixels in this view's window (which is
 * FLAG_LAYOUT_IN_SCREEN, so view coords == screen coords).
 */
class GuideCanvasView(context: Context) : View(context) {

    // ---- Cursor state ---------------------------------------------------------

    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorVisible = false
    private var cursorAngle = 0f // radians; direction the triangle points
    private var flightAnimator: ValueAnimator? = null

    /** Idle bob/pulse phase, driven by one endless animator. */
    private var pulsePhase = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1_600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            if (cursorVisible || drawn.isNotEmpty()) invalidate()
        }
    }

    // ---- Annotation state -----------------------------------------------------

    private class Drawn(val annotation: GuideAnnotation, val color: Int) {
        var alpha = 0f
        var progress = 0f // 0..1 draw-on progress for strokes
    }

    private val drawn = ArrayList<Drawn>()
    private var colorCursor = 0

    // ---- Paints -----------------------------------------------------------------

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(9f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        isFakeBoldText = true
    }

    private val workPath = Path()

    init {
        // Software layer: BlurMaskFilter is unsupported on hardware canvases.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        flightAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- Public API (main thread only) ------------------------------------------

    /** Flies the cursor to (x, y) with a springy overshoot, like HeyClicky's buddy. */
    fun flyCursorTo(x: Float, y: Float) {
        flightAnimator?.cancel()
        if (!cursorVisible) {
            // First appearance: come in from the bottom edge for a "flying in" feel.
            cursorX = width / 2f
            cursorY = height + dp(60f)
            cursorVisible = true
        }
        val fromX = cursorX
        val fromY = cursorY
        cursorAngle = atan2(y - fromY, x - fromX)
        val distance = hypot(x - fromX, y - fromY)
        flightAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (350 + distance / 6).toLong().coerceAtMost(900)
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                cursorX = fromX + (x - fromX) * t
                cursorY = fromY + (y - fromY) * t
                invalidate()
            }
            start()
        }
    }

    /** Adds an annotation with a fade/draw-on entrance. Assigns the next neon color. */
    fun addAnnotation(annotation: GuideAnnotation) {
        val item = Drawn(annotation, NEON_COLORS[colorCursor++ % NEON_COLORS.size])
        drawn.add(item)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                val t = it.animatedValue as Float
                item.alpha = t
                item.progress = t
                invalidate()
            }
            start()
        }
        if (annotation is GuideAnnotation.Point) {
            flyCursorTo(annotation.x, annotation.y)
        }
    }

    /** Fades everything out, then removes it. */
    fun clearAll(onDone: (() -> Unit)? = null) {
        if (drawn.isEmpty() && !cursorVisible) {
            onDone?.invoke()
            return
        }
        val items = ArrayList(drawn)
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 300
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                items.forEach { it.alpha = it.alpha.coerceAtMost(t) }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    drawn.removeAll(items.toSet())
                    cursorVisible = false
                    colorCursor = 0
                    invalidate()
                    onDone?.invoke()
                }
            })
            start()
        }
    }

    val isEmpty: Boolean get() = drawn.isEmpty() && !cursorVisible

    // ---- Drawing ----------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawn.forEach { drawAnnotation(canvas, it) }
        if (cursorVisible) drawCursor(canvas)
    }

    private fun drawAnnotation(canvas: Canvas, item: Drawn) {
        val alpha255 = (item.alpha * 255).toInt().coerceIn(0, 255)
        strokePaint.color = item.color
        strokePaint.alpha = alpha255
        glowPaint.color = item.color
        glowPaint.alpha = (alpha255 * 0.55f).toInt()

        when (val a = item.annotation) {
            is GuideAnnotation.Point -> {
                // Cursor handles the pointer; just place the chip near the target.
                a.label?.let { drawChip(canvas, it, a.x, a.y - dp(46f), item.color, item.alpha) }
            }

            is GuideAnnotation.Circle -> {
                // Gentle breathing so highlights feel alive.
                val breathe = 1f + 0.04f * sin(pulsePhase.toDouble()).toFloat()
                val r = a.radius * item.progress * breathe
                canvas.drawCircle(a.x, a.y, r, glowPaint)
                canvas.drawCircle(a.x, a.y, r, strokePaint)
                a.label?.let { drawChip(canvas, it, a.x, a.y - r - dp(28f), item.color, item.alpha) }
            }

            is GuideAnnotation.Box -> {
                val rect = RectF(a.left, a.top, a.right, a.bottom)
                val corner = dp(12f)
                canvas.drawRoundRect(rect, corner, corner, glowPaint)
                canvas.drawRoundRect(rect, corner, corner, strokePaint)
                a.label?.let {
                    drawChip(canvas, it, rect.centerX(), rect.top - dp(28f), item.color, item.alpha)
                }
            }

            is GuideAnnotation.Arrow -> {
                val t = item.progress
                val ex = a.x1 + (a.x2 - a.x1) * t
                val ey = a.y1 + (a.y2 - a.y1) * t
                canvas.drawLine(a.x1, a.y1, ex, ey, glowPaint)
                canvas.drawLine(a.x1, a.y1, ex, ey, strokePaint)
                if (t > 0.85f) drawArrowHead(canvas, a.x1, a.y1, ex, ey, item.color, alpha255)
                a.label?.let {
                    drawChip(
                        canvas, it,
                        (a.x1 + a.x2) / 2f, (a.y1 + a.y2) / 2f - dp(28f),
                        item.color, item.alpha,
                    )
                }
            }

            is GuideAnnotation.Path -> {
                if (a.points.size < 2) return
                buildSmoothPath(a.points, a.closed)
                canvas.drawPath(workPath, glowPaint)
                canvas.drawPath(workPath, strokePaint)
                a.label?.let {
                    val first = a.points.first()
                    drawChip(canvas, it, first.x, first.y - dp(28f), item.color, item.alpha)
                }
            }
        }
    }

    /** Quadratic-smoothed polyline through the points (straight for 2 points). */
    private fun buildSmoothPath(points: List<PointF>, closed: Boolean) {
        workPath.reset()
        workPath.moveTo(points[0].x, points[0].y)
        if (points.size == 2) {
            workPath.lineTo(points[1].x, points[1].y)
        } else {
            for (i in 1 until points.size - 1) {
                val midX = (points[i].x + points[i + 1].x) / 2f
                val midY = (points[i].y + points[i + 1].y) / 2f
                workPath.quadTo(points[i].x, points[i].y, midX, midY)
            }
            val last = points.last()
            workPath.lineTo(last.x, last.y)
        }
        if (closed) workPath.close()
    }

    private fun drawArrowHead(
        canvas: Canvas,
        x1: Float, y1: Float, x2: Float, y2: Float,
        color: Int, alpha: Int,
    ) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val size = dp(14f)
        val spread = Math.PI / 7
        workPath.reset()
        workPath.moveTo(x2, y2)
        workPath.lineTo(
            (x2 - size * cos(angle - spread)).toFloat(),
            (y2 - size * sin(angle - spread)).toFloat(),
        )
        workPath.lineTo(
            (x2 - size * cos(angle + spread)).toFloat(),
            (y2 - size * sin(angle + spread)).toFloat(),
        )
        workPath.close()
        fillPaint.color = color
        fillPaint.alpha = alpha
        canvas.drawPath(workPath, fillPaint)
    }

    /** Rounded label chip (like HeyClicky's red "leg B equals 3" tags). */
    private fun drawChip(canvas: Canvas, text: String, cx: Float, cy: Float, color: Int, alpha: Float) {
        val padH = dp(10f)
        val padV = dp(6f)
        val textWidth = chipTextPaint.measureText(text)
        val fm = chipTextPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent
        var left = cx - textWidth / 2 - padH
        var top = cy - textHeight / 2 - padV
        // Keep the chip on screen.
        left = left.coerceIn(dp(4f), width - textWidth - padH * 2 - dp(4f))
        top = top.coerceIn(dp(4f), height - textHeight - padV * 2 - dp(4f))
        val rect = RectF(left, top, left + textWidth + padH * 2, top + textHeight + padV * 2)

        chipPaint.color = color
        chipPaint.alpha = (alpha * 235).toInt()
        canvas.drawRoundRect(rect, dp(8f), dp(8f), chipPaint)
        chipTextPaint.alpha = (alpha * 255).toInt()
        canvas.drawText(text, rect.left + padH, rect.top + padV - fm.ascent, chipTextPaint)
    }

    /** Glowing triangle buddy with an idle bob, pointing along its last flight direction. */
    private fun drawCursor(canvas: Canvas) {
        val bob = dp(3f) * sin(pulsePhase.toDouble()).toFloat()
        val cx = cursorX
        val cy = cursorY + bob
        val size = dp(17f)

        canvas.save()
        canvas.rotate(Math.toDegrees(cursorAngle.toDouble()).toFloat() + 90f, cx, cy)
        workPath.reset()
        workPath.moveTo(cx, cy - size) // tip
        workPath.lineTo(cx - size * 0.75f, cy + size * 0.8f)
        workPath.lineTo(cx, cy + size * 0.35f) // notched tail
        workPath.lineTo(cx + size * 0.75f, cy + size * 0.8f)
        workPath.close()

        // Halo, body, outline.
        fillPaint.color = CURSOR_COLOR
        fillPaint.alpha = 90
        fillPaint.maskFilter = BlurMaskFilter(dp(12f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawPath(workPath, fillPaint)
        fillPaint.maskFilter = null
        fillPaint.alpha = 255
        canvas.drawPath(workPath, fillPaint)
        strokePaint.color = Color.WHITE
        strokePaint.alpha = 230
        canvas.drawPath(workPath, strokePaint)
        canvas.restore()
    }

    companion object {
        /** Neon palette inspired by the HeyClicky look: cyan, pink, green, yellow, orange. */
        private val NEON_COLORS = intArrayOf(
            Color.parseColor("#00E5FF"),
            Color.parseColor("#FF4081"),
            Color.parseColor("#76FF03"),
            Color.parseColor("#FFEA00"),
            Color.parseColor("#FF9100"),
        )
        private val CURSOR_COLOR = Color.parseColor("#2979FF")
    }
}
