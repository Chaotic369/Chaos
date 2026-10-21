package com.chandan.assistant.a11y

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.chandan.assistant.core.AssistantService
import com.chandan.assistant.data.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

data class Notif(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val reply: Notification.Action?
)

class AssistantNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: AssistantNotificationListener? = null
        val recent = CopyOnWriteArrayList<Notif>()
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || sbn.isOngoing) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = label(sbn.packageName)
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        recent.removeAll { it.key == sbn.key }
        recent.add(0, Notif(sbn.key, sbn.packageName, app, title, text, sbn.postTime, reply))
        while (recent.size > 30) recent.removeAt(recent.size - 1)

        val ctx = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val triggers = AppDb.get(ctx).triggerDao().list().filter { it.enabled && it.type == "notification" }
            for (t in triggers) {
                val appOk = t.notifApp.isBlank() || app.contains(t.notifApp, true) || sbn.packageName.contains(t.notifApp, true)
                val textOk = t.notifText.isBlank() || "$title $text".contains(t.notifText, true)
                if (appOk && textOk) AssistantService.dispatch(ctx, t.command, t.name)
            }
        }
    }
}
