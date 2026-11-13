package com.hangge.rootpilot.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.hangge.rootpilot.BuildConfig

/**
 * Optional accessibility bridge for the loopback Agent.
 *
 * It does two things the rooted `uiautomator dump` path cannot do well: read the node tree in a few
 * milliseconds instead of seconds, and click a node by identity instead of by blind coordinates.
 * Everything here is read-only unless the caller explicitly asks for an action through the Agent
 * API, and the same Agent-control / SafeMode gates apply there.
 *
 * The emitted XML intentionally mirrors `uiautomator dump` so the desktop plugin parses it without
 * changes; one extra attribute, `node-index`, gives every node a stable address for tapping.
 */
class RootPilotAccessibilityService : AccessibilityService() {

    @Volatile
    private var lastTree: List<AccessibilityNodeInfo> = emptyList()

    @Volatile
    private var lastTreeAt = 0L

    @Volatile
    var lastPackage: String? = null
        private set

    override fun onServiceConnected() {
        instance = this
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) lastPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            connected = false
            lastTree = emptyList()
        }
        super.onDestroy()
    }

    /** Full window tree as uiautomator-compatible XML, or null when no window is available. */
    fun dumpTreeXml(): String? {
        val root = pickRoot() ?: return null
        val nodes = ArrayList<AccessibilityNodeInfo>(128)
        val builder = StringBuilder(16 * 1024)
        builder.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">")
        runCatching {
            appendNode(root, builder, 0, 0, nodes)
        }.onFailure {
            nodes.clear()
        }
        builder.append("</hierarchy>")
        lastTree = nodes
        lastTreeAt = System.currentTimeMillis()
        lastPackage = runCatching { root.packageName?.toString() }.getOrNull() ?: lastPackage
        return builder.toString()
    }

    /**
     * Some apps hand back a contentless "ghost" root and the active window may even be a system
     * overlay (status bar, IME). Prefer the largest *application* window, focused ones first, so a
     * chat window beats a 1240x113 status bar; dialogs still win because they take focus.
     */
    private fun pickRoot(): AccessibilityNodeInfo? {
        if (BuildConfig.DEBUG) logWindowCandidates()
        val appWindows = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { window ->
                runCatching { window.root }.getOrNull()?.let { root -> window to root }
            }
            .sortedWith(
                compareByDescending<Pair<AccessibilityWindowInfo, AccessibilityNodeInfo>> { it.first.isFocused }
                    .thenByDescending { (_, root) -> areaOf(root) },
            )
            .map { it.second }

        appWindows.firstOrNull { it.childCount > 0 }?.let { return it }
        appWindows.firstOrNull()?.let { return it }

        runCatching { rootInActiveWindow }.getOrNull()?.let { return it }
        return runCatching { windows }.getOrNull().orEmpty()
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .maxByOrNull(::areaOf)
    }

    /** Debug-build only: which windows exist, and whether each one exposes a real node tree. */
    private fun logWindowCandidates() {
        runCatching { windows }.getOrNull()?.forEach { window ->
            val root = runCatching { window.root }.getOrNull()
            val bounds = Rect().also { window.getBoundsInScreen(it) }
            android.util.Log.d(
                "RootPilotA11y",
                "window type=${window.type} focused=${window.isFocused} bounds=$bounds " +
                    "rootPkg=${root?.packageName} children=${root?.childCount} rootClass=${root?.className}",
            )
        }
    }

    private fun areaOf(node: AccessibilityNodeInfo): Long {
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        return bounds.width().toLong() * bounds.height().toLong()
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        index: Int,
        collected: MutableList<AccessibilityNodeInfo>,
    ) {
        if (collected.size >= MAX_NODES || depth > MAX_DEPTH) return
        val globalIndex = collected.size
        collected.add(node)

        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val drawn = runCatching { node.isVisibleToUser }.getOrDefault(true)

        out.append("<node")
        appendAttribute(out, "index", index.toString())
        appendAttribute(out, "text", node.text?.toString().orEmpty())
        appendAttribute(out, "resource-id", node.viewIdResourceName.orEmpty())
        appendAttribute(out, "class", node.className?.toString().orEmpty())
        appendAttribute(out, "package", node.packageName?.toString().orEmpty())
        appendAttribute(out, "content-desc", node.contentDescription?.toString().orEmpty())
        appendAttribute(out, "checkable", node.isCheckable.toString())
        appendAttribute(
            out,
            "checked",
            runCatching { node.isChecked }.getOrDefault(false).toString(),
        )
        appendAttribute(out, "clickable", node.isClickable.toString())
        appendAttribute(out, "enabled", node.isEnabled.toString())
        appendAttribute(out, "focusable", node.isFocusable.toString())
        appendAttribute(out, "focused", node.isFocused.toString())
        appendAttribute(out, "scrollable", node.isScrollable.toString())
        appendAttribute(out, "long-clickable", node.isLongClickable.toString())
        appendAttribute(out, "password", node.isPassword.toString())
        appendAttribute(out, "selected", node.isSelected.toString())
        appendAttribute(out, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        appendAttribute(out, "node-index", globalIndex.toString())
        if (!drawn) appendAttribute(out, "visible-to-user", "false")
        out.append(">")

        for (childIndex in 0 until node.childCount) {
            val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
            appendNode(child, out, depth + 1, childIndex, collected)
        }
        out.append("</node>")
    }

    private fun appendAttribute(out: StringBuilder, name: String, value: String) {
        out.append(' ').append(name).append("=\"").append(escapeXml(value)).append('"')
    }

    private fun escapeXml(value: String): String {
        if (value.none { it == '<' || it == '>' || it == '&' || it == '"' || it == '\'' || it < ' ' }) {
            return value
        }
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                '\t' -> sb.append("&#9;")
                else -> if (ch < ' ') sb.append(' ') else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Click the node with the given `node-index`. Prefers the node's own ACTION_CLICK and walks up
     * to the nearest clickable ancestor, which is what a user's finger effectively does.
     */
    fun clickNode(nodeIndex: Int): Boolean {
        val node = nodeAt(nodeIndex) ?: return false
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val candidate = current ?: return@repeat
            if (runCatching { candidate.isClickable }.getOrDefault(false)) {
                val clicked = runCatching {
                    candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) return true
            }
            current = runCatching { candidate.parent }.getOrNull()
        }
        // Nothing accepted the click: fall back to a real gesture at the node's centre.
        return tapAt(nodeBoundsCenter(node) ?: return false)
    }

    fun tapAt(point: Pair<Float, Float>): Boolean {
        val path = Path().apply { moveTo(point.first, point.second) }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build(),
            null,
            null,
        )
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatchGesture(
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0,
                        durationMs.coerceIn(50, 3_000).toLong(),
                    ),
                )
                .build(),
            null,
            null,
        )
    }

    fun nodeCenter(nodeIndex: Int): Pair<Float, Float>? = nodeAt(nodeIndex)?.let(::nodeBoundsCenter)

    /**
     * Geometry of the current windows. This is how an Agent learns that a tap would land on the
     * keyboard: the IME window sits on top of the app and swallows touches in its band.
     */
    fun snapshotWindows(): List<WindowSnapshot> =
        runCatching { windows }.getOrNull().orEmpty().map { window ->
            val bounds = Rect().also { runCatching { window.getBoundsInScreen(it) } }
            val root = runCatching { window.root }.getOrNull()
            WindowSnapshot(
                type = window.type,
                typeName = windowTypeName(window.type),
                packageName = runCatching { root?.packageName?.toString() }.getOrNull(),
                boundsLeft = bounds.left,
                boundsTop = bounds.top,
                boundsRight = bounds.right,
                boundsBottom = bounds.bottom,
                focused = window.isFocused,
                active = window.isActive,
            )
        }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input-method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility-overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split-screen-divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification-overlay"
        else -> "type-$type"
    }

    /**
     * Write text straight into the focused editable field with ACTION_SET_TEXT.
     *
     * This is the reason the Agent does not need the IME or `input text`: no key events are
     * synthesised, so Chinese and emoji work, spaces need no `%s` escaping, and nothing is parsed
     * by a shell. Returns false when no editable field is present, and the caller falls back.
     */
    fun setText(text: String): Boolean {
        val root = pickRoot() ?: return false
        val target = findEditable(root, requireFocused = true, depth = 0)
            ?: findEditable(root, requireFocused = false, depth = 0)
            ?: return false
        return applyText(target, text)
    }

    /** Same as [setText] but for one specific node, e.g. a field that does not hold focus yet. */
    fun setTextAt(nodeIndex: Int, text: String): Boolean {
        val node = nodeAt(nodeIndex) ?: return false
        return applyText(node, text)
    }

    private fun applyText(node: AccessibilityNodeInfo, text: String): Boolean {
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (!editable) return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }.getOrDefault(false)
    }

    private fun findEditable(
        node: AccessibilityNodeInfo,
        requireFocused: Boolean,
        depth: Int,
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        if (editable && (!requireFocused || runCatching { node.isFocused }.getOrDefault(false))) {
            return node
        }
        for (childIndex in 0 until node.childCount) {
            val child = runCatching { node.getChild(childIndex) }.getOrNull() ?: continue
            findEditable(child, requireFocused, depth + 1)?.let { return it }
        }
        return null
    }

    private fun nodeBoundsCenter(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    private fun nodeAt(nodeIndex: Int): AccessibilityNodeInfo? {
        if (nodeIndex < 0) return null
        val fresh = if (System.currentTimeMillis() - lastTreeAt > TREE_TTL_MS) {
            dumpTreeXml()
            lastTree
        } else {
            lastTree
        }
        return fresh.getOrNull(nodeIndex)?.takeIf { node ->
            runCatching { node.refresh() }.getOrDefault(true)
        }
    }

    companion object {
        @Volatile
        var instance: RootPilotAccessibilityService? = null
            private set
        @Volatile
        var connected: Boolean = false
            private set

        fun isConnected(): Boolean = connected && instance != null

        private const val MAX_NODES = 2_000
        private const val MAX_DEPTH = 40
        private const val MAX_CLICK_ANCESTORS = 6
        private const val TREE_TTL_MS = 1_500L
        private const val TAP_DURATION_MS = 40L
    }
}

/** One window's screen geometry, as reported by the accessibility service. */
data class WindowSnapshot(
    val type: Int,
    val typeName: String,
    val packageName: String?,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val focused: Boolean,
    val active: Boolean,
)
