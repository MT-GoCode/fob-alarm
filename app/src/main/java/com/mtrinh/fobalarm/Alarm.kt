package com.mtrinh.fobalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import java.util.Calendar

object Alarm {
    const val ACTION_FIRE = "com.mtrinh.fobalarm.FIRE"
    const val ACTION_WATCHDOG = "com.mtrinh.fobalarm.WATCHDOG"
    const val MAX_RING_MINUTES = 60
    private const val RC_FIRE = 1001
    private const val RC_WATCHDOG = 1002

    private fun pi(ctx: Context, action: String, rc: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, rc,
            Intent(ctx, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Next occurrence of defaultAlarmTime strictly in the future. */
    fun computeNextFire(now: Long = System.currentTimeMillis()): Long {
        val (h, m) = DeState.defaultAlarmTime.split(":").map { it.toInt() }
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** The single chokepoint. Every state change ends here. */
    fun recompute(ctx: Context, reason: String) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val next = computeNextFire()
        DeState.nextFireAtMs = next
        // setAlarmClock: exempt from standby quotas and Doze, and carries the FGS-start allowlist.
        am.setAlarmClock(AlarmManager.AlarmClockInfo(next, pi(ctx, ACTION_FIRE, RC_FIRE)), pi(ctx, ACTION_FIRE, RC_FIRE))
        Log.e("recompute", "reason" to reason, "nextFireAtMs" to next)
    }

    fun scheduleTestFire(ctx: Context, seconds: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + seconds * 1000L
        DeState.nextFireAtMs = at
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi(ctx, ACTION_FIRE, RC_FIRE)), pi(ctx, ACTION_FIRE, RC_FIRE))
        Log.e("test_fire_scheduled", "atMs" to at, "inSeconds" to seconds)
    }

    fun armWatchdog(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + 60_000L
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi(ctx, ACTION_WATCHDOG, RC_WATCHDOG)), pi(ctx, ACTION_WATCHDOG, RC_WATCHDOG))
    }

    fun cancelWatchdog(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACTION_WATCHDOG, RC_WATCHDOG))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        DeState.init(ctx); Log.init(ctx)
        Log.e("alarm_receiver", "action" to intent.action)

        // AlarmManager's own wake lock ends when onReceive returns, and the FGS-start
        // allowlist is ~10s. Take our own lock and start the service SYNCHRONOUSLY.
        val pm = ctx.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:receiver")
        wl.acquire(60_000L)
        try {
            when (intent.action) {
                Alarm.ACTION_FIRE -> {
                    if (!DeState.hasOpenSession) {
                        DeState.openSession(System.currentTimeMillis(), Alarm.MAX_RING_MINUTES)
                        Log.e("ring_start", "source" to "SCHEDULED")
                    }
                    startRing(ctx)
                }
                Alarm.ACTION_WATCHDOG -> {
                    if (DeState.hasOpenSession) {
                        Log.e("watchdog_resurrect")
                        startRing(ctx)
                    }
                }
            }
        } finally {
            if (wl.isHeld) wl.release()
        }
    }

    private fun startRing(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, RingService::class.java))
        } catch (e: Exception) {
            // Blew the allowlist window (cold start after OTA, busy disk). Retry in 5s.
            Log.e("fgs_start_failed", "error" to e.toString())
            Alarm.scheduleTestFire(ctx, 5)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        DeState.init(ctx); Log.init(ctx)
        Log.e("boot", "action" to intent.action)
        Alarm.recompute(ctx, "boot:${intent.action}")
        // An update is a process death; MY_PACKAGE_REPLACED must restart the FGS first if a
        // session was open, then rebuild alarms.
        if (DeState.hasOpenSession && DeState.sessionEndsByMs > System.currentTimeMillis()) {
            Log.e("resume_session_after_boot")
            ctx.startForegroundService(Intent(ctx, RingService::class.java))
        }
    }
}
