package com.agentchat.agent

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turns the live accessibility tree into a compact, indexed description the model
 * can reason over, plus an index→node map the [ActionExecutor] uses to act. This
 * is the "eyes" of the agent: an a11y-tree-first observation, with screenshots
 * captured separately only when the tree is too sparse to be trustworthy.
 */
class ScreenObserver(private val service: AgentAccessibilityService) {

    data class Element(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
    )

    data class Observation(
        val text: String,
        val elements: Map<Int, Element>,
        val packageName: String?,
        val interactiveCount: Int,
        val isSparse: Boolean,
        /** Real device pixels. */
        val screenWidth: Int,
        val screenHeight: Int,
        /**
         * The coordinate space the model works in: identical to the downscaled
         * screenshot's pixel grid. tap_coordinates/swipe inputs arrive in this
         * space and must be scaled up to real pixels before dispatching.
         */
        val imageWidth: Int,
        val imageHeight: Int,
    )

    private val ownPackage = service.packageName

    fun observe(): Observation {
        val metrics = service.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val elements = LinkedHashMap<Int, Element>()
        val lines = StringBuilder()
        val seen = HashSet<String>()
        var index = 0
        var interactive = 0

        val (imageW, imageH) = imageDimensions(screenW, screenH)

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(rootWindows())
        var visited = 0

        while (queue.isNotEmpty() && index < MAX_ELEMENTS && visited < MAX_VISIT) {
            val node = queue.removeFirst()
            visited++

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }

            val pkg = node.packageName?.toString()
            if (pkg == ownPackage) continue
            if (!node.isVisibleToUser) continue

            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val checkable = node.isCheckable
            val label = nodeLabel(node)
            val actionable = clickable || editable || scrollable || checkable

            // Emit actionable nodes and any node that carries visible text.
            if (!actionable && label.isEmpty()) continue

            val signature = "$actionable|$label|${bounds.toShortString()}"
            if (!seen.add(signature)) continue

            val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val flags = buildString {
                if (clickable) append('C')
                if (editable) append('E')
                if (scrollable) append('S')
                if (checkable) append(if (node.isChecked) "K+" else "K-")
            }
            if (actionable) interactive++

            // Centers are reported in the model's (image-space) coordinate grid.
            val cx = if (screenW > 0) bounds.centerX() * imageW / screenW else bounds.centerX()
            val cy = if (screenH > 0) bounds.centerY() * imageH / screenH else bounds.centerY()
            lines.append('[').append(index).append("] ").append(cls)
            if (label.isNotEmpty()) lines.append(" \"").append(label).append('"')
            if (flags.isNotEmpty()) lines.append(' ').append(flags)
            lines.append(" @(").append(cx).append(',').append(cy).append(')')
            lines.append('\n')

            elements[index] = Element(index, node, bounds)
            index++
        }

        val pkg = service.rootInActiveWindow?.packageName?.toString()
        val sparse = interactive < SPARSE_INTERACTIVE || index < SPARSE_TOTAL

        AgentLog.log(
            "OBSERVE",
            "foreground=$pkg elements=$index interactive=$interactive visited=$visited " +
                "sparse=$sparse windows=[${windowSummary()}]",
        )

        val header = buildString {
            append("Foreground app: ").append(pkg ?: "unknown").append('\n')
            append("Screen: ").append(imageW).append('x').append(imageH)
            append(" (screenshots and tap/swipe coordinates use this size)").append('\n')
            if (index == 0) {
                append("(No readable UI elements — the screen is likely custom-rendered; ")
                append("use take_screenshot and tap_coordinates.)\n")
            } else {
                append("Elements (index, type, label, flags C=click E=edit S=scroll K=check, @(centerX,centerY)):\n")
            }
        }

        return Observation(
            text = header + lines.toString(),
            elements = elements,
            packageName = pkg,
            interactiveCount = interactive,
            isSparse = sparse,
            screenWidth = screenW,
            screenHeight = screenH,
            imageWidth = imageW,
            imageHeight = imageH,
        )
    }

    /** Size of the downscaled screenshot for the given screen — the model's coordinate space. */
    private fun imageDimensions(screenW: Int, screenH: Int): Pair<Int, Int> {
        val longest = maxOf(screenW, screenH)
        if (longest <= MAX_IMAGE_DIM) return screenW to screenH
        val scale = MAX_IMAGE_DIM.toFloat() / longest
        return (screenW * scale).toInt().coerceAtLeast(1) to
            (screenH * scale).toInt().coerceAtLeast(1)
    }

    /** Captures the screen and returns a downscaled base64 JPEG, or null. */
    suspend fun screenshotBase64(): String? = withContext(Dispatchers.Default) {
        val bitmap = service.captureScreenshot()
        if (bitmap == null) {
            AgentLog.log("SCREENSHOT", "capture returned null (see SCREENSHOT_FAIL above for the code)")
            return@withContext null
        }
        val scaled = downscale(bitmap, MAX_IMAGE_DIM)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val scale = maxDim.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun rootWindows(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        service.rootInActiveWindow?.let { roots.add(it) }
        runCatching { service.windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { roots.add(it) }
        }
        return roots
    }

    /** One-line description of every retrievable window, for the debug trace. */
    private fun windowSummary(): String =
        runCatching { service.windows }.getOrNull().orEmpty().joinToString(", ") { window ->
            val pkg = runCatching { window.root?.packageName }.getOrNull() ?: "?"
            "type=${window.type} pkg=$pkg root=${window.root != null}"
        }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        val raw = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { "hint:$it" }
            ?: ""
        return raw.replace('\n', ' ').trim().take(MAX_LABEL)
    }

    companion object {
        private const val MAX_ELEMENTS = 120
        private const val MAX_VISIT = 4_000
        private const val MAX_LABEL = 60
        private const val SPARSE_INTERACTIVE = 3
        private const val SPARSE_TOTAL = 5
        private const val MAX_IMAGE_DIM = 1092
        private const val JPEG_QUALITY = 70
    }
}
