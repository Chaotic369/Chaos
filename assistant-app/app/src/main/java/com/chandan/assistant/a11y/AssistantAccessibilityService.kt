package com.chandan.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.delay

class AssistantAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AssistantAccessibilityService? = null
    }

    @Volatile var changeCounter = 0L
    private var cachedTree: String? = null
    private var cachedAt = -1L
    @Volatile private var lastNodes: List<AccessibilityNodeInfo> = emptyList()
    private var bubble: View? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> changeCounter++
        }
        if (recording) record(event)
    }

    // ---------- teach mode recording ----------

    @Volatile var recording = false
    private val rec = mutableListOf<String>()
    private var pendingText: String? = null
    private var pendingField = ""
    private var lastPkg = ""

    fun startRecording() {
        synchronized(rec) {
            rec.clear()
            pendingText = null
            lastPkg = ""
        }
        recording = true
    }

    fun stopRecording(): List<String> {
        recording = false
        return synchronized(rec) {
            flushText()
            rec.toList()
        }
    }

    private fun flushText() {
        pendingText?.let { if (it.isNotEmpty()) rec.add("type text=\"$it\" field=\"$pendingField\"") }
        pendingText = null
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun record(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        synchronized(rec) {
            if (rec.size >= 200) return
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (pkg != lastPkg) {
                        flushText()
                        lastPkg = pkg
                        rec.add("app ${appLabel(pkg)} ($pkg)")
                    }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    flushText()
                    val n = e.source
                    val t = n?.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: n?.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        ?: (if (n != null) labelOf(n) else "")
                    val id = n?.viewIdResourceName?.substringAfter(":id/").orEmpty()
                    rec.add("click text=\"${t.take(40)}\" id=$id")
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    if (!e.isPassword) {
                        pendingText = e.text.joinToString("")
                        pendingField = e.source?.hintText?.toString().orEmpty()
                    }
                }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    flushText()
                    if (rec.lastOrNull() != "scroll") rec.add("scroll")
                }
            }
            Unit
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideStop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------- reading the screen ----------

    private fun labelOf(n: AccessibilityNodeInfo, depth: Int = 0): String {
        val t = n.text?.toString()?.takeIf { it.isNotBlank() }
            ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        if (t != null || depth >= 2) return t ?: ""
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val l = labelOf(c, depth + 1)
            if (l.isNotEmpty()) return l
        }
        return ""
    }

    /** Compact dump: only clickable/editable nodes. Cached while the screen is unchanged. */
    fun dumpTree(): String {
        val counter = changeCounter
        cachedTree?.let { if (cachedAt == counter) return it }
        val root = rootInActiveWindow ?: return "(no window)"
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        val sb = StringBuilder("app=${root.packageName}\n")
        fun walk(node: AccessibilityNodeInfo) {
            if (nodes.size >= 60) return
            if (node.isVisibleToUser && (node.isClickable || node.isEditable || node.isLongClickable)) {
                val text = node.text?.toString().orEmpty().ifBlank { labelOf(node) }
                val desc = node.contentDescription?.toString().orEmpty()
                val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
                if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || node.isEditable) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    sb.append("[${nodes.size}] ").append(if (node.isEditable) "EDIT" else "BTN")
                        .append(" text=\"").append(text.take(40)).append("\"")
                    if (desc.isNotBlank() && desc != text) sb.append(" desc=\"").append(desc.take(40)).append("\"")
                    if (id.isNotBlank()) sb.append(" id=").append(id)
                    sb.append(" ${r.left},${r.top},${r.right},${r.bottom}\n")
                    nodes.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        if (nodes.isEmpty()) sb.append("(no tappable nodes)\n")
        lastNodes = nodes
        val out = sb.toString()
        cachedTree = out
        cachedAt = counter
        return out
    }

    fun nodeAt(i: Int): AccessibilityNodeInfo? = lastNodes.getOrNull(i)

    fun findByLabels(labels: List<String>, editable: Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        for (label in labels) {
            var best: AccessibilityNodeInfo? = null
            var bestScore = 0
            fun walk(n: AccessibilityNodeInfo) {
                if (!editable || n.isEditable) {
                    val cands = listOfNotNull(
                        n.text?.toString(), n.contentDescription?.toString(),
                        n.hintText?.toString(), n.viewIdResourceName?.substringAfter(":id/")
                    )
                    var s = 0
                    for (c in cands) {
                        if (c.equals(label, true)) s = maxOf(s, 2)
                        else if (label.length >= 3 && c.contains(label, true)) s = maxOf(s, 1)
                    }
                    if (s > bestScore && n.isVisibleToUser) {
                        best = n
                        bestScore = s
                    }
                }
                for (i in 0 until n.childCount) {
                    val c = n.getChild(i) ?: continue
                    walk(c)
                }
            }
            walk(root)
            if (best != null) return best
        }
        return null
    }

    fun firstEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        fun walk(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c)?.let { return it }
            }
            return null
        }
        return walk(root)
    }

    suspend fun waitForNode(labels: List<String>, editable: Boolean, timeoutMs: Long): AccessibilityNodeInfo? {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            findByLabels(labels, editable)?.let { return it }
            delay(250)
        }
        return null
    }

    suspend fun waitForScreenChange(since: Long, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (changeCounter != since) return true
            delay(100)
        }
        return false
    }

    // ---------- acting on the screen ----------

    fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun longClick(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            cur = cur.parent
        }
        return false
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val b = Bundle()
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    fun imeEnter(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                find(c)?.let { return it }
            }
            return null
        }
        val t = find(root) ?: return false
        return t.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
        return dispatchGesture(g, null, null)
    }

    // ---------- STOP bubble ----------

    fun showStop(onStop: () -> Unit) = showBubble("STOP", 0xE6D32F2F.toInt(), onStop)

    fun showBubble(label: String, color: Int, onTap: () -> Unit) {
        main.post {
            removeBubble()
            val tv = TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(56, 28, 56, 28)
                background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = 90f
                }
                setOnClickListener { onTap() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 220
            }
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, lp)
                bubble = tv
            } catch (e: Exception) {
                bubble = null
            }
        }
    }

    private fun removeBubble() {
        bubble?.let {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (e: Exception) {
            }
        }
        bubble = null
    }

    fun hideStop() {
        main.post { removeBubble() }
    }
}
