package com.chandan.assistant.core

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ai.AgentStep
import com.chandan.assistant.core.ai.AiClient
import kotlinx.coroutines.delay

object AgentActions {
    fun describeStep(s: AgentStep): String = when (s.action) {
        "click" -> "tapping ${s.target}"
        "tap_visual" -> "finding ${s.target} on screen"
        "long_click" -> "long-pressing ${s.target}"
        "type" -> "typing \"${s.text.take(24)}\""
        "enter" -> "pressing enter"
        "scroll" -> "scrolling ${s.direction.ifBlank { "down" }}"
        "open_app" -> "opening ${s.target}"
        "wait" -> "waiting"
        else -> s.action
    }

    private fun resolve(svc: AssistantAccessibilityService, target: String, editable: Boolean): AccessibilityNodeInfo? {
        if (target.startsWith("#")) return svc.nodeAt(target.drop(1).trim().toIntOrNull() ?: -1)
        if (target.isBlank()) return null
        return svc.findByLabels(listOf(target), editable)
    }

    suspend fun apply(ctx: Context, svc: AssistantAccessibilityService, s: AgentStep, ai: AiClient? = null): Boolean = when (s.action) {
        "click" -> {
            val n = resolve(svc, s.target, false)
            if (n != null) svc.click(n)
            else if (ai != null && s.target.isNotBlank() && !s.target.startsWith("#")) Vision.tap(svc, ai, s.target)
            else false
        }
        "tap_visual" -> ai != null && Vision.tap(svc, ai, s.target)
        "long_click" -> resolve(svc, s.target, false)?.let { svc.longClick(it) } ?: false
        "type" -> (resolve(svc, s.target, true) ?: svc.firstEditable())?.let { svc.setText(it, s.text) } ?: false
        "enter" -> svc.imeEnter()
        "scroll" -> svc.scroll(s.direction != "up")
        "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "open_app" -> openApp(svc, s.target)
        "wait" -> {
            delay(1000)
            true
        }
        else -> false
    }

    private fun openApp(svc: AssistantAccessibilityService, name: String): Boolean {
        val pm = svc.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val best = pm.queryIntentActivities(i, 0)
            .map { it to Fuzzy.score(name, it.loadLabel(pm).toString()) }
            .filter { it.second >= 0.75 }.maxByOrNull { it.second } ?: return false
        val launch = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName) ?: return false
        svc.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
