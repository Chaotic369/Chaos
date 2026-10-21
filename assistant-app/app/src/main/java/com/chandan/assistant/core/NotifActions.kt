package com.chandan.assistant.core

import android.content.Intent
import android.app.PendingIntent
import android.app.RemoteInput
import android.os.Bundle
import com.chandan.assistant.a11y.AssistantNotificationListener
import com.chandan.assistant.core.ai.AiClient
import kotlinx.coroutines.CancellationException

object NotifActions {
    suspend fun read(ai: AiClient?): Outcome {
        if (AssistantNotificationListener.instance == null) {
            return Outcome(false, "Turn on notification access in Settings first.")
        }
        val items = AssistantNotificationListener.recent.take(6)
        if (items.isEmpty()) return Outcome(true, "No new notifications.")
        val raw = items.joinToString("\n") { "${it.appLabel}: ${it.title} - ${it.text}".take(160) }
        if (ai != null) {
            try {
                return Outcome(true, ai.summarize(raw))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
        return Outcome(true, items.take(3).joinToString(". ") { "${it.appLabel}, ${it.title}: ${it.text}".take(120) })
    }

    fun reply(text: String): Outcome {
        val ctx = AssistantNotificationListener.instance
            ?: return Outcome(false, "Turn on notification access in Settings first.")
        val n = AssistantNotificationListener.recent.firstOrNull { it.reply != null }
            ?: return Outcome(false, "No notification I can reply to.")
        val act = n.reply ?: return Outcome(false, "No notification I can reply to.")
        val inputs = act.remoteInputs ?: return Outcome(false, "That notification can't take replies.")
        val intent = Intent()
        val b = Bundle()
        b.putCharSequence(inputs[0].resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, b)
        return try {
            act.actionIntent.send(ctx, 0, intent)
            Outcome(true, "Replied on ${n.appLabel}${if (n.title.isNotBlank()) " to ${n.title}" else ""}")
        } catch (e: PendingIntent.CanceledException) {
            Outcome(false, "That chat is gone.")
        }
    }
}
