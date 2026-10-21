package com.chandan.assistant.core

import android.content.Context
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ai.AiClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** observe screen -> ask AI for ONE step -> execute -> verify screen changed -> repeat. */
class AgentRunner(private val ctx: Context, private val ai: AiClient, private val status: (String) -> Unit) {
    private val maxSteps = 20

    suspend fun run(goal: String): Outcome {
        val svc = AssistantAccessibilityService.instance
            ?: return Outcome(false, "Turn on the Assistant accessibility service first (Settings > Permissions).")
        val history = mutableListOf<String>()
        var failures = 0
        for (n in 1..maxSteps) {
            currentCoroutineContext().ensureActive()
            status("Step $n/$maxSteps: looking at screen")
            val before = svc.changeCounter
            val screen = svc.dumpTree()
            val s = ai.nextAgentStep(goal, history, screen)
            currentCoroutineContext().ensureActive()
            when (s.action) {
                "done" -> return Outcome(true, s.message.ifBlank { "Done" })
                "ask_user" -> return Outcome(false, s.message.ifBlank { "I need your input" })
                "fail" -> return Outcome(false, s.message.ifBlank { "I couldn't do that" })
            }
            val desc = AgentActions.describeStep(s)
            status("Step $n/$maxSteps: $desc")
            val ok = AgentActions.apply(ctx, svc, s, ai)
            val moved = when {
                !ok -> false
                s.action == "wait" || s.action == "open_app" -> {
                    delay(1200)
                    true
                }
                else -> svc.waitForScreenChange(before, 1800)
            }
            if (ok && moved) {
                failures = 0
                history.add("$n. $desc (ok)")
            } else {
                failures++
                history.add("$n. $desc (${if (ok) "no screen change" else "failed"}; try something different)")
                if (failures >= 2) return Outcome(false, "Stuck after: $desc. What should I do?")
            }
        }
        return Outcome(false, "Reached the $maxSteps step limit. Tell me what to do next.")
    }
}
