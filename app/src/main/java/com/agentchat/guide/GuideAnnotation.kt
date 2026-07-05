package com.agentchat.guide

import android.graphics.PointF

/**
 * One visual element the guide overlay can draw. All coordinates are REAL
 * device pixels — [GuideOverlayController] scales model (image-space)
 * coordinates before anything reaches the canvas.
 */
sealed interface GuideAnnotation {

    /** Fly the glowing cursor to this point (with an optional label chip). */
    data class Point(val x: Float, val y: Float, val label: String?) : GuideAnnotation

    /** Neon circle highlight around a target. */
    data class Circle(
        val x: Float,
        val y: Float,
        val radius: Float,
        val label: String?,
    ) : GuideAnnotation

    /** Rounded-rect highlight over a region. */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val label: String?,
    ) : GuideAnnotation

    /** Arrow from tail to head (head gets the arrowhead). */
    data class Arrow(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val label: String?,
    ) : GuideAnnotation

    /**
     * Free-form path through the given points; 3+ points are rendered as a
     * smooth curve, and [closed] joins the last point back to the first
     * (polygon).
     */
    data class Path(
        val points: List<PointF>,
        val closed: Boolean,
        val label: String?,
    ) : GuideAnnotation
}
