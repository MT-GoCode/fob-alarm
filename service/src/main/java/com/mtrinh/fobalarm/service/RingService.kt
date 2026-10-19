package com.mtrinh.fobalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import com.mtrinh.fobalarm.core.*

/**
 * The foreground service that IS the ring: audio, vibration, the wake lock, and the
 * notification carrying the full-screen intent. Started by the alarm trigger and by
 * nothing else; it outlives the ring screen so that killing the UI cannot silence an
 * alarm. It registers no sensors -- snooze is ACTION_SNOOZE from the bar on the ring
 * screen, and the rotation gesture it used to own is gone.
 *
 * Also serves the test ring, which has no engine session -- see `Svc.testActive`.
 */
class RingService : Service() {

    companion object {
        const val CHANNEL_RING = "ring"
        /** Same notification, no heads-up: used while the ring screen itself is showing. */
        const val CHANNEL_RING_QUIET = "ring_quiet"
        const val CHANNEL_STATUS = "status"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "stop"
        const val ACTION_SNOOZE = "snooze"
        /** Resume an open session after the process died: never a new trigger. */
        const val ACTION_RESUME = "resume"
        /**
         * A TEST ring. Marked, not sniffed: this used to be inferred from
         * `session == null && de.pendingTest`, so a nap that began ringing between
         * `Svc.testRing` and this service starting made the test fall through to the real
         * trigger path -- which superseded the live nap session and then tore it down,
         * silencing a ring that was already sounding.
         */
        const val ACTION_TEST = "test"

        @Volatile var serviceAlive = false

        /**
         * The ring screen reports when it is on screen. Then the notification drops to a
         * quiet channel so a heads-up does not sit on top of the very screen it opens;
         * when the screen goes away it comes back loud, with its full-screen intent.
         */
        fun uiVisible(ctx: Context, visible: Boolean) {
            if (!serviceAlive) return
            ctx.getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, ringNotification(ctx, quiet = visible))
        }

        private fun ringNotification(ctx: Context, quiet: Boolean): Notification {
            val full = PendingIntent.getActivity(ctx, 0,
                Intent().setClassName(ctx.packageName, "com.mtrinh.fobalarm.ui.RingActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // A tappable body AND an explicit action: if the ring activity is ever gone
            // (recreated, or its content null) the notification is the only control surface.
            val dismiss = PendingIntent.getBroadcast(ctx, 7,
                Intent(ctx, AlarmReceiver::class.java).setAction(Scheduler.ACTION_DISMISS)
                    .setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, if (quiet) CHANNEL_RING_QUIET else CHANNEL_RING)
                .setContentTitle("Alarm ringing")
                .setContentText("Press to dismiss")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(full)
                .apply { if (!quiet) setFullScreenIntent(full, true) }
                .addAction(Notification.Action.Builder(null, "DISMISS", dismiss).build())
                .build()
        }
        @Volatile var audible: String = "-"

        /**
         * A genuine trigger for [source], which travels on the intent rather than being re-read
         * from the DE mirror that every `apply` rewrites.
         *
         * Three entry points, one per kind of start, because the kind decides everything the
         * service then does -- and a `resume: Boolean` plus a nullable source made two of the
         * four combinations meaningless and one of them fabricate a mark.
         */
        fun ring(ctx: Context, source: OccurrenceSource) =
            launch(ctx, Intent(ctx, RingService::class.java)
                .putExtra(Scheduler.EXTRA_SOURCE, source.name)) {
                // Blew the ~10s FGS allowlist window (cold start after an OTA, busy disk). Retry
                // on a DEDICATED request code: reusing 1001 would share the PendingIntent with
                // the real scheduled fire and, under FLAG_UPDATE_CURRENT, overwrite the next
                // alarm with a 5s retry.
                Scheduler.armFireRetry(ctx, source)
            }

        /** Resume an already-open session. Never a trigger, so it never needs a source. */
        fun resume(ctx: Context) =
            launch(ctx, Intent(ctx, RingService::class.java).setAction(ACTION_RESUME)) {
                Scheduler.armWatchdog(ctx)
            }

        /** A test ring. Carries no source because it is not a fire and must never arm one. */
        fun startTest(ctx: Context) =
            launch(ctx, Intent(ctx, RingService::class.java).setAction(ACTION_TEST)) {
                // Deliberately nothing: a test that could not start is a test that did not
                // happen. Retrying it as a FIRE broadcast is how a test became a real alarm.
                Svc.log("test_start_abandoned")
            }

        private fun launch(ctx: Context, intent: Intent, onFailure: () -> Unit) {
            runCatching { ctx.startForegroundService(intent) }.onFailure {
                Svc.log("fgs_start_failed", "action" to (intent.action ?: "fire"),
                    "error" to it.toString())
                onFailure()
            }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Snooze, asked for by the bar at the bottom of the ring screen. Local only --
         * `Svc.snooze` is the sole caller and there is no HTTP route, because snoozing
         * must mean reaching the box.
         */
        fun snooze(ctx: Context) {
            ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_SNOOZE))
        }
    }

