package com.chandan.assistant.core

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.chandan.assistant.data.AppDb
import com.chandan.assistant.data.Trigger
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object Triggers {
    const val ACTION_TIME = "com.chandan.assistant.TRIGGER_TIME"
    const val ACTION_GEO = "com.chandan.assistant.TRIGGER_GEO"

    private fun timePi(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(),
        Intent(ctx, TriggerReceiver::class.java).setAction(ACTION_TIME).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun geoPi(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, Intent(ctx, TriggerReceiver::class.java).setAction(ACTION_GEO), flags)
    }

    /** Next time this trigger should fire. Days use Calendar numbers 1=Sun..7=Sat, blank = every day. */
    fun nextTime(t: Trigger): Long? {
        val p = t.time.split(":")
        val h = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val days = t.days.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance()
        for (i in 0..7) {
            c.timeInMillis = now
            c.add(Calendar.DAY_OF_YEAR, i)
            c.set(Calendar.HOUR_OF_DAY, h)
            c.set(Calendar.MINUTE, m)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis > now && (days.isEmpty() || c.get(Calendar.DAY_OF_WEEK) in days)) return c.timeInMillis
        }
        return null
    }

    fun cancel(ctx: Context, t: Trigger) {
        when (t.type) {
            "time" -> ctx.getSystemService(AlarmManager::class.java).cancel(timePi(ctx, t.id))
            "location" -> try {
                LocationServices.getGeofencingClient(ctx).removeGeofences(listOf(t.id.toString()))
            } catch (e: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun schedule(ctx: Context, t: Trigger) {
        cancel(ctx, t)
        if (!t.enabled) return
        try {
            when (t.type) {
                "time" -> {
                    val at = nextTime(t) ?: return
                    val am = ctx.getSystemService(AlarmManager::class.java)
                    val pi = timePi(ctx, t.id)
                    if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    } else {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                    }
                }
                "location" -> {
                    if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                        PackageManager.PERMISSION_GRANTED
                    ) return
                    val g = Geofence.Builder()
                        .setRequestId(t.id.toString())
                        .setCircularRegion(t.lat, t.lng, t.radius)
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(
                            if (t.event == "leave") Geofence.GEOFENCE_TRANSITION_EXIT else Geofence.GEOFENCE_TRANSITION_ENTER
                        )
                        .build()
                    val req = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(g).build()
                    LocationServices.getGeofencingClient(ctx).addGeofences(req, geoPi(ctx))
                }
            }
        } catch (e: Exception) {
        }
    }

    suspend fun scheduleAll(ctx: Context) {
        AppDb.get(ctx).triggerDao().list().forEach { schedule(ctx, it) }
    }
}

class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(app).triggerDao()
                when (intent.action) {
                    Triggers.ACTION_TIME -> {
                        val t = dao.get(intent.getLongExtra("id", -1))
                        if (t != null && t.enabled) {
                            Triggers.schedule(app, t)
                            AssistantService.dispatch(app, t.command, t.name)
                        }
                    }
                    Triggers.ACTION_GEO -> {
                        val ev = GeofencingEvent.fromIntent(intent)
                        if (ev != null && !ev.hasError()) {
                            ev.triggeringGeofences?.forEach { g ->
                                val t = dao.get(g.requestId.toLongOrNull() ?: -1)
                                if (t != null && t.enabled) AssistantService.dispatch(app, t.command, t.name)
                            }
                        }
                    }
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Triggers.scheduleAll(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
