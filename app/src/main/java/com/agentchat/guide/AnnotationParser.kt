package com.agentchat.guide

import android.graphics.PointF

/**
 * Parses the guide model's inline annotation tags out of its spoken reply.
 *
 * Tag grammar (coordinates in the model's image space):
 *   [POINT:x,y:label]
 *   [CIRCLE:x,y,r:label]
 *   [BOX:l,t,r,b:label]
 *   [ARROW:x1,y1,x2,y2:label]
 *   [PATH:x1,y1 x2,y2 x3,y3:label]        (open curve)
 *   [POLYGON:x1,y1 x2,y2 x3,y3:label]     (closed shape)
 *   [AWAIT_ACTION]                        (walkthrough step boundary)
 *
 * Labels are optional. The reply is split into [Segment]s at tag boundaries so
 * playback can render each annotation right before speaking the text that
 * follows it — the HeyClicky "cursor lands as the voice says it" sync.
 */
object AnnotationParser {

    /** One unit of playback: optionally show [annotation], then speak [text]. */
    data class Segment(
        val annotation: GuideAnnotation?,
        val awaitAction: Boolean,
        val text: String,
    )

    data class Parsed(
        val segments: List<Segment>,
        /** The reply with all tags stripped — what actually gets spoken/logged. */
        val spokenText: String,
    )

    private val TAG_REGEX = Regex(
        """\[(POINT|CIRCLE|BOX|ARROW|PATH|POLYGON):([^\]:]+)(?::([^\]]*))?]|\[AWAIT_ACTION]""",
    )

    fun parse(reply: String): Parsed {
        val segments = ArrayList<Segment>()
        var lastEnd = 0
        var pendingAnnotation: GuideAnnotation? = null
        var pendingAwait = false

        fun flushText(upTo: Int) {
            val text = reply.substring(lastEnd, upTo).trim()
            if (text.isNotEmpty() || pendingAnnotation != null || pendingAwait) {
                segments.add(Segment(pendingAnnotation, pendingAwait, text))
                pendingAnnotation = null
                pendingAwait = false
            }
        }

        for (match in TAG_REGEX.findAll(reply)) {
            // Text before this tag belongs to the previous annotation.
            flushText(match.range.first)
            lastEnd = match.range.last + 1

            if (match.value == "[AWAIT_ACTION]") {
                pendingAwait = true
                continue
            }
            val kind = match.groupValues[1]
            val args = match.groupValues[2]
            val label = match.groupValues[3].trim().ifEmpty { null }
            parseTag(kind, args, label)?.let { pendingAnnotation = it }
        }
        flushText(reply.length)

        val spoken = TAG_REGEX.replace(reply, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return Parsed(segments, spoken)
    }

    private fun parseTag(kind: String, args: String, label: String?): GuideAnnotation? {
        return when (kind) {
            "POINT" -> {
                val n = numbers(args, 2) ?: return null
                GuideAnnotation.Point(n[0], n[1], label)
            }
            "CIRCLE" -> {
                val n = numbers(args, 3) ?: return null
                GuideAnnotation.Circle(n[0], n[1], n[2], label)
            }
            "BOX" -> {
                val n = numbers(args, 4) ?: return null
                GuideAnnotation.Box(n[0], n[1], n[2], n[3], label)
            }
            "ARROW" -> {
                val n = numbers(args, 4) ?: return null
                GuideAnnotation.Arrow(n[0], n[1], n[2], n[3], label)
            }
            "PATH", "POLYGON" -> {
                val points = args.trim().split(Regex("\\s+")).mapNotNull { pair ->
                    val xy = pair.split(',')
                    val x = xy.getOrNull(0)?.trim()?.toFloatOrNull() ?: return@mapNotNull null
                    val y = xy.getOrNull(1)?.trim()?.toFloatOrNull() ?: return@mapNotNull null
                    PointF(x, y)
                }
                if (points.size < 2) return null
                GuideAnnotation.Path(points, closed = kind == "POLYGON", label = label)
            }
            else -> null
        }
    }

    private fun numbers(args: String, count: Int): List<Float>? {
        val values = args.split(',').mapNotNull { it.trim().toFloatOrNull() }
        return if (values.size >= count) values else null
    }
}