    private lateinit var audio: Audio
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Boot.ensure(this)
        audio = Audio(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_STOP) { teardown(); return START_NOT_STICKY }
        // Snooze acts on the session that is already open and starts nothing.
        if (intent?.action == ACTION_SNOOZE) { doSnooze(); return START_STICKY }

        // Open the session and arm the watchdog BEFORE anything that can throw, so a
        // failure here is recoverable rather than terminal.
        serviceAlive = true
        Scheduler.armWatchdog(this)
        // Fall through the declared types: if systemExempted is refused, a service that
        // never reaches foreground is killed and sticky-restarted into the same failure,
        // forever. Log which type actually took.
        val types = listOf(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED to "systemExempted",
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE to "specialUse",
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK to "mediaPlayback",
        )
        var fgsOk = false
        for ((type, name) in types) {
            val r = runCatching { startForeground(NOTIF_ID, ringNotification(), type) }
            if (r.isSuccess) { Svc.log("foreground_started", "type" to name); fgsOk = true; break }
            Svc.log("start_foreground_failed", "type" to name,
                "error" to (r.exceptionOrNull()?.javaClass?.simpleName ?: "?"))
        }
        if (!fgsOk) Svc.log("foreground_all_types_failed")

        // A TEST ring has no engine session by design: it must not latch, schedule or
        // consume an occurrence. It caps itself and needs no dismiss to end.
        if (intent?.action == ACTION_TEST) {
            // A real ring outranks a test, always. Without this the test fell through to the
            // trigger path and killed the session that was already sounding.
            //
            // Return WITHOUT teardown. This service is already in the foreground for the live
            // ring, and `teardown` stops the audio, releases the wake lock and calls
            // `stopForeground(STOP_FOREGROUND_REMOVE)` -- so tearing down here would silence the
            // very ring this branch exists to protect, which is the original defect wearing a
            // different hat. Nothing above this point has touched the live ring: the
            // `startForeground` call re-posted the notification it already had, and the watchdog
            // was re-armed, which is harmless.
            if (Svc.session != null) {
                Svc.log("test_refused_during_ring", "ringId" to Svc.session!!.ringId)
                return START_STICKY
            }
            Svc.de.pendingTestUntilMs = 0L
            // Only if it is not already live. Svc.testRing sets this before starting the
            // service so the caller's snapshot shows the test at once; writing it again
            // here moved the window by ~45ms, and the controller keys its alert on
            // "test-$testUntilMs" -- so a poll landing inside that gap saw one value and
            // the next poll saw another, and it raised the full-screen alert TWICE for one
            // test. Still needed for the process-hop case, where the in-memory value is 0.
            if (Svc.testUntilMs <= System.currentTimeMillis()) {
                Svc.testUntilMs = System.currentTimeMillis() + 60_000
            }
            // Read from DE: the in-memory flag does not survive the process hop, and
            // losing it turns a silent test into a full-volume siren.
            val silent = Svc.de.testSilent
            Svc.log("test_ring_start", "silent" to silent.toString())
            val pm0 = getSystemService(PowerManager::class.java)
            wakeLock = pm0.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:test")
                .also { it.acquire(90_000) }
            audio.stop(); audio.start(Svc.settings, silent = silent)
            handler.removeCallbacks(heartbeat); handler.post(heartbeat)
            showRingUi()
            return START_STICKY
        }

        if (intent == null || intent.action == ACTION_RESUME) {
            // START_STICKY restart, watchdog or boot: not a trigger. Only resume; never
            // create, and never ask the engine, which would read a resumed nap as a
            // scheduled alarm superseding it.
            if (Svc.session == null && !Svc.testActive) {
                Svc.log("sticky_restart_no_session")
                teardown(); return START_NOT_STICKY
            }
        } else {
            // A real trigger during a test: the test ends and the alarm takes over. A test
            // a minute before 04:00 must never swallow 04:00.
            if (Svc.testActive) {
                Svc.testUntilMs = 0L; Svc.de.pendingTestUntilMs = 0L
                Svc.log("test_superseded_by_alarm")
            }
            // EVERY genuine trigger goes through the engine, open session or not --
            // that is what makes the §3 precedence table reachable: a scheduled alarm
            // superseding an open nap mints a fresh ringId, and a nap arriving during a
            // real alarm is dropped.
            val prev = Svc.session?.ringId
            val src = Scheduler.sourceOf(intent)
            // A wrong latch beats silence: never let an engine throw stop the ring.
            val engine = runCatching { Svc.onTrigger(src) }
                .onFailure { Svc.log("trigger_failed", "error" to it.toString()) }
            if (Svc.session == null) {
                if (engine.isSuccess) {
                    // The engine answered and the answer was "this trigger is for nothing"
                    // (a duplicate, or a resurrect after the cap). Honour it.
                    teardown(); return START_NOT_STICKY
                }
                // The engine threw. Open a REAL session so the ring screen, the dismiss
                // button, the watchdog and the DE mirror all behave normally.
                Svc.log("ring_without_session")
                runCatching { Svc.forceSession(src) }
            }
            val s = Svc.session
            // A supersede replaces the session under us: restart audio on the new one.
            if (s != null && prev != null && prev != s.ringId) beginAudio()
        }

