package com.agentchat.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * High-level, text/viewId-based automation primitives built on top of the live
 * [AccessibilityService]. Nodes are always targeted by semantics (text, view id,
 * content description) — never screen coordinates — so flows survive layout and
 * density changes.
 *
 * All waits poll [AccessibilityService.getRootInActiveWindow] on a fixed cadence
 * until a deadline, which is far more robust than relying on a single event.
 */
class UiDriver(private val service: AccessibilityService) {

    private val root: AccessibilityNodeInfo?
        get() = service.rootInActiveWindow

    val currentPackage: String?
        get() = root?.packageName?.toString()

    /**
     * Roots of every retrievable window, active window first. Apps like Uber put
     * their bottom-sheet content (fares, buttons) in a separate window from the
     * focused one, so semantic searches must span all windows to be reliable.
     */
    private fun rootWindows(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        service.rootInActiveWindow?.let { roots.add(it) }
        runCatching { service.windows }.getOrNull()?.forEach { window ->
            runCatching { window.root }.getOrNull()?.let { roots.add(it) }
        }
        return roots
    }

    // ---- App launching -------------------------------------------------------

    suspend fun launchApp(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext false
        // NEW_TASK only, so an already-running app resumes on its current screen
        // instead of being reset to its home screen.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { service.startActivity(intent) }.isSuccess
    }

