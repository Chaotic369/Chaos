package com.chandan.assistant.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.chandan.assistant.MainActivity
import com.chandan.assistant.a11y.AssistantAccessibilityService
import com.chandan.assistant.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Keeps the assistant alive (persistent notification), runs triggered commands, listens for "stop" during tasks. */
class AssistantService : Service() {
    companion object {
        @Volatile var instance: AssistantService? = null
        const val CHANNEL = "assistant"
        const val ALERTS = "assistant_alerts"
        const val ACTION_RUN = "com.chandan.assistant.RUN"
        const val ACTION_STOP = "com.chandan.assistant.STOP"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java))
            } catch (e: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AssistantService::class.java))
        }

        /** Run a command from a trigger. Falls back to a tap-to-run notification when the service can't start. */
        fun dispatch(ctx: Context, command: String, label: String) {
            val s = instance
            if (s != null) {
                s.runCommand(command)
                return
            }
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, AssistantService::class.java).setAction(ACTION_RUN).putExtra("cmd", command).putExtra("label", label)
                )
            } catch (e: Exception) {
                notifyTap(ctx, command, label)
            }
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Assistant running", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ALERTS, "Trigger alerts", NotificationManager.IMPORTANCE_HIGH))
        }

        fun notifyTap(ctx: Context, command: String, label: String) {
            ensureChannels(ctx)
            val pi = PendingIntent.getActivity(
                ctx, command.hashCode(),
                Intent(ctx, MainActivity::class.java).putExtra("command", command).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(ctx, ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Trigger: $label")
                .setContentText("Tap to run: $command")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(command.hashCode(), n)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var voice: VoiceController
    private var cmdJob: Job? = null
    private var loopJob: Job? = null
    private val stopper: () -> Unit = { cmdJob?.cancel() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannels(this)
        voice = VoiceController(this)
        Bus.addStopper(stopper)
        scope.launch {
            Bus.busy.collect { b ->
                loopJob?.cancel()
                if (b && Prefs(this@AssistantService).voiceStop) loopJob = launch { listenLoop() }
            }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Assistant is ready")
            .setContentText("Tap to open. STOP cancels a running task.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "STOP", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Bus.stopAll()
            return START_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, buildNotification())
            }
        } catch (e: Exception) {
            intent?.getStringExtra("cmd")?.let { notifyTap(this, it, intent.getStringExtra("label") ?: "Trigger") }
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra("cmd")?.let { runCommand(it) }
        return START_STICKY
    }

    fun runCommand(cmd: String) {
        cmdJob?.cancel()
        cmdJob = scope.launch {
            Bus.setBusy("svc", true)
            AssistantAccessibilityService.instance?.showStop { Bus.stopAll() }
            try {
                CommandRunner(applicationContext).run(cmd, { }) { voice.speak(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            } finally {
                Bus.setBusy("svc", false)
                AssistantAccessibilityService.instance?.hideStop()
            }
        }
    }

    private suspend fun listenLoop() {
        val lang = Prefs(this).lang
        while (currentCoroutineContext().isActive) {
            val text = suspendCancellableCoroutine<String?> { c ->
                voice.listen(lang, {}, { if (c.isActive) c.resume(it) }, { if (c.isActive) c.resume(null) })
                c.invokeOnCancellation { Handler(Looper.getMainLooper()).post { voice.stopListening() } }
            }
            if (text != null && text.lowercase().split(" ", ",", ".").any { it == "stop" || it == "ruko" || it == "cancel" }) {
                Bus.stopAll()
            }
            delay(400)
        }
    }

    override fun onDestroy() {
        instance = null
        Bus.removeStopper(stopper)
        scope.cancel()
        voice.release()
        super.onDestroy()
    }
}
