package com.chandan.assistant.core

import android.Manifest
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.core.ai.AiClient

data class Outcome(val ok: Boolean, val message: String)

class Executor(private val ctx: Context) {
    var aiProvider: () -> AiClient? = { null }

    suspend fun run(a: Action): Outcome {
        return try {
            when (a) {
                is Action.Call -> {
                    val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED
                    val verb = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
                    val i = Intent(verb, Uri.parse("tel:" + Uri.encode(a.number)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    Outcome(true, "Calling ${a.label}")
                }
                is Action.OpenApp -> {
                    val i = ctx.packageManager.getLaunchIntentForPackage(a.pkg)
                    if (i == null) Outcome(false, "Can't launch ${a.label}")
                    else {
                        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        Outcome(true, "Opening ${a.label}")
                    }
                }
                is Action.OpenUrl -> {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    Outcome(true, "Opening ${a.label}")
                }
                is Action.Launch -> {
                    val i = Intent(a.verb, Uri.parse(a.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    a.extras.forEach { (k, v) -> i.putExtra(k, v) }
                    if (a.pkg != null && ctx.packageManager.getLaunchIntentForPackage(a.pkg) != null) i.setPackage(a.pkg)
                    if (a.clip != null) {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("message", a.clip))
                    }
                    ctx.startActivity(i)
                    val svc = AssistantAccessibilityService.instance
                    if (a.recipe.isNotEmpty() && svc != null) {
                        val ok = Recipes.run(svc, ctx, a.recipe, aiProvider())
                        if (ok) Outcome(true, "Done: ${a.label}") else Outcome(true, "Opened ${a.label}. Couldn't auto-tap. ${a.note}")
                    } else {
                        Outcome(true, "Opened ${a.label}. ${a.note}".trim())
                    }
                }
                is Action.Alarm -> {
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    Outcome(true, "Alarm set for %02d:%02d".format(a.hour, a.minute))
                }
                is Action.SysAction -> SystemControl.run(ctx, a.cmd, aiProvider())
                is Action.ReadNotifications -> NotifActions.read(aiProvider())
                is Action.ReplyNotif -> NotifActions.reply(a.text)
                is Action.Agent, is Action.RunSkill, is Action.TeachStart, is Action.TeachDone ->
                    Outcome(false, "Handled by the main screen")
                is Action.NoMatch -> Outcome(false, a.reason)
                is Action.Stop -> Outcome(true, "Stopped")
                is Action.Unknown -> Outcome(false, a.reason)
            }
        } catch (e: Exception) {
            Outcome(false, e.message ?: "Error")
        }
    }
}
