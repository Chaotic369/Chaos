package com.chandan.assistant.core

import android.content.Context
import android.os.SystemClock
import com.chandan.assistant.core.ai.GeminiClient
import com.chandan.assistant.data.AppDb
import com.chandan.assistant.data.LogEntry
import com.chandan.assistant.data.Prefs
import kotlinx.coroutines.delay

/** Headless version of the Home screen pipeline. Used by triggers and the background service. */
class CommandRunner(private val ctx: Context) {
    private val db = AppDb.get(ctx)
    private val prefs = Prefs(ctx)
    private val executor = Executor(ctx)
    private val router = CommandRouter(ctx, db, FastRouter(ctx, db))

    private fun ai(): GeminiClient? = prefs.apiKey.takeIf { it.isNotBlank() }?.let { GeminiClient(it, prefs.model) }

    suspend fun run(cmd: String, status: (String) -> Unit, speak: (String) -> Unit): Outcome {
        executor.aiProvider = { ai() }
        val split = cmd.split(",", "।").map { it.trim() }.filter { it.isNotEmpty() }
        val parts = if (split.size > 1 && split.any { router.fastOnly(it) is Action.NoMatch }) listOf(cmd) else split
        var last = Outcome(false, "Nothing to do")
        for ((i, p) in parts.withIndex()) {
            val t0 = SystemClock.elapsedRealtime()
            val a = router.route(p, prefs.apiKey, prefs.model).action
            if (a is Action.Stop) return Outcome(true, "Stopped")
            status(describe(a))
            val out = when (a) {
                is Action.Agent -> ai()?.let { AgentRunner(ctx, it, status).run(a.goal) } ?: Outcome(false, "Needs a Gemini key")
                is Action.RunSkill -> SkillRunner(ctx, db, ai(), status).run(a.skillName, a.args)
                is Action.TeachStart, is Action.TeachDone -> Outcome(false, "Teach mode runs from the app")
                else -> executor.run(a)
            }
            db.logDao().insert(
                LogEntry(
                    time = System.currentTimeMillis(), command = p, route = "trigger",
                    action = describe(a), success = out.ok, ms = SystemClock.elapsedRealtime() - t0, detail = out.message
                )
            )
            speak(out.message)
            last = out
            if (!out.ok) break
            if (i < parts.lastIndex) delay(1800)
        }
        return last
    }
}
