package com.chandan.assistant.core

import android.content.Context
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ai.AgentStep
import com.chandan.assistant.core.ai.AiClient
import com.chandan.assistant.data.AppDb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val SkillJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

fun stepsJson(steps: List<AgentStep>): String = SkillJson.encodeToString(ListSerializer(AgentStep.serializer()), steps)

fun parseSteps(json: String): List<AgentStep> = try {
    SkillJson.decodeFromString(ListSerializer(AgentStep.serializer()), json)
} catch (e: Exception) {
    emptyList()
}

/** Replays a taught skill. Failed steps get one AI repair; repaired steps are saved back. Skills can chain skills. */
class SkillRunner(
    private val ctx: Context,
    private val db: AppDb,
    private val ai: AiClient?,
    private val status: (String) -> Unit
) {
    private fun sub(s: String, args: Map<String, String>): String =
        args.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v) }

    private fun parseArgs(t: String): Map<String, String> =
        t.split(";").mapNotNull { p ->
            val i = p.indexOf('=')
            if (i > 0) p.substring(0, i).trim() to p.substring(i + 1).trim() else null
        }.toMap()

    suspend fun run(name: String, args: Map<String, String>, depth: Int = 0): Outcome {
        if (depth > 5) return Outcome(false, "Skill chain is too deep")
        val skill = db.skillDao().list()
            .map { it to Fuzzy.score(name, it.name.replace('_', ' ')) }
            .filter { it.second >= 0.85 }.maxByOrNull { it.second }?.first
            ?: return Outcome(false, "No skill named \"$name\"")
        val svc = AssistantAccessibilityService.instance
            ?: return Outcome(false, "Turn on the accessibility service first.")
        val steps = parseSteps(skill.steps).toMutableList()
        if (steps.isEmpty()) return Outcome(false, "Skill ${skill.name} has no steps")
        var repaired = false
        for ((i, st) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val real = st.copy(target = sub(st.target, args), text = sub(st.text, args))
            status("${skill.name} ${i + 1}/${steps.size}: ${AgentActions.describeStep(real)}")
            if (real.action == "run_skill") {
                val o = run(real.target, parseArgs(real.text) + args, depth + 1)
                if (!o.ok) return o
                continue
            }
            val before = svc.changeCounter
            var ok = AgentActions.apply(ctx, svc, real, ai)
            if (ok && real.action in setOf("click", "long_click", "type", "enter", "scroll")) {
                svc.waitForScreenChange(before, 1500)
            }
            if (ok && real.action == "open_app") delay(1500)
            if (!ok && ai != null) {
                val fix = try {
                    ai.repairStep("step ${i + 1} of skill ${skill.name}: ${AgentActions.describeStep(real)}", svc.dumpTree())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (fix != null) {
                    val f = if (fix.action == "type") fix.copy(text = real.text) else fix
                    ok = AgentActions.apply(ctx, svc, f, ai)
                    if (ok) {
                        steps[i] = if (fix.action == "type") fix.copy(text = st.text) else fix
                        repaired = true
                    }
                }
            }
            if (!ok) return Outcome(false, "Skill ${skill.name} failed at step ${i + 1}: ${AgentActions.describeStep(real)}")
            delay(300)
        }
        if (repaired) db.skillDao().upsert(skill.copy(steps = stepsJson(steps)))
        return Outcome(true, "Finished ${skill.name}" + if (repaired) " (repaired a step)" else "")
    }
}
