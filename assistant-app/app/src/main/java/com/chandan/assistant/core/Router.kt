package com.chandan.assistant.core

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.chandan.assistant.data.AppDb
import com.chandan.assistant.data.Nickname
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class Action {
    data class Call(val number: String, val label: String) : Action()
    data class OpenApp(val pkg: String, val label: String) : Action()
    data class OpenUrl(val url: String, val label: String) : Action()
    data class Launch(
        val verb: String, val uri: String, val pkg: String?, val extras: Map<String, String>,
        val clip: String?, val label: String, val note: String,
        val recipe: List<RecipeStep> = emptyList()
    ) : Action()
    data class Agent(val goal: String) : Action()
    data class RunSkill(val skillName: String, val args: Map<String, String>) : Action()
    data class SysAction(val cmd: SysCmd) : Action()
    object ReadNotifications : Action()
    data class ReplyNotif(val text: String) : Action()
    data class TeachStart(val nameHint: String) : Action()
    object TeachDone : Action()
    data class Alarm(val hour: Int, val minute: Int, val label: String) : Action()
    object Stop : Action()
    /** Fast path found nothing; fall through to AI. */
    data class NoMatch(val reason: String) : Action()
    data class Unknown(val reason: String) : Action()
}

fun describe(a: Action): String = when (a) {
    is Action.Call -> "call ${a.label}"
    is Action.OpenApp -> "open app ${a.label}"
    is Action.OpenUrl -> "open ${a.label}"
    is Action.Launch -> "open ${a.label}"
    is Action.Alarm -> "alarm ${a.hour}:${a.minute}"
    is Action.Agent -> "agent task"
    is Action.RunSkill -> "skill ${a.skillName}"
    is Action.SysAction -> "system ${a.cmd.kind}"
    is Action.ReadNotifications -> "read notifications"
    is Action.ReplyNotif -> "reply"
    is Action.TeachStart -> "teach"
    is Action.TeachDone -> "teach done"
    is Action.Stop -> "stop"
    is Action.NoMatch -> "no match"
    is Action.Unknown -> "unknown"
}

/** Tier 1: no AI, no network. Handles call / open / nicknames. */
class FastRouter(private val ctx: Context, private val db: AppDb) {
    private val ic = RegexOption.IGNORE_CASE
    private val callA = Regex("^(?:please\\s+)?(?:call|phone|dial)\\s+(.+)$", ic)
    private val callB = Regex("^(.+?)\\s+(?:ko\\s+)?(?:call|phone)(?:\\s+(?:karo|kar|kijiye|laga|lagao))?$", ic)
    private val openA = Regex("^(?:please\\s+)?(?:open|launch|start|khol|kholo)\\s+(.+)$", ic)
        private val openB = Regex("^(.+?)\\s+(?:open|khol|kholo)(?:\\s+karo)?$", ic)
        private val chromeSearchA = Regex("^(?:please\\s+)?(?:open\\s+chrome\\s+and\\s+)?(?:search|google|find|look\\s+up)\\s+(?:for\\s+)?(.+?)(?:\\s+(?:on|in)\\s+(?:google\\s+)?chrome|\\s+(?:on|in)\\s+google)?$", ic)
        private val chromeSearchB = Regex("^(?:please\\s+)?(?:on|in)\\s+(?:google\\s+)?chrome\\s+(?:search|find|look\\s+up)\\s+(?:for\\s+)?(.+)$", ic)
        private val stopWords = setOf("stop", "ruko", "ruk jao", "band karo", "cancel", "रुको", "बंद करो")

        private val teachRe = Regex("^(?:learn this|teach mode|learn karo)(?:\\s+(?:as|named|call it)\\s+(.+))?$", ic)
        private val doneWords = setOf("done", "finished", "finish", "save skill", "ho gaya", "ho gya", "khatam")
        private val skillPrefixes = setOf("run", "do", "start", "execute", "chalao", "chalu")
        private val notifRe = Regex("^(?:read|check|show|what are)?\\s*(?:my\\s+)?(?:new\\s+)?notifications?$", ic)
        private val replyRe = Regex("^(?:reply|jawab)\\s+(.+)$", ic)

