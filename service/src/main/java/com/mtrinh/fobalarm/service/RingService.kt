package com.mtrinh.fobalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import com.mtrinh.fobalarm.core.*

/**
 * The foreground service that IS the ring: audio, vibration, the wake lock, the
 * rotation-snooze sensor, and the notification carrying the full-screen intent. Started
 * by the alarm trigger and by nothing else; it outlives the ring screen so that killing
 * the UI cannot silence an alarm.
 *
 * Also serves the test ring, which has no engine session -- see `Svc.testActive`.
 */
class RingService : Service(), SensorEventListener {

    companion object {
        const val CHANNEL_RING = "ring"
        /** Same notification, no heads-up: used while the ring screen itself is showing. */
        const val CHANNEL_RING_QUIET = "ring_quiet"
        const val CHANNEL_STATUS = "status"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "stop"
        /** Resume an open session after the process died: never a new trigger. */
        const val ACTION_RESUME = "resume"

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
        @Volatile var rotationDeg: Double = 0.0
        @Volatile var gyroBiasDps: Double = 0.0
        @Volatile var gyroStale = false
        @Volatile var rvStale = false
        @Volatile var audible: String = "-"
        @Volatile var quaternion: DoubleArray? = null

        fun start(ctx: Context, resume: Boolean = false) {
            val intent = Intent(ctx, RingService::class.java)
            if (resume) intent.action = ACTION_RESUME
            runCatching { ctx.startForegroundService(intent) }
                .onFailure {
                    // Blew the ~10s FGS allowlist window (cold start after an OTA, busy
                    // disk). Retry on a DEDICATED request code: reusing 1001 would share
                    // the PendingIntent with the real scheduled fire and, under
                    // FLAG_UPDATE_CURRENT, overwrite the next alarm with a 5s retry.
                    Svc.log("fgs_start_failed", "error" to it.toString())
                    if (resume) Scheduler.armWatchdog(ctx) else Scheduler.armFireRetry(ctx)
                }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RingService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var audio: Audio
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var acc: RotationAccumulator? = null
    private var sm: SensorManager? = null
    private var lastAccelG = 1.0

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
        if (intent != null && Svc.session == null && Svc.de.pendingTest) {
            Svc.de.pendingTestUntilMs = 0L
            Svc.testUntilMs = System.currentTimeMillis() + 60_000
            // Read from DE: the in-memory flag does not survive the process hop, and
            // losing it turns a silent test into a full-volume siren.
            val silent = Svc.de.testSilent
            Svc.log("test_ring_start", "silent" to silent.toString())
            startGesture()                    // without this the test has no sensors
            acc?.reset(); rotationDeg = 0.0; quaternion = null
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
            val src = runCatching { OccurrenceSource.valueOf(Svc.de.nextFireSource) }
                .getOrDefault(OccurrenceSource.SCHEDULED)
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
        startGesture()

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
        acc?.reset()
        rotationDeg = 0.0
    }

    private fun showRingUi() {
        runCatching {
            startActivity(Intent().setClassName(packageName, "com.mtrinh.fobalarm.ui.RingActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    // ---- the snooze gesture ------------------------------------------------

    private fun startGesture() {
        if (acc != null) return
        acc = RotationAccumulator(Svc.settings.snoozeThresholdDegrees)
        sm = getSystemService(SensorManager::class.java)
        val rv = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        rv?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = acc ?: return
        val now = System.currentTimeMillis()
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelG = Math.sqrt(
                    (e.values[0] * e.values[0] + e.values[1] * e.values[1] +
                     e.values[2] * e.values[2]).toDouble()) / SensorManager.GRAVITY_EARTH
            }
            Sensor.TYPE_GYROSCOPE -> {
                a.onGyro(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(),
                    lastAccelG, now)
                gyroBiasDps = a.gyroBiasDps
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                // Only accumulate while actually RINGING: never while snoozed or idle.
                val engineRinging = Svc.session?.phase == RingPhase.RINGING
                val testRinging = Svc.testActive && testSnoozedUntilMs == 0L
                if (!engineRinging && !testRinging) return
                val q = RotationAccumulator.quatFromSensor(e.values)
                quaternion = q
                val crossed = a.onRotationVector(q, now)
                rotationDeg = a.degrees
                if (crossed) doSnooze()
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    @Volatile private var testSnoozedUntilMs = 0L
        set(v) { field = v; Svc.testSnoozedUntilMs = v }   // the snapshot shows the countdown

    private fun doSnooze() {
        val s = Svc.session
        if (s == null) {
            // Test ring: snooze it the same way, so the gesture is genuinely testable.
            if (!Svc.testActive) return
            if (testSnoozedUntilMs > System.currentTimeMillis()) return
            testSnoozedUntilMs = System.currentTimeMillis() + Svc.settings.snoozeSeconds * 1000L
            Svc.log("test_snooze")
            audio.stop()
            acc?.reset(); rotationDeg = 0.0
            return
        }
        if (s.phase != RingPhase.RINGING) return
        Svc.onSnooze()
        audio.stop()                       // SNOOZED is fully silent: no hum, no pulse
        acc?.reset(); rotationDeg = 0.0
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
            acc?.let { gyroStale = it.gyroStale(now); rvStale = it.rvStale(now) }
            Scheduler.armWatchdog(this@RingService)
            handler.postDelayed(this, 5_000)
        }
    }

    @Volatile private var lastStartId = 0

    private fun teardown() {
        testSnoozedUntilMs = 0L
        quaternion = null
        serviceAlive = false
        handler.removeCallbacksAndMessages(null)
        audio.stop()
        sm?.unregisterListener(this); sm = null; acc = null
        rotationDeg = 0.0
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
        runCatching { sm?.unregisterListener(this) }
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
