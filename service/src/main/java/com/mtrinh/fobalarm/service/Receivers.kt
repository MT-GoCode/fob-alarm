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
    fun configure(v: String) { version = v }
    fun ensure(ctx: Context) = Svc.init(ctx, version)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Boot.ensure(ctx)
        if (intent.action == ControllerWatch.ACTION_REMOTE_DISMISS) {
            // Handed to the link service: it holds a wake lock and has no 10 s budget.
            runCatching { ctx.startService(Intent(ctx, LinkService::class.java)
                .setAction(ControllerWatch.ACTION_REMOTE_DISMISS)) }
                .onFailure { Svc.log("remote_dismiss_handoff_failed", "error" to it.toString()) }
            return
        }
        if (Svc.settings.role != Role.ALARM) return      // controller never fires alarms
        // AlarmManager's own wake lock ends when onReceive returns and the FGS-start
        // allowlist is ~10s, so take a lock and start the service SYNCHRONOUSLY.
        val wl = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:receiver")
        wl.acquire(60_000)
        // True when the ring service has been asked to start: the lock is then handed to
        // it and deliberately NOT released here. startForegroundService only posts to the
        // main looper, so the service starts after this returns and takes its own lock.
        var handedOff = false
        try {
            when (intent.action) {
                Scheduler.ACTION_FIRE -> {
                    Svc.log("alarm_fired")
                    RingService.start(ctx); handedOff = true
                }
                // A test that arrives after its window has lapsed simply does nothing.
                Scheduler.ACTION_TEST -> if (Svc.de.pendingTest) RingService.start(ctx)
                Scheduler.ACTION_WATCHDOG -> {
                    val open = Svc.session
                    if (open != null && open.endsByMs > System.currentTimeMillis()) {
                        Svc.log("watchdog_resurrect")
                        RingService.start(ctx, resume = true); handedOff = true
                    } else if (open != null) {
                        Svc.recompute("watchdog_after_cap")     // reaps it as CAPPED
                    }
                }
                Scheduler.ACTION_TICK -> {
                    Svc.pruneHistory()
                    Svc.recompute("hourly_tick")
                    Svc.loadRoomAsync("hourly_tick")
                    if (!LinkService.alive) LinkService.start(ctx)   // exact-alarm exempt, so allowed
                    SyncWindow.run(ctx)
                }
                Scheduler.ACTION_ARMGATE -> ArmGateRunner.run(ctx)
                // From the notification action: works even with no activity on screen.
                Scheduler.ACTION_DISMISS -> {
                    val open = Svc.session
                    when {
                        open != null -> runCatching {
                            Svc.dismiss(open.ringId, java.util.UUID.randomUUID().toString(), Actor.ALARM)
                        }
                        Svc.testActive -> Svc.stopTest()
                        else -> RingService.stop(ctx)
                    }
                }
            }
        } finally {
            if (!handedOff && wl.isHeld) wl.release()
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
            RingService.start(ctx, resume = true)
        }
        // Pending alarms survive a package replace, but the exact-alarm re-check can wipe
        // them, and a reboot clears everything. Rebuild unconditionally.
        Svc.recompute("boot:${intent.action}")
        // BOOT_COMPLETED arrives after the first unlock, so the database is readable now.
        // (USER_UNLOCKED is registered-receivers-only and never reaches a manifest receiver.)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Svc.loadRoomAsync("boot_completed")
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
        // Never drop the group in the minutes before the alarm: the controller would
        // still be rejoining when the ring starts, and remote dismiss would be dead.
        val next = Svc.lastNextFire?.atMs ?: Long.MAX_VALUE
        if (next - System.currentTimeMillis() < 10 * 60_000L) return
        // The system's network clock is usually fresh without touching the network at
        // all; only when it is stale is the group worth dropping.
        ClockObserver.poll()
        if (ClockObserver.healthy() && System.currentTimeMillis() - lastOkMs < 24 * 3600_000L) return
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
                for (i in 0 until 12) {
                    if (Svc.session != null) break
                    ClockObserver.poll()
                    if (ClockObserver.healthy()) break      // was `return@repeat`, i.e. continue
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

/** The persisted crash file, in DE storage so it survives a reboot without unlock. */
object Crash {
    fun file(ctx: Context) = java.io.File(ctx.createDeviceProtectedStorageContext().filesDir, "crash.txt")
}

/** Hibernation and force-stop both cancel every PendingIntent. Make it visible. */
object ForceStopDetector {
    fun check(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            if (am.getHistoricalProcessStartReasons(3).any { it.wasForceStopped() })
                Svc.log("force_stopped_detected")
        }
    }
}
