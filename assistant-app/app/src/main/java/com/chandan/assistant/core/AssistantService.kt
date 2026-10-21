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
                const val ACTION_PTT = "com.chandan.assistant.PTT"
                const val ACTION_TOGGLE_CONTINUOUS = "com.chandan.assistant.TOGGLE_CONTINUOUS"

                fun start(ctx: Context) {
                    try {
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java))
                    } catch (e: Exception) {
                    }
                }

                fun triggerPtt(ctx: Context) {
                    val s = instance
                    if (s != null) {
                        s.startPushToTalk()
                        return
                    }
                    try {
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java).setAction(ACTION_PTT))
                    } catch (e: Exception) {
                    }
                }

                fun toggleContinuous(ctx: Context) {
                    val s = instance
                    if (s != null) {
                        s.toggleContinuousMode()
                        return
                    }
                    try {
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java).setAction(ACTION_TOGGLE_CONTINUOUS))
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
        private var continuousJob: Job? = null
        private val stopper: () -> Unit = {
            cmdJob?.cancel()
            voice.stopListening()
            voice.stopSpeaking()
        }

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onCreate() {
            super.onCreate()
            instance = this
            ensureChannels(this)
            voice = VoiceController(this)
            Bus.addStopper(stopper)
            scope.launch {
                voice.listening.collect { Bus.listening.value = it }
            }
            scope.launch {
                Bus.busy.collect { b ->
                    loopJob?.cancel()
                    if (b && Prefs(this@AssistantService).voiceStop && !Bus.continuousMode.value) {
                        loopJob = launch { listenLoop() }
                    }
                }
            }
        }

        private fun buildNotification(): Notification {
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(
                this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
            )
            val ptt = PendingIntent.getService(
                this, 2, Intent(this, AssistantService::class.java).setAction(ACTION_PTT), PendingIntent.FLAG_IMMUTABLE
            )
            val toggle = PendingIntent.getService(
                this, 3, Intent(this, AssistantService::class.java).setAction(ACTION_TOGGLE_CONTINUOUS), PendingIntent.FLAG_IMMUTABLE
            )
            val modeLabel = if (Bus.continuousMode.value) "Continuous ON (Tap Vol Up to stop)" else "Tap Vol Up for Continuous / PTT"
            return Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Assistant is active")
                .setContentText(modeLabel)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(0, "PTT", ptt).build())
                .addAction(Notification.Action.Builder(0, "CONTINUOUS", toggle).build())
                .addAction(Notification.Action.Builder(0, "STOP", stop).build())
                .setOngoing(true)
                .build()
        }

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (intent?.action == ACTION_STOP) {
                if (Bus.continuousMode.value) setContinuousMode(false)
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
            when (intent?.action) {
                ACTION_PTT -> startPushToTalk()
                ACTION_TOGGLE_CONTINUOUS -> toggleContinuousMode()
            }
            intent?.getStringExtra("cmd")?.let { runCommand(it) }
            return START_STICKY
        }

        fun startPushToTalk() {
            if (Bus.continuousMode.value) setContinuousMode(false)
            cmdJob?.cancel()
            voice.stopSpeaking()
            val lang = Prefs(this).lang
            Bus.statusText.value = "Listening (PTT)…"
            voice.listen(
                lang = lang,
                partial = { Bus.statusText.value = it },
                done = { text ->
                    Bus.statusText.value = text
                    runCommand(text)
                },
                fail = { err ->
                    Bus.statusText.value = err
                }
            )
        }

        fun toggleContinuousMode() {
            setContinuousMode(!Bus.continuousMode.value)
        }

        private fun setContinuousMode(enabled: Boolean) {
            Bus.continuousMode.value = enabled
            continuousJob?.cancel()
            runCatching {
                getSystemService(NotificationManager::class.java).notify(1, buildNotification())
            }
            if (enabled) {
                Bus.statusText.value = "Continuous mode ON"
                voice.speak("Listening")
                continuousJob = scope.launch {
                    delay(650)
                    continuousListenLoop()
                }
            } else {
                voice.stopListening()
                Bus.statusText.value = "Continuous mode OFF"
                voice.speak("Stopped listening")
            }
        }

        private suspend fun continuousListenLoop() {
            val prefs = Prefs(this)
            while (currentCoroutineContext().isActive && Bus.continuousMode.value) {
                while (voice.isSpeaking || Bus.busy.value) {
                    delay(250)
                }
                if (!Bus.continuousMode.value) break
                val lang = prefs.lang
                val text = suspendCancellableCoroutine<String?> { c ->
                    Handler(Looper.getMainLooper()).post {
                        voice.listen(
                            lang = lang,
                            partial = { Bus.statusText.value = it },
                            done = { if (c.isActive) c.resume(it) },
                            fail = { if (c.isActive) c.resume(null) }
                        )
                    }
                    c.invokeOnCancellation {
                        Handler(Looper.getMainLooper()).post { voice.stopListening() }
                    }
                }
                val cleaned = text?.trim().orEmpty()
                if (cleaned.isNotEmpty() && Bus.continuousMode.value) {
                    val words = cleaned.lowercase().split(" ", ",", ".")
                    if (words.size <= 2 && words.any { it == "stop" || it == "ruko" || it == "cancel" }) {
                        Bus.stopAll()
                    } else {
                        runCommand(cleaned)?.join()
                        delay(450)
                    }
                } else {
                    delay(300)
                }
            }
        }

        fun runCommand(cmd: String): Job? {
            cmdJob?.cancel()
            cmdJob = scope.launch {
                Bus.setBusy("svc", true)
                AssistantAccessibilityService.instance?.showStop { Bus.stopAll() }
                try {
                    CommandRunner(applicationContext).run(cmd, { Bus.statusText.value = it }) {
                        Bus.statusText.value = it
                        voice.speak(it)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Bus.statusText.value = e.message ?: "Error"
                } finally {
                    Bus.setBusy("svc", false)
                    AssistantAccessibilityService.instance?.hideStop()
                }
            }
            return cmdJob
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
