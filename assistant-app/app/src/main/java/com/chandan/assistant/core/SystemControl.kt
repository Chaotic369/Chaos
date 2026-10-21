package com.chandan.assistant.core

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import com.chandan.assistant.a11y.AssistantAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** kind: wifi | bluetooth | airplane | dnd | volume | brightness | force_stop | timer */
data class SysCmd(val kind: String, val on: Boolean = true, val value: String = "")

object SystemCommands {
    fun parse(raw: String): SysCmd? {
        val t = raw.lowercase().trim().replace("wi-fi", "wifi").replace("wi fi", "wifi")
        Regex("""^force[\s-]?stop\s+(.+)$""").find(t)?.let { return SysCmd("force_stop", value = it.groupValues[1].trim()) }
        Regex("""timer.*?(\d+)\s*(second|sec|minute|min|hour|hr)""").find(t)?.let {
            val n = it.groupValues[1].toInt()
            val secs = when (it.groupValues[2]) {
                "second", "sec" -> n
                "minute", "min" -> n * 60
                else -> n * 3600
            }
            return SysCmd("timer", value = secs.toString())
        }
        Regex("""brightness.*?(\d{1,3})""").find(t)?.let { return SysCmd("brightness", value = it.groupValues[1]) }
        if ("volume" in t) {
            Regex("""(\d{1,3})""").find(t)?.let { return SysCmd("volume", value = "set:" + it.groupValues[1]) }
            return when {
                "up" in t || "increase" in t -> SysCmd("volume", value = "up")
                "down" in t || "decrease" in t || "lower" in t -> SysCmd("volume", value = "down")
                "max" in t || "full" in t -> SysCmd("volume", value = "max")
                "mute" in t || "min" in t -> SysCmd("volume", value = "mute")
                else -> null
            }
        }
        val kinds = listOf(
            "wifi" to "wifi", "bluetooth" to "bluetooth", "airplane" to "airplane",
            "flight mode" to "airplane", "do not disturb" to "dnd", "dnd" to "dnd"
        )
        for ((k, kind) in kinds) {
            if (k in t) {
                val off = Regex("""\b(off|disable)\b""").containsMatchIn(t)
                val on = Regex("""\b(on|enable)\b""").containsMatchIn(t)
                return when {
                    off -> SysCmd(kind, false)
                    on -> SysCmd(kind, true)
                    else -> null
                }
            }
        }
        return null
    }
}

object Apps {
    fun find(ctx: Context, name: String): Pair<String, String>? {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { (it.loadLabel(pm).toString() to it.activityInfo.packageName) }
            .map { it to Fuzzy.score(name, it.first) }
            .filter { it.second >= 0.75 }.maxByOrNull { it.second }?.first
    }
}

object SystemControl {
    suspend fun run(ctx: Context, c: SysCmd): Outcome = try {
        when (c.kind) {
            "wifi" -> toggle(c, "svc wifi " + en(c), listOf("Wi-Fi", "Internet", "WiFi"), "Wi-Fi")
            "bluetooth" -> toggle(c, "svc bluetooth " + en(c), listOf("Bluetooth"), "Bluetooth")
            "airplane" -> toggle(c, "cmd connectivity airplane-mode " + en(c), listOf("Airplane mode", "Flight mode", "Airplane"), "Airplane mode")
            "dnd" -> dnd(ctx, c)
            "volume" -> volume(ctx, c.value)
            "brightness" -> brightness(ctx, (c.value.toIntOrNull() ?: 50).coerceIn(0, 100))
            "force_stop" -> forceStop(ctx, c.value)
            "timer" -> timer(ctx, c.value.toIntOrNull() ?: 60)
            else -> Outcome(false, "Unknown system command")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(false, e.message ?: "System command failed")
    }

    private fun en(c: SysCmd) = if (c.on) "enable" else "disable"
    private fun word(c: SysCmd) = if (c.on) "on" else "off"

    /** Shizuku shell if available, else tap the Quick Settings tile through accessibility. */
    private suspend fun toggle(c: SysCmd, shell: String, labels: List<String>, name: String): Outcome {
        if (ShizukuShell.available()) {
            val (code, _) = ShizukuShell.exec(shell)
            if (code == 0) return Outcome(true, "$name ${word(c)}")
        }
        val svc = AssistantAccessibilityService.instance
            ?: return Outcome(false, "Turning $name ${word(c)} needs Shizuku or the accessibility service")
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        delay(900)
        val n = svc.waitForNode(labels, false, 3000)
        if (n == null) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            return Outcome(false, "Couldn't find the $name tile")
        }
        val st = if (Build.VERSION.SDK_INT >= 30) n.stateDescription?.toString() else null
        val current = if (st != null) st.equals("on", true) else n.isChecked
        if (current != c.on) {
            svc.click(n)
            delay(600)
        }
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return Outcome(true, "$name ${word(c)}")
    }

    private suspend fun dnd(ctx: Context, c: SysCmd): Outcome {
        if (ShizukuShell.available()) {
            val (code, _) = ShizukuShell.exec("cmd notification set_dnd " + if (c.on) "priority" else "off")
            if (code == 0) return Outcome(true, "Do not disturb ${word(c)}")
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(
                if (c.on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            return Outcome(true, "Do not disturb ${word(c)}")
        }
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Outcome(false, "Allow Do Not Disturb access for Assistant, then try again")
    }

    private fun volume(ctx: Context, v: String): Outcome {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when {
            v == "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            v == "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            v == "max" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
            v == "mute" -> am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            v.startsWith("set:") -> {
                val pct = (v.removePrefix("set:").toIntOrNull() ?: 50).coerceIn(0, 100)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * pct / 100, AudioManager.FLAG_SHOW_UI)
            }
        }
        return Outcome(true, "Volume $v".replace("set:", "").trim())
    }

    private suspend fun brightness(ctx: Context, pct: Int): Outcome {
        val level = pct * 255 / 100
        if (Settings.System.canWrite(ctx)) {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            return Outcome(true, "Brightness $pct percent")
        }
        if (ShizukuShell.available()) {
            val (code, _) = ShizukuShell.exec("settings put system screen_brightness $level")
            if (code == 0) return Outcome(true, "Brightness $pct percent")
        }
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return Outcome(false, "Allow Modify system settings for Assistant, then try again")
    }

    private suspend fun forceStop(ctx: Context, name: String): Outcome {
        val app = Apps.find(ctx, name) ?: return Outcome(false, "No app named \"$name\"")
        if (ShizukuShell.available()) {
            val (code, _) = ShizukuShell.exec("am force-stop ${app.second}")
            if (code == 0) return Outcome(true, "Force stopped ${app.first}")
        }
        val svc = AssistantAccessibilityService.instance
            ?: return Outcome(false, "Force stop needs Shizuku or the accessibility service")
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.second}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        delay(1200)
        val b = svc.waitForNode(listOf("Force stop"), false, 4000) ?: return Outcome(false, "Couldn't find Force stop (is the app already stopped?)")
        svc.click(b)
        delay(800)
        val ok = svc.waitForNode(listOf("OK", "Force stop"), false, 3000) ?: return Outcome(false, "Couldn't confirm the dialog")
        svc.click(ok)
        delay(500)
        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        return Outcome(true, "Force stopped ${app.first}")
    }

    private fun timer(ctx: Context, secs: Int): Outcome {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return Outcome(true, "Timer set for ${if (secs >= 60) "${secs / 60} minutes" else "$secs seconds"}")
    }
}
