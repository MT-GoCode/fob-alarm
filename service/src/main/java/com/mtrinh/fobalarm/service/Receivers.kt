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
                    Svc.pruneHistory()
                    Svc.recompute("hourly_tick")
                    SyncWindow.run(ctx)
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
/**
 * Hourly health refresh. It does NOT chirp and it does NOT disarm -- arming is an
 * explicit switch the user controls, and a failing check is reported on the status
 * screen rather than at 22:00 in the dark.
 */
object ArmGateRunner {
    fun run(ctx: Context) {
        Boot.ensure(ctx)
        if (Svc.settings.role != Role.ALARM) return
        GateEval.invalidateSlowChecks()
        val gates = GateEval.evaluate(ctx, Svc.settings, Svc.lastNextFire != null)
        val failing = gates.failing()
        Svc.armGate = ArmGate(System.currentTimeMillis(),
            if (failing.isEmpty()) "OK" else "ISSUES", failing)
        Svc.log("health", "failing" to failing.joinToString(","))
        Scheduler.armGateAlarm(ctx)
    }
}

/**
 * Before first unlock Room is unreadable, so this is the only moment the settings that
 * live there can be loaded.
 */
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        Svc.tryLoadRoom("user_unlocked")
        LinkService.start(ctx)
    }
}

/**
 * A clock correction was otherwise noticed up to an hour late, via the tick. A backwards
 * jump can invent missed alarms; a forward one can hide real ones.
 */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        ClockObserver.poll()
        Svc.log("time_changed", "action" to (intent.action ?: "?"))
        Svc.recompute("time_changed")
    }
}

/**
 * Hourly clock sync. This phone cannot host the Wi-Fi Direct group and stay on home
 * Wi-Fi simultaneously, so syncing means dropping the group for a moment. Never while
 * ringing, and never for long.
 */
object SyncWindow {
    @Volatile var lastAttemptMs = 0L
    @Volatile var lastOkMs = 0L
    @Volatile var running = false

    fun run(ctx: Context) {
        if (Svc.settings.role != Role.ALARM) { ClockObserver.poll(); return }
        if (Svc.session != null || Svc.testActive || running) return
        running = true
        lastAttemptMs = System.currentTimeMillis()
        Thread({
            runCatching {
                val hadGroup = Group.running
                if (hadGroup) {
                    Svc.log("sync_group_down")
                    Group.stop(ctx)
                    Thread.sleep(3_000)
                }
                // Give Android a moment to reassociate with home Wi-Fi, then read time.
                repeat(12) {
                    if (Svc.session != null) return@runCatching
                    ClockObserver.poll()
                    if (ClockObserver.healthy()) return@repeat
                    Thread.sleep(5_000)
                }
                if (ClockObserver.healthy()) {
                    lastOkMs = System.currentTimeMillis()
                    Svc.log("sync_ok", "offsetMs" to ClockObserver.offsetMs.toString())
                } else {
                    Svc.log("sync_fail", "source" to ClockObserver.source)
                }
                if (hadGroup) {
                    Group.start(ctx, Svc.settings)
                    Svc.log("sync_group_up")
                }
            }.onFailure { Svc.log("sync_error", "error" to it.toString()) }
            running = false
        }, "sync-window").start()
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