        suspend fun route(raw: String): Action {
            val t = raw.trim()
            if (t.lowercase() in stopWords) return Action.Stop
            teachRe.matchEntire(t)?.let { return Action.TeachStart(it.groupValues[1].trim()) }
            if (t.lowercase() in doneWords) return Action.TeachDone
            val sk = matchSkill(t)
            if (sk != null) return sk
            if (notifRe.matches(t)) return Action.ReadNotifications
            replyRe.matchEntire(t)?.let { return Action.ReplyNotif(it.groupValues[1].trim()) }
            if (t.split(" ").size <= 14) SystemCommands.parse(t)?.let { return Action.SysAction(it) }
            chromeSearchB.matchEntire(t)?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotEmpty()) return buildChromeSearch(q)
            }
            chromeSearchA.matchEntire(t)?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotEmpty() && !q.contains(" on ") && !q.contains(" in ")) return buildChromeSearch(q)
            }
            callA.matchEntire(t)?.let { return resolveCall(it.groupValues[1].trim()) }
            callB.matchEntire(t)?.let { return resolveCall(it.groupValues[1].trim()) }
            openA.matchEntire(t)?.let {
                val target = it.groupValues[1].trim()
                if (target.split(" ").size <= 4) return resolveOpen(target)
            }
            openB.matchEntire(t)?.let {
                val target = it.groupValues[1].trim()
                if (target.split(" ").size <= 4) return resolveOpen(target)
            }
            return resolveBare(t)
        }

        private fun buildChromeSearch(query: String): Action {
            val url = "https://www.google.com/search?q=${Uri.encode(query)}"
            return Action.Launch(
                Intent.ACTION_VIEW, url, "com.android.chrome", emptyMap(),
                null, "Chrome search: $query", ""
            )
        }

    private suspend fun matchSkill(t: String): Action? = withContext(Dispatchers.IO) {
        val skills = db.skillDao().list()
        if (skills.isEmpty()) return@withContext null
        var words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty() && words[0].lowercase() in skillPrefixes) words = words.drop(1)
        val best = skills.mapNotNull { sk ->
            val nm = sk.name.replace('_', ' ')
            val n = nm.split(" ").size
            if (words.size < n) null
            else Triple(sk, Fuzzy.score(words.take(n).joinToString(" "), nm), words.drop(n).joinToString(" "))
        }.filter { it.second >= 0.85 }.maxByOrNull { it.second } ?: return@withContext null
        val sk = best.first
        val params = sk.params.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        when {
            params.isEmpty() -> Action.RunSkill(sk.name, emptyMap())
            params.size == 1 ->
                if (best.third.isBlank()) Action.Unknown("What should I use for ${params[0]}?")
                else Action.RunSkill(sk.name, mapOf(params[0] to best.third))
            else -> null
        }
    }

    private fun pickCall(q: String, c: List<Pair<String, String>>): Action? {
        val s = c.map { it to Fuzzy.score(q, it.first) }
            .filter { it.second >= 0.72 }
            .sortedByDescending { it.second }
        if (s.isEmpty()) return null
        if (s.size > 1 && s[0].second - s[1].second < 0.04 &&
            s[0].first.second.filter { ch -> ch.isDigit() } != s[1].first.second.filter { ch -> ch.isDigit() }
        ) {
            return Action.Unknown("Ambiguous: ${s[0].first.first} or ${s[1].first.first}. Say the full name.")
        }
        return Action.Call(s[0].first.second, s[0].first.first)
    }

    suspend fun resolveCall(name: String): Action = withContext(Dispatchers.IO) {
        val aliasList = db.aliasDao().list().filter { it.phone.isNotBlank() }.map { it.name to it.phone }
        pickCall(name, aliasList)
            ?: pickCall(name, contacts())
            ?: Action.NoMatch("No alias or contact matches \"$name\".")
    }

    suspend fun phoneFor(name: String): String? = withContext(Dispatchers.IO) {
        contacts().map { it to Fuzzy.score(name, it.first) }
            .filter { it.second >= 0.72 }.maxByOrNull { it.second }?.first?.second
    }

    private fun contacts(): List<Pair<String, String>> {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val out = mutableListOf<Pair<String, String>>()
        val cols = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
            }
        }
        return out
    }

    private fun installedApps(): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
    }

    private fun nicknameAction(n: Nickname): Action {
        val t = n.target.trim()
        if (t.contains("://")) return Action.OpenUrl(t, n.name)
        val pm = ctx.packageManager
        if (t.contains('.') && !t.contains(' ')) {
            return if (pm.getLaunchIntentForPackage(t) != null) Action.OpenApp(t, n.name)
            else Action.OpenUrl("https://$t", n.name)
        }
        val app = installedApps().map { it to Fuzzy.score(t, it.first) }
            .filter { it.second >= 0.75 }.maxByOrNull { it.second }
        return if (app != null) Action.OpenApp(app.first.second, app.first.first)
        else Action.Unknown("Nickname \"${n.name}\" points to \"$t\" which I can't find")
    }

    suspend fun resolveOpen(name: String): Action = withContext(Dispatchers.IO) {
        val nn = db.nicknameDao().list().map { it to Fuzzy.score(name, it.name) }
            .filter { it.second >= 0.8 }.maxByOrNull { it.second }
        if (nn != null) return@withContext nicknameAction(nn.first)
        val app = installedApps().map { it to Fuzzy.score(name, it.first) }
            .filter { it.second >= 0.75 }.maxByOrNull { it.second }
        if (app != null) return@withContext Action.OpenApp(app.first.second, app.first.first)
        Action.NoMatch("No app or nickname matches \"$name\".")
    }

    private suspend fun resolveBare(t: String): Action = withContext(Dispatchers.IO) {
        val nn = db.nicknameDao().list().map { it to Fuzzy.score(t, it.name) }
            .filter { it.second >= 0.85 }.maxByOrNull { it.second }
        if (nn != null) return@withContext nicknameAction(nn.first)
        Action.NoMatch("I only know call and open without AI.")
    }
}
