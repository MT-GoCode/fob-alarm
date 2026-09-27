package com.mtrinh.fobalarm.service

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import com.mtrinh.fobalarm.core.*

/** One place that guarantees Svc is up, whatever entry point woke the process. */
object Boot {
    @Volatile private var version = "?"
    @Volatile private var variant = Variant.LIVE
    fun configure(v: String, va: Variant) { version = v; variant = va }
    fun ensure(ctx: Context) = Svc.init(ctx, version, variant)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        if (Svc.settings.role != Role.ALARM) return      // controller never fires alarms
        // AlarmManager's own wake lock ends when onReceive returns and the FGS-start
        // allowlist is ~10s, so take a lock and start the service SYNCHRONOUSLY.
        val wl = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:receiver")
        wl.acquire(60_000)
        try {
            when (intent.action) {
                Scheduler.ACTION_FIRE -> {
                    Svc.log("alarm_fired")
                    RingService.start(ctx)
                }
                Scheduler.ACTION_WATCHDOG -> {
                    if (Svc.session != null) {
                        Svc.log("watchdog_resurrect")
                        RingService.start(ctx)
                    }
                }
                Scheduler.ACTION_TICK -> {
                    ClockObserver.poll()
                    Svc.log("tick", "clockSource" to ClockObserver.source)
                    Svc.pruneHistory()
                    Svc.recompute("hourly_tick")
                }
                Scheduler.ACTION_ARMGATE -> ArmGateRunner.run(ctx)
            }
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        Svc.log("boot", "action" to (intent.action ?: "?"))

        // An update is a process death that takes the FGS with it. The temporary
        // background-FGS allowlist granted with MY_PACKAGE_REPLACED is ~10s and is the
        // only chance to restart it, so do that FIRST.
        val open = Svc.session
        if (open != null && open.endsByMs > System.currentTimeMillis()) {
            Svc.log("resume_session_after_boot", "ringId" to open.ringId)
            RingService.start(ctx)
        }
        // Pending alarms survive a package replace, but the exact-alarm re-check can wipe
        // them, and a reboot clears everything. Rebuild unconditionally.
        Svc.recompute("boot:${intent.action}")
        LinkService.start(ctx)

        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Svc.log("package_replaced", "toVersion" to Svc.appVersion)
        }
    }
}

/**
 * The nightly arm gate. Pass -> absolutely nothing happens. Fail -> audible, because a
 * silent notification behind acrylic is not a warning. It NEVER disarms. SPEC.md section 10.
 */
object ArmGateRunner {
    fun run(ctx: Context) {
        Boot.ensure(ctx)
        if (Svc.settings.role != Role.ALARM) return
        GateEval.invalidateSlowChecks()
        val gates = GateEval.evaluate(ctx, Svc.settings, Svc.lastNextFire != null)
        val clockOk = ClockObserver.healthy()
        val failing = gates.failing().toMutableList()
        if (!clockOk) failing += "clockSynced"

        val pass = failing.isEmpty()
        Svc.armGate = ArmGate(System.currentTimeMillis(), if (pass) "PASS" else "FAIL", failing)
        Svc.log(if (pass) "gate_pass" else "gate_fail", "failing" to failing.joinToString(","))

        if (!pass) {
            // Three short chirps at STREAM_ALARM volume. You are awake at 22:00.
            Audio(ctx).chirp(3)
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching {
                nm.notify(77, android.app.Notification.Builder(ctx, RingService.CHANNEL_STATUS)
                    .setContentTitle("Arm gate failed")
                    .setContentText(failing.joinToString(", "))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .build())
            }
        }
        Svc.log("arm")
        Scheduler.armGateAlarm(ctx)
    }
}

/**
 * Before first unlock, Room is unreadable. This is the only moment it becomes readable,
 * so it is the only chance to load the settings that live there.
 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        Svc.tryLoadRoom("user_unlocked")
    }
}

/**
 * A clock correction was previously noticed up to an hour late, via the tick. A backwards
 * jump could then invent a fortnight of missed alarms; a forward one could hide real
 * misses for years.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        ClockObserver.poll()
        Svc.log("time_changed", "action" to (intent.action ?: "?"))
        Svc.recompute("time_changed")
    }
}

/** Hibernation and force-stop both cancel every PendingIntent. Make it visible. */
object ForceStopDetector {
    fun check(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessStartReasons(1).firstOrNull() ?: return
            if (info.wasForceStopped()) Svc.log("force_stopped_detected")
        }
    }
}