    suspend fun launchDeepLink(uri: String, packageName: String? = null): Boolean =
        withContext(Dispatchers.Main) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName != null) setPackage(packageName)
            }
            runCatching { service.startActivity(intent) }.isSuccess
        }

    // ---- Global actions ------------------------------------------------------

    fun pressBack(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)

    // ---- Waiting -------------------------------------------------------------

    /** Polls [block] until it returns non-null or [timeoutMs] elapses. */
    suspend fun <T> awaitResult(
        timeoutMs: Long,
        pollMs: Long = 150,
        block: () -> T?,
    ): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val result = runCatching { block() }.getOrNull()
            if (result != null) return result
            delay(pollMs)
        }
        return null
    }

    /** Waits until [packageName] owns the active window (i.e. its screen loaded). */
    suspend fun awaitPackage(packageName: String, timeoutMs: Long = 8_000): Boolean =
        awaitResult(timeoutMs) { if (currentPackage == packageName) true else null } ?: false

    suspend fun awaitNodeByText(
        text: String,
        timeoutMs: Long = 8_000,
        exact: Boolean = false,
    ): AccessibilityNodeInfo? = awaitResult(timeoutMs) { findByText(text, exact) }

    suspend fun awaitNodeByViewId(
        viewId: String,
        timeoutMs: Long = 8_000,
    ): AccessibilityNodeInfo? = awaitResult(timeoutMs) { findByViewId(viewId) }

    suspend fun awaitNode(
        timeoutMs: Long = 8_000,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? = awaitResult(timeoutMs) { findNode(predicate) }

    // ---- Finding -------------------------------------------------------------

    /** Finds a node whose text or content-description matches [text]. */
    fun findByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        var firstAny: AccessibilityNodeInfo? = null
        for (r in rootWindows()) {
            val matches = r.findAccessibilityNodeInfosByText(text) ?: continue
            for (node in matches) {
                val isMatch = !exact || nodeText(node).equals(text, ignoreCase = true)
                if (!isMatch) continue
                if (node.isVisibleToUser) return node
                if (firstAny == null) firstAny = node
            }
        }
        return firstAny
    }

    fun findByViewId(viewId: String): AccessibilityNodeInfo? {
        var firstAny: AccessibilityNodeInfo? = null
        for (r in rootWindows()) {
            val matches = r.findAccessibilityNodeInfosByViewId(viewId) ?: continue
            for (node in matches) {
                if (node.isVisibleToUser) return node
                if (firstAny == null) firstAny = node
            }
        }
        return firstAny
    }

    fun findByContentDescription(desc: String, exact: Boolean = false): AccessibilityNodeInfo? =
        findNode { node ->
            val cd = node.contentDescription?.toString() ?: return@findNode false
            if (exact) cd.equals(desc, ignoreCase = true) else cd.contains(desc, ignoreCase = true)
        }

    /** Breadth-first search over all windows for the first matching node. */
    fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(rootWindows())
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (runCatching { predicate(node) }.getOrDefault(false)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** Finds an editable (EditText-like) node, optionally scoped by view id. */
    fun findEditable(viewId: String? = null): AccessibilityNodeInfo? {
        if (viewId != null) findByViewId(viewId)?.let { return it }
        return findNode { it.isEditable }
    }

    // ---- Acting --------------------------------------------------------------

    fun click(node: AccessibilityNodeInfo?): Boolean {
        var n = node
        while (n != null) {
            if (n.isClickable && n.isEnabled) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n.parent
        }
        // Fall back to clicking the node itself even if not flagged clickable.
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun setText(node: AccessibilityNodeInfo?, value: String): Boolean {
        val target = node ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value,
            )
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Submits the current text field (keyboard "enter"/"search"). API 30+. */
    fun pressImeAction(node: AccessibilityNodeInfo?): Boolean {
        val target = node ?: findNode { it.isEditable && it.isFocused } ?: findNode { it.isEditable }
        ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return target.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
            )
        }
        return false
    }

    // ---- Gestures (coordinate-based, for custom-rendered screens) -------------

    suspend fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // Strokes shorter than the system tap timeout are silently dropped on
        // some devices (see CTS GestureUtils) — never go below it.
        val duration = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(100L)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Deepest (smallest) enabled clickable node whose screen bounds contain the
     * point. Lets coordinate taps go through performAction(ACTION_CLICK), which
     * works even where gesture injection is unreliable.
     */
    fun clickableNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addAll(rootWindows())
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (!node.isVisibleToUser || !node.isClickable || !node.isEnabled) continue
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (!bounds.contains(x, y)) continue
            val area = bounds.width().toLong() * bounds.height().toLong()
            if (area in 1 until bestArea) {
                best = node
                bestArea = area
            }
        }
        return best
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return dispatch(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val ok = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(description: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(description: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                if (!ok && cont.isActive) cont.resume(false)
            }
        }

    /** Scrolls the first scrollable ancestor/descendant forward one page. */
    fun scrollForward(): Boolean {
        val scrollable = findNode { it.isScrollable } ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    // ---- Debug ---------------------------------------------------------------

    /** Serializes every window's node tree for the engine self-test. */
    fun dumpActiveWindow(maxNodes: Int = 80): String {
        val roots = rootWindows()
        if (roots.isEmpty()) return "No windows (is a screen visible?)"
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            val indent = "  ".repeat(depth)
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val txt = node.text?.toString()?.take(30)
            val desc = node.contentDescription?.toString()?.take(30)
            val id = node.viewIdResourceName?.substringAfterLast('/')
            val flags = buildString {
                if (node.isClickable) append("C")
                if (node.isEditable) append("E")
                if (node.isScrollable) append("S")
            }
            sb.append(indent).append(cls)
            if (!txt.isNullOrEmpty()) sb.append(" \"").append(txt).append('"')
            if (!desc.isNullOrEmpty()) sb.append(" desc=").append(desc)
            if (!id.isNullOrEmpty()) sb.append(" #").append(id)
            if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
            sb.append('\n')
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        roots.forEachIndexed { index, r ->
            if (count >= maxNodes) return@forEachIndexed
            sb.append("── window ").append(index)
                .append(" (").append(r.packageName).append(") ──\n")
            walk(r, 0)
        }
        if (count >= maxNodes) sb.append("… (truncated at ").append(maxNodes).append(" nodes)")
        return sb.toString()
    }

    private fun nodeText(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString() ?: ""

    companion object {
        private const val MAX_NODES = 3_000
    }
}
