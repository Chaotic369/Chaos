package com.chandan.assistant.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Browser
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.chandan.assistant.core.AssistantService
import com.chandan.assistant.core.Bus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class AssistantAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: AssistantAccessibilityService? = null
    }

    @Volatile var changeCounter = 0L
    private var cachedTree: String? = null
    private var cachedAt = -1L
    @Volatile private var lastNodes: List<AccessibilityNodeInfo> = emptyList()
    private var bubble: View? = null
    private var controlBubble: TextView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        instance = this
        runCatching {
            serviceInfo = serviceInfo?.apply {
                flags = flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        }
        AssistantService.start(this)
        ensureControlBubble()
        serviceScope.launch {
            combine(Bus.continuousMode, Bus.listening, Bus.busy) { cont, list, bsy ->
                Triple(cont, list, bsy)
            }.collect { (cont, list, bsy) ->
                updateControlBubble(cont, list, bsy)
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                AssistantService.toggleContinuous(this)
            }
            return true
        }
        return super.onKeyEvent(event)
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
            removeControlBubble()
            return super.onUnbind(intent)
        }

        override fun onDestroy() {
            instance = null
            removeControlBubble()
            serviceScope.cancel()
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
                    if (nodes.size >= 90) return
                    val text = node.text?.toString().orEmpty().ifBlank { labelOf(node) }
                    val desc = node.contentDescription?.toString().orEmpty()
                    val id = node.viewIdResourceName?.substringAfter(":id/").orEmpty()
                    val interactive = node.isClickable || node.isEditable || node.isLongClickable || text.isNotBlank() || desc.isNotBlank()
                    if (node.isVisibleToUser && interactive) {
                        if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || node.isEditable) {
                            val r = Rect()
                            node.getBoundsInScreen(r)
                            if (r.width() > 0 && r.height() > 0) {
                                sb.append("[${nodes.size}] ").append(if (node.isEditable) "EDIT" else "BTN")
                                    .append(" text=\"").append(text.take(45)).append("\"")
                                if (desc.isNotBlank() && desc != text) sb.append(" desc=\"").append(desc.take(45)).append("\"")
                                if (id.isNotBlank()) sb.append(" id=").append(id)
                                sb.append(" ${r.left},${r.top},${r.right},${r.bottom}\n")
                                nodes.add(node)
                            }
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
            val r = Rect()
            n.getBoundsInScreen(r)
            return longTap(r.centerX().toFloat(), r.centerY().toFloat())
        }

        fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
        }

        fun imeEnter(): Boolean {
            if (Build.VERSION.SDK_INT >= 30) {
                val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (f != null && f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    return true
                }
            }
            val searchBtn = findByLabels(listOf("Search", "Go", "Send", "Done", "Enter"), false)
            if (searchBtn != null) return click(searchBtn)
            return false
        }

        fun scroll(forward: Boolean): Boolean {
            val root = rootInActiveWindow
            if (root != null) {
                fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (n.isScrollable) return n
                    for (i in 0 until n.childCount) {
                        val c = n.getChild(i) ?: continue
                        find(c)?.let { return it }
                    }
                    return null
                }
                val t = find(root)
                if (t != null && t.performAction(
                        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    )
                ) {
                    return true
                }
            }
            val dm = resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val startY = if (forward) dm.heightPixels * 0.75f else dm.heightPixels * 0.25f
            val endY = if (forward) dm.heightPixels * 0.25f else dm.heightPixels * 0.75f
            return swipe(cx, startY, cx, endY, 320L)
        }

        fun tap(x: Float, y: Float): Boolean {
            val dm = resources.displayMetrics
            val sx = x.coerceIn(1f, (dm.widthPixels - 1).toFloat())
            val sy = y.coerceIn(1f, (dm.heightPixels - 1).toFloat())
            val p = Path().apply { moveTo(sx, sy) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
            return runCatching { dispatchGesture(g, null, null) }.getOrDefault(false)
        }

        fun longTap(x: Float, y: Float, durationMs: Long = 750L): Boolean {
            val dm = resources.displayMetrics
            val sx = x.coerceIn(1f, (dm.widthPixels - 1).toFloat())
            val sy = y.coerceIn(1f, (dm.heightPixels - 1).toFloat())
            val p = Path().apply { moveTo(sx, sy) }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build()
            return runCatching { dispatchGesture(g, null, null) }.getOrDefault(false)
        }

        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 320L): Boolean {
            val dm = resources.displayMetrics
            val sx1 = x1.coerceIn(1f, (dm.widthPixels - 1).toFloat())
            val sy1 = y1.coerceIn(1f, (dm.heightPixels - 1).toFloat())
            val sx2 = x2.coerceIn(1f, (dm.widthPixels - 1).toFloat())
            val sy2 = y2.coerceIn(1f, (dm.heightPixels - 1).toFloat())
            val p = Path().apply {
                moveTo(sx1, sy1)
                lineTo(sx2, sy2)
            }
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, durationMs)).build()
            return runCatching { dispatchGesture(g, null, null) }.getOrDefault(false)
        }

        fun takeScreenshotNow(): Boolean {
            return if (Build.VERSION.SDK_INT >= 28) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else false
        }

        suspend fun openNewTabInBrowser(): Boolean {
            findByLabels(listOf("New tab", "new_tab_button"), false)?.let {
                if (click(it)) return true
            }
            val menu = findByLabels(listOf("More options", "menu_button", "Customize and control"), false)
            if (menu != null && click(menu)) {
                delay(500)
                val nt = waitForNode(listOf("New tab"), false, 2000)
                if (nt != null && click(nt)) return true
            }
            return runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    setPackage("com.android.chrome")
                    putExtra(Browser.EXTRA_CREATE_NEW_TAB, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                true
            }.getOrDefault(false)
        }

        suspend fun downloadCurrentImage(): Boolean {
            findByLabels(listOf("Download image", "Save image", "Download"), false)?.let {
                if (click(it)) return true
            }
            val dm = resources.displayMetrics
            var targetX = dm.widthPixels / 2f
            var targetY = dm.heightPixels * 0.45f
            rootInActiveWindow?.let { root ->
                var bestArea = 0
                fun findImg(n: AccessibilityNodeInfo) {
                    val cls = n.className?.toString().orEmpty()
                    if (n.isVisibleToUser && (cls.contains("Image", true) || cls.contains("WebView", true))) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        val area = r.width() * r.height()
                        if (cls.contains("Image", true) && area > bestArea && r.width() > 120 && r.height() > 120) {
                            bestArea = area
                            targetX = r.centerX().toFloat()
                            targetY = r.centerY().toFloat()
                        }
                    }
                    for (i in 0 until n.childCount) {
                        n.getChild(i)?.let { findImg(it) }
                    }
                }
                findImg(root)
            }
            longTap(targetX, targetY, 750L)
            delay(650)
            val dl = waitForNode(listOf("Download image", "Save image", "Download picture", "Download"), false, 2500)
            return if (dl != null) click(dl) else false
        }

        // ---------- Always-on Floating Control Overlay ----------

        private fun ensureControlBubble() {
            main.post {
                if (controlBubble != null) return@post
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val tv = TextView(this).apply {
                    text = "🎤 PTT"
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(36, 22, 36, 22)
                    background = GradientDrawable().apply {
                        setColor(0xD91E88E5.toInt())
                        cornerRadius = 80f
                    }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 20
                    y = 420
                }
                var downRawX = 0f
                var downRawY = 0f
                var startX = 0
                var startY = 0
                var moved = false
                var longPressed = false
                val longPressRunnable = Runnable {
                    if (!moved) {
                        longPressed = true
                        AssistantService.toggleContinuous(this@AssistantAccessibilityService)
                    }
                }
                tv.setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = ev.rawX
                            downRawY = ev.rawY
                            startX = lp.x
                            startY = lp.y
                            moved = false
                            longPressed = false
                            main.postDelayed(longPressRunnable, 600L)
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (downRawX - ev.rawX).toInt()
                            val dy = (ev.rawY - downRawY).toInt()
                            if (abs(dx) > 18 || abs(dy) > 18) {
                                moved = true
                                main.removeCallbacks(longPressRunnable)
                                lp.x = (startX + dx).coerceAtLeast(0)
                                lp.y = (startY + dy).coerceAtLeast(60)
                                runCatching { wm.updateViewLayout(tv, lp) }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            main.removeCallbacks(longPressRunnable)
                            if (!moved && !longPressed && ev.actionMasked == MotionEvent.ACTION_UP) {
                                when {
                                    Bus.busy.value -> Bus.stopAll()
                                    Bus.continuousMode.value -> AssistantService.toggleContinuous(this@AssistantAccessibilityService)
                                    else -> AssistantService.triggerPtt(this@AssistantAccessibilityService)
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                try {
                    wm.addView(tv, lp)
                    controlBubble = tv
                    controlParams = lp
                } catch (e: Exception) {
                    controlBubble = null
                }
            }
        }

        private fun updateControlBubble(continuous: Boolean, listening: Boolean, busy: Boolean) {
            main.post {
                if (controlBubble == null) ensureControlBubble()
                val tv = controlBubble ?: return@post
                val (label, color) = when {
                    busy -> "⏹ BUSY" to 0xE6D32F2F.toInt()
                    continuous && listening -> "🟢 ON 🎤" to 0xE62E7D32.toInt()
                    continuous -> "🟢 ON (Vol)" to 0xE6388E3C.toInt()
                    listening -> "🎤 Speak…" to 0xE6F57C00.toInt()
                    else -> "🎤 PTT" to 0xD91E88E5.toInt()
                }
                tv.text = label
                tv.background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = 80f
                }
            }
        }

        private fun removeControlBubble() {
            main.post {
                controlBubble?.let {
                    runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
                }
                controlBubble = null
            }
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