        val pm = getSystemService(PowerManager::class.java)
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:session")
                .also { it.acquire(4 * 3600_000L) }
        }

        Scheduler.armWatchdog(this)

        if (Svc.session?.phase == RingPhase.RINGING) beginAudio()
        else if (Svc.session == null) {
            // Test ring: no engine session. Read silence from DE, which survives the
            // process hop -- the in-memory flag does not.
            audio.stop(); audio.start(Svc.settings, silent = Svc.de.testSilent)
        }
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
        showRingUi()
        return START_STICKY
    }

    private fun beginAudio() {
        audio.stop()                 // release any previous player first
        audio.start(Svc.settings)
    }

    private fun showRingUi() {
        runCatching {
            startActivity(Intent().setClassName(packageName, "com.mtrinh.fobalarm.ui.RingActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    // ---- snooze ------------------------------------------------------------

    @Volatile private var testSnoozedUntilMs = 0L
        set(v) { field = v; Svc.testSnoozedUntilMs = v }   // the snapshot shows the countdown

    /** Snooze a ringing alarm, or extend one already snoozing. The engine decides which. */
    private fun doSnooze() {
        val now = System.currentTimeMillis()
        val s = Svc.session
        if (s == null) {
            // Test ring: snoozes and extends the same way, so both are genuinely testable.
            if (!Svc.testActive) return
            val set = Svc.settings
            if (testSnoozedUntilMs > now) {
                // Mid-snooze: the same derivation the engine uses for a real session.
                val at = Engine.extendableAt(set, testSnoozedUntilMs) ?: return
                if (now < at) return
                Svc.log("test_snooze_extended")
            } else {
                Svc.log("test_snooze")
            }
            testSnoozedUntilMs = now + set.snoozeSeconds * 1000L
            audio.stop()
            return
        }
        // No phase guard here: Engine.snooze admits a RINGING session, and a SNOOZED one
        // only once canExtendSnooze allows it. One rule, in one place.
        Svc.onSnooze()
        audio.stop()                       // SNOOZED is fully silent: no hum, no pulse
    }

    // ---- heartbeat ---------------------------------------------------------

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (Svc.testUntilMs > 0L) {
                if (now >= Svc.testUntilMs) {
                    Svc.log("test_ring_end")
                    Svc.testUntilMs = 0L
                    teardown(); return
                }
                if (testSnoozedUntilMs > 0L) {
                    if (now >= testSnoozedUntilMs) {
                        testSnoozedUntilMs = 0L
                        audio.start(Svc.settings, silent = Svc.de.testSilent)
                    }
                    // Snoozed is silent: never let the heartbeat resurrect a tone.
                } else if (!Svc.de.testSilent) {
                    audio.heartbeat(Svc.settings)
                }
                audible = audio.audible
                handler.postDelayed(this, 5_000)
                return
            }

            val s = Svc.session
            if (s == null) { teardown(); return }

            if (now >= s.endsByMs) {
                audio.stop()
                Svc.endSession(Outcome.CAPPED)
                teardown(); return
            }

            when (s.phase) {
                RingPhase.SNOOZED -> {
                    if (s.snoozeUntilMs != null && now >= s.snoozeUntilMs!!) {
                        Svc.onTrigger(s.trigger)     // re-ring, same session
                        beginAudio()
                    }
                    audible = "snoozed"
                }
                RingPhase.RINGING -> {
                    audio.heartbeat(Svc.settings)
                    audible = audio.audible
                }
            }
            Scheduler.armWatchdog(this@RingService)
            handler.postDelayed(this, 5_000)
        }
    }

    @Volatile private var lastStartId = 0

    private fun teardown() {
        testSnoozedUntilMs = 0L
        serviceAlive = false
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        Scheduler.cancelWatchdog(this)
        runCatching { wakeLock?.release() }; wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // With the startId, a teardown racing a fresh start cannot kill the new session.
        if (lastStartId != 0) stopSelf(lastStartId) else stopSelf()
    }

    override fun onDestroy() {
        serviceAlive = false
        handler.removeCallbacksAndMessages(null)
        runCatching { audio.stop() }
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    // ---- notifications -----------------------------------------------------

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RING, "Alarm ringing",
            NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RING_QUIET, "Alarm ringing (screen showing)",
            NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Status",
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
    }

    private fun ringNotification(): Notification = ringNotification(this, quiet = false)
}
