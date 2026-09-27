package com.mtrinh.fobalarm.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as ASettings
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import com.mtrinh.fobalarm.core.Gates
import com.mtrinh.fobalarm.core.Settings
import java.io.File

object GateEval {

    // ---------------------------------------------------------------------
    // Gate evaluation is EXPENSIVE and must never run on the main thread:
    //   - getUnusedAppRestrictionsStatus() returns a future backed by a service
    //     binding; blocking on it from the main thread deadlocks into an ANR.
    //   - audioPlayable prepares a MediaPlayer, which is synchronous decode setup.
    // snapshot() is called from Application.onCreate and again every 500ms while
    // ringing, so both were being hit constantly. Evaluate on a worker and let
    // snapshot() read the last cached result. SPEC.md section 4 also requires audio
    // readability to be checked at the nightly arm gate, not on the ring path.
    // ---------------------------------------------------------------------

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var cached: Gates? = null
    @Volatile private var refreshing = false
    @Volatile private var audioOk: Boolean? = null
    @Volatile private var hibernationOk: Boolean? = null

    /** Cheap, main-thread safe, never blocks. Kicks a refresh if the cache is stale. */
    fun current(ctx: Context, settings: Settings, nextFireExists: Boolean): Gates {
        val c = cached
        if (c == null || System.currentTimeMillis() - c.evaluatedAtMs > 15_000) refresh(ctx, settings, nextFireExists)
        return c ?: unknownGates(nextFireExists)
    }

    fun refresh(ctx: Context, settings: Settings, nextFireExists: Boolean) {
        if (refreshing) return
        refreshing = true
        worker.execute {
            runCatching { cached = evaluate(ctx, settings, nextFireExists) }
                .onFailure { Svc.log("gate_eval_failed", "error" to it.toString()) }
            refreshing = false
        }
    }

    /**
     * Only ever seen in the fraction of a second before the first evaluation lands.
     * evaluatedAtMs == 0 marks it as NOT MEASURED, and the UI must say so rather than
     * drawing nineteen red crosses for checks that have never run.
     */
    private fun unknownGates(nextFireExists: Boolean) = Gates(
        evaluatedAtMs = 0, scheduleExists = nextFireExists, exactAlarm = false,
        foregroundService = false, p2pSupported = false, gyroscopePresent = false,
        staApConcurrent = false, groupCredentialsSet = false, localNetworkPermission = false,
        notificationPolicyAccess = false, dndAllowsAlarms = false, volumeNotFixed = false,
        fullScreenIntent = false, notHibernating = false, thermalOk = false,
        audioPlayable = false, powerOk = false, vibrationEnabled = false,
        freeDiskOk = false, noBluetoothAudio = false)

    @Volatile var lastError: String? = null

    /** One gate must never be able to take the other eighteen down with it. */
    private inline fun probe(name: String, default: Boolean, block: () -> Boolean): Boolean =
        try { block() } catch (t: Throwable) {
            Svc.log("gate_probe_failed", "gate" to name, "error" to t.toString())
            lastError = "$name: ${t.javaClass.simpleName}"
            default
        }

