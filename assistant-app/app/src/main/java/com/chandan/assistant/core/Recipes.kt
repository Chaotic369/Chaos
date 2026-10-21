package com.chandan.assistant.core

import android.content.Context
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ai.AiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** One accessibility step using text/description labels, never fixed coordinates. kind: click | type */
data class RecipeStep(val kind: String, val labels: List<String>, val text: String = "")

object Recipes {
    suspend fun run(svc: AssistantAccessibilityService, ctx: Context, steps: List<RecipeStep>, ai: AiClient?): Boolean {
        delay(1000)
        for (s in steps) {
            var ok = doStep(svc, s)
            if (!ok && ai != null) {
                val what = "${s.kind} ${s.labels.joinToString(" or ")}" + if (s.text.isNotEmpty()) " (type the message)" else ""
                val fix = try {
                    ai.repairStep(what, svc.dumpTree())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (fix != null) {
                    ok = AgentActions.apply(ctx, svc, if (fix.action == "type") fix.copy(text = s.text) else fix, ai)
                }
            }
            if (!ok) return false
            delay(400)
        }
        return true
    }

    private suspend fun doStep(svc: AssistantAccessibilityService, s: RecipeStep): Boolean = when (s.kind) {
        "click" -> svc.waitForNode(s.labels, false, 8000)?.let { svc.click(it) } ?: false
        "type" -> (svc.waitForNode(s.labels, true, 8000) ?: svc.firstEditable())?.let { svc.setText(it, s.text) } ?: false
        else -> false
    }
}
