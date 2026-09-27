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
            exactAlarm = am.canScheduleExactAlarms(),
            foregroundService = RingService.serviceAlive,
            p2pSupported = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
            // TYPE_ROTATION_VECTOR exists on gyro-less devices and proves nothing --
            // probe TYPE_GYROSCOPE directly, or a 2029 replacement phone silently
            // ships a snooze gesture that cannot work.
            gyroscopePresent = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
            staApConcurrent = runCatching { wm.isStaApConcurrencySupported }.getOrDefault(false),
            groupCredentialsSet = !settings.ssid.isNullOrBlank() && !settings.passphrase.isNullOrBlank(),
            localNetworkPermission = hasLocalNetwork(ctx),
            notificationPolicyAccess = nm.isNotificationPolicyAccessGranted,
            dndAllowsAlarms = dndAllowsAlarms(ctx, nm),
            volumeNotFixed = !audio.isVolumeFixed && !audio.isStreamMute(AudioManager.STREAM_ALARM),
            fullScreenIntent = nm.canUseFullScreenIntent(),
            notHibernating = notHibernating(ctx),
            thermalOk = pm.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE,
            audioPlayable = audioPlayable(ctx, settings),
            // NEVER isCharging(), and never expect 100%: Motorola's Overcharge protection
            // caps at 80% and may report not-charging at the plateau. Checking isCharging
            // would chirp every night forever -- the worst-shaped bug available.
            powerOk = plugged && pct > 50,
            vibrationEnabled = vibrationEnabled(ctx),
            freeDiskOk = freeBytes(ctx) > 50L * 1024 * 1024,
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

    /** Vibration is NOT an unconditional backstop: one tap in Settings removes it. */
    private fun vibrationEnabled(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val on = ASettings.System.getInt(cr, "vibrate_on", 1)
        val intensity = ASettings.System.getInt(cr, "alarm_vibration_intensity", 2)
        return on == 1 && intensity != 0
    }

    private fun notHibernating(ctx: Context): Boolean = runCatching {
        when (PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx).get()) {
            UnusedAppRestrictionsConstants.DISABLED,
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> true
            else -> false
        }
    }.getOrDefault(false)

    /** Readability is checked at the arm gate, never at 04:00. */
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