    /** Blocking. Worker thread and the arm gate only. */
    fun evaluate(ctx: Context, settings: Settings, nextFireExists: Boolean): Gates {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val audio = ctx.getSystemService(AudioManager::class.java)
        val wm = ctx.getSystemService(WifiManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val sm = ctx.getSystemService(SensorManager::class.java)
        val cr = ctx.contentResolver

        val batt = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val pct = ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return Gates(
            evaluatedAtMs = System.currentTimeMillis(),
            scheduleExists = nextFireExists,
            exactAlarm = probe("exactAlarm", false) { am.canScheduleExactAlarms() },
            // Whether we are ALLOWED to run the ring service, not whether it is running
            // right now -- it only runs during a ring, so the old check was a permanent X.
            foregroundService = probe("foregroundService", false) {
                nm.areNotificationsEnabled()
            },
            p2pSupported = probe("p2pSupported", false) {
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
            },
            gyroscopePresent = probe("gyroscopePresent", false) {
                sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            },
            staApConcurrent = probe("staApConcurrent", false) { wm.isStaApConcurrencySupported },
            groupCredentialsSet = !settings.ssid.isNullOrBlank() && !settings.passphrase.isNullOrBlank(),
            localNetworkPermission = probe("localNetworkPermission", false) { hasLocalNetwork(ctx) },
            notificationPolicyAccess = probe("notificationPolicyAccess", false) {
                nm.isNotificationPolicyAccessGranted
            },
            dndAllowsAlarms = probe("dndAllowsAlarms", true) { dndAllowsAlarms(ctx, nm) },
            volumeNotFixed = probe("volumeNotFixed", true) {
                !audio.isVolumeFixed && !audio.isStreamMute(AudioManager.STREAM_ALARM)
            },
            fullScreenIntent = probe("fullScreenIntent", false) { nm.canUseFullScreenIntent() },
            notHibernating = probe("notHibernating", true) {
                hibernationOk ?: notHibernating(ctx).also { hibernationOk = it }
            },
            thermalOk = probe("thermalOk", true) {
                pm.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE
            },
            // Readability is checked at the nightly arm gate, never at 04:00.
            audioPlayable = probe("audioPlayable", false) {
                audioOk ?: audioPlayable(ctx, settings).also { audioOk = it }
            },
            // NEVER isCharging(), and never expect 100%: Motorola's Overcharge protection
            // caps at 80% and may report not-charging at the plateau.
            powerOk = probe("powerOk", true) { plugged && pct > 50 },
            vibrationEnabled = probe("vibrationEnabled", true) { vibrationEnabled(ctx) },
            freeDiskOk = probe("freeDiskOk", true) { freeBytes(ctx) > 50L * 1024 * 1024 },
            // A bathroom speaker auto-connecting at 03:00 routes the alarm out of the box.
            noBluetoothAudio = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none {
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            },
        )
    }

    private fun hasLocalNetwork(ctx: Context): Boolean {
        // On API 36 the LNP gate is NEARBY_WIFI_DEVICES; ACCESS_LOCAL_NETWORK only becomes
        // the gate at targetSdk 37. Check what actually applies today.
        return ctx.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checking the CONSOLIDATED policy at 22:00 is not enough: Bedtime mode is a scheduled
     * AutomaticZenRule that is inactive at 22:00 and mutes STREAM_ALARM by 04:00. Enumerate
     * the rules instead. SPEC.md section 4.
     */
    fun dndAllowsAlarms(ctx: Context, nm: NotificationManager): Boolean {
        if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE) return false
        val rules = runCatching { nm.automaticZenRules }.getOrDefault(emptyMap())
        val hostile = rules.values.any { rule ->
            if (!rule.isEnabled) return@any false
            val zp = rule.zenPolicy ?: return@any false
            zp.priorityCategoryAlarms == android.service.notification.ZenPolicy.STATE_DISALLOW
        }
        if (hostile) return false
        if (nm.isNotificationPolicyAccessGranted) {
            val p = runCatching { nm.consolidatedNotificationPolicy }.getOrNull()
            if (p != null && (p.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0) {
                return false
            }
        }
        return true
    }

    /**
     * Vibration is NOT an unconditional backstop: one tap in Settings removes it.
     *
     * We can only see half of that. `vibrate_on` is readable, but
     * `alarm_vibration_intensity` is an @hide setting and throws SecurityException from
     * Android 12 on -- it is restricted to system apps, and there is no public API for
     * it. So we check what we can and note that intensity is unobservable rather than
     * throwing on every refresh.
     */
    private fun vibrationEnabled(ctx: Context): Boolean {
        val hasVibrator = runCatching {
            ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator.hasVibrator()
        }.getOrDefault(true)
        val on = runCatching { ASettings.System.getInt(ctx.contentResolver, "vibrate_on", 1) }
            .getOrDefault(1)
        return hasVibrator && on == 1
    }

    private fun notHibernating(ctx: Context): Boolean = runCatching {
        when (PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx)
            .get(3, java.util.concurrent.TimeUnit.SECONDS)) {
            UnusedAppRestrictionsConstants.DISABLED,
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> true
            else -> false
        }
    }.getOrDefault(false)

    /** Called by the arm gate to force a fresh read of the slow checks. */
    fun invalidateSlowChecks() { audioOk = null; hibernationOk = null }

    fun audioPlayable(ctx: Context, settings: Settings): Boolean = runCatching {
        val uri = Audio.resolveUri(ctx, settings.ringtoneUri) ?: return false
        val mp = MediaPlayer()
        mp.setDataSource(ctx, uri)
        mp.prepare()
        mp.release()
        true
    }.getOrDefault(false)

    private fun freeBytes(ctx: Context): Long = runCatching {
        val f = File(ctx.filesDir.absolutePath)
        f.usableSpace
    }.getOrDefault(Long.MAX_VALUE)
}

/**
 * Clock observation. The API exposes no timestamp of the last successful sync, so
 * lastSyncOkMs is APP-OBSERVED: we poll, and record when our own observation succeeded.
 * The underlying network time may itself be arbitrarily stale. SPEC.md section 1.
 */
object ClockObserver {
    @Volatile var lastAttemptMs: Long = 0
    @Volatile var lastOkMs: Long = 0
    @Volatile var offsetMs: Long = 0
    @Volatile var source: String = "unknown"

    fun poll() {
        lastAttemptMs = System.currentTimeMillis()
        runCatching {
            // currentNetworkTimeClock() itself never throws; DateTimeException comes out of millis().
            val net = android.os.SystemClock.currentNetworkTimeClock().millis()
            offsetMs = net - System.currentTimeMillis()
            lastOkMs = System.currentTimeMillis()
            source = "network"
        }.onFailure { source = "unavailable:${it.javaClass.simpleName}" }
    }

    fun staleByMs(): Long = if (lastOkMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastOkMs

    /** Observed available within 36h AND |offset| < 60s. */
    fun healthy(): Boolean = lastOkMs != 0L &&
            staleByMs() < 36 * 3600_000L && kotlin.math.abs(offsetMs) < 60_000
}
