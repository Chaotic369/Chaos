package com.chandan.assistant.core

import android.content.Intent
import android.net.Uri
import com.chandan.assistant.core.ai.GeminiClient
import com.chandan.assistant.core.ai.RouteResult
import com.chandan.assistant.data.AppDb
import android.content.Context
import kotlinx.coroutines.CancellationException

data class Routed(val action: Action, val route: String)

/** Fast path first, then AI. Caches AI routing for repeated commands. */
class CommandRouter(private val ctx: Context, private val db: AppDb, private val fast: FastRouter) {
    private val cache = object : LinkedHashMap<String, RouteResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RouteResult>?): Boolean = size > 50
    }

    suspend fun fastOnly(cmd: String): Action = fast.route(cmd)

    suspend fun route(cmd: String, apiKey: String, model: String): Routed {
        val f = fast.route(cmd)
        if (f !is Action.NoMatch) return Routed(f, "fast")
        if (apiKey.isBlank()) {
            return Routed(Action.Unknown("${f.reason} Add a Gemini API key in Settings to use AI routing."), "fast")
        }
        return try {
            val key = cmd.lowercase().trim()
            val rr = cache[key] ?: GeminiClient(apiKey, model)
                .routeCommand(
                    cmd, db.aliasDao().list().map { it.name }, db.nicknameDao().list().map { it.name },
                    db.skillDao().list().map { "${it.name}(${it.params})" }
                )
                .also { cache[key] = it }
            Routed(toAction(rr, cmd), "ai")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Routed(Action.Unknown(e.message ?: "AI failed"), "ai")
        }
    }

    private suspend fun toAction(rr: RouteResult, cmd: String): Action = when (rr.action) {
        "call" -> fast.resolveCall(rr.recipient)
        "open_app" -> fast.resolveOpen(rr.app.ifBlank { rr.text }).let { if (it is Action.NoMatch) Action.Agent(cmd) else it }
        "open_url" -> {
            val t = rr.text.ifBlank { rr.app }
            if (t.contains("://")) Action.OpenUrl(t, t)
            else if (t.startsWith("www.")) Action.OpenUrl("https://$t", t)
            else fast.resolveOpen(t)
        }
        "send_message", "send_email" -> messaging(rr, cmd)
        "set_alarm" -> alarm(rr)
        "screen_task" -> Action.Agent(if (rr.steps.isEmpty()) cmd else "$cmd. Plan: ${rr.steps.joinToString("; ")}")
        "system" -> SystemCommands.parse(rr.text)?.let { Action.SysAction(it) } ?: Action.Unknown("I can't do \"${rr.text}\" yet.")
        "run_skill" -> Action.RunSkill(
            rr.app.ifBlank { rr.text },
            rr.steps.mapNotNull { s ->
                val i = s.indexOf('=')
                if (i > 0) s.substring(0, i).trim() to s.substring(i + 1).trim() else null
            }.toMap()
        )
        "create_trigger" -> Action.Unknown("Add triggers in the Triggers tab.")
        else -> Action.Unknown("I didn't understand that.")
    }

    private fun alarm(rr: RouteResult): Action {
        val p = rr.text.trim().split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull()
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        if (h == null || h !in 0..23 || m !in 0..59) return Action.Unknown("I couldn't read the alarm time.")
        return Action.Alarm(h, m, rr.subject.ifBlank { "Alarm" })
    }

    private fun digits(ph: String): String {
        val d = ph.filter { it.isDigit() }
        return when {
            d.length == 10 -> "91$d"
            d.length == 11 && d.startsWith("0") -> "91" + d.drop(1)
            else -> d
        }
    }

    private suspend fun messaging(rr: RouteResult, cmd: String): Action {
        val isEmail = rr.action == "send_email"
        val appName = rr.app.ifBlank { if (isEmail) "gmail" else "sms" }
        val prof = Profiles.find(appName)
            ?: return Action.Agent(cmd)
        val person = db.aliasDao().list().map { it to Fuzzy.score(rr.recipient, it.name) }
            .filter { it.second >= 0.72 }.maxByOrNull { it.second }?.first
        val label = person?.name ?: rr.recipient
        val text = rr.text
        val enc = Uri.encode(text)
        suspend fun phone(): String? = person?.phone?.takeIf { it.isNotBlank() } ?: fast.phoneFor(label)
        return when (prof.id) {
            "whatsapp" -> {
                val ph = phone() ?: return Action.Unknown("No phone number for $label. Add an alias.")
                Action.Launch(Intent.ACTION_VIEW, "https://wa.me/${digits(ph)}?text=$enc", null, emptyMap(), null, "WhatsApp chat with $label", "Tap Send.", listOf(RecipeStep("click", listOf("Send"))))
            }
            "telegram" -> {
                val user = person?.telegram?.removePrefix("@")?.takeIf { it.isNotBlank() }
                if (user != null) {
                    Action.Launch(Intent.ACTION_VIEW, "tg://resolve?domain=$user", null, emptyMap(), text, "Telegram chat with $label", "Message copied. Paste and send.", listOf(RecipeStep("type", listOf("Message"), text), RecipeStep("click", listOf("Send"))))
                } else {
                    Action.Launch(Intent.ACTION_VIEW, "tg://msg?text=$enc", null, emptyMap(), null, "Telegram", "Pick $label, then tap Send.")
                }
            }
            "instagram" -> {
                val h = person?.instagram?.removePrefix("@")?.takeIf { it.isNotBlank() }
                    ?: return Action.Unknown("No Instagram handle for $label. Add it to the alias.")
                Action.Launch(Intent.ACTION_VIEW, "https://www.instagram.com/_u/$h", "com.instagram.android", emptyMap(), text, "Instagram profile of $label", "Message copied. Tap Message, paste, send.", listOf(RecipeStep("click", listOf("Message")), RecipeStep("type", listOf("Message", "Message..."), text), RecipeStep("click", listOf("Send"))))
            }
            "gmail" -> {
                val to = person?.email?.takeIf { it.isNotBlank() } ?: rr.recipient.takeIf { it.contains("@") }
                    ?: return Action.Unknown("No email address for $label. Add it to the alias.")
                val uri = "mailto:${Uri.encode(to)}?subject=${Uri.encode(rr.subject)}&body=$enc"
                Action.Launch(Intent.ACTION_SENDTO, uri, "com.google.android.gm", emptyMap(), null, "Gmail to $label", "Tap Send.", listOf(RecipeStep("click", listOf("Send"))))
            }
            "sms" -> {
                val ph = phone() ?: return Action.Unknown("No phone number for $label. Add an alias.")
                Action.Launch(Intent.ACTION_SENDTO, "smsto:${Uri.encode(ph)}", null, mapOf("sms_body" to text), null, "SMS to $label", "Tap Send.", listOf(RecipeStep("click", listOf("Send SMS", "Send"))))
            }
            else -> Action.Unknown("${prof.appName} can't be messaged yet.")
        }
    }
}
