package com.mtrinh.fobalarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants

/**
 * Step 1: answer the platform questions that only these phones can answer.
 * Every row is something SPEC.md currently asserts or guesses.
 */
data class Fact(val key: String, val value: String, val verdict: Verdict)
enum class Verdict { GOOD, BAD, UNKNOWN }

object Probe {

    fun collect(ctx: Context): List<Fact> {
        val out = mutableListOf<Fact>()
        fun add(k: String, v: String, ok: Boolean?) =
            out.add(Fact(k, v, when (ok) { true -> Verdict.GOOD; false -> Verdict.BAD; null -> Verdict.UNKNOWN }))

        // ---- device identity ----
        add("model", "${Build.MODEL} (${Build.DEVICE})", null)
        add("api", "${Build.VERSION.SDK_INT} / Android ${Build.VERSION.RELEASE}", Build.VERSION.SDK_INT >= 31)
        add("build", Build.DISPLAY, null)

        // ---- sensors: does the snooze gesture exist at all? ----
        val sm = ctx.getSystemService(SensorManager::class.java)
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        add("gyroscope", gyro?.let { "${it.name}" } ?: "ABSENT", gyro != null)
        val grv = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        add("game_rotation_vector", grv?.let { "present" } ?: "ABSENT", grv != null)
        val rv = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        add("rotation_vector", rv?.let { "present" } ?: "absent", null)

        // ---- alarm + foreground service gates ----
        val am = ctx.getSystemService(AlarmManager::class.java)
        add("canScheduleExactAlarms", am.canScheduleExactAlarms().toString(), am.canScheduleExactAlarms())

        // ---- hibernation: the most likely total kill (SPEC.md invariants 5 & 7) ----
        val fut = PackageManagerCompat.getUnusedAppRestrictionsStatus(ctx)
        val hib = try { fut.get() } catch (e: Exception) { -1 }
        val hibText = when (hib) {
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> "FEATURE_NOT_AVAILABLE"
            UnusedAppRestrictionsConstants.DISABLED -> "DISABLED (good)"
            UnusedAppRestrictionsConstants.API_30_BACKPORT,
            UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_31 -> "ENABLED — app will be hibernated"
            UnusedAppRestrictionsConstants.ERROR -> "ERROR"
            else -> "unknown($hib)"
        }
        add("unusedAppRestrictions", hibText, hib == UnusedAppRestrictionsConstants.DISABLED ||
                hib == UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE)

        // ---- notifications / full screen intent ----
        val nm = ctx.getSystemService(NotificationManager::class.java)
        add("canUseFullScreenIntent", nm.canUseFullScreenIntent().toString(), nm.canUseFullScreenIntent())
        add("notificationsEnabled", nm.areNotificationsEnabled().toString(), nm.areNotificationsEnabled())

        // ---- DND / zen: can anything mute STREAM_ALARM before 04:00? ----
        add("interruptionFilter", nm.currentInterruptionFilter.let {
            when (it) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL (good)"
                NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE — total silence"
                NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
                else -> "unknown($it)"
            }
        }, nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL)
        add("isNotificationPolicyAccessGranted", nm.isNotificationPolicyAccessGranted.toString(), null)
        val rules = try { nm.automaticZenRules } catch (e: Exception) { emptyMap() }
        val enabledRules = rules.values.filter { it.isEnabled }
        add("automaticZenRules", if (enabledRules.isEmpty()) "none enabled (good)"
            else enabledRules.joinToString { it.name }, enabledRules.isEmpty())

        // ---- audio ----
        val audio = ctx.getSystemService(AudioManager::class.java)
        add("alarmVolume", "${audio.getStreamVolume(AudioManager.STREAM_ALARM)}/${audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)}", null)
        add("isStreamMute(ALARM)", audio.isStreamMute(AudioManager.STREAM_ALARM).toString(),
            !audio.isStreamMute(AudioManager.STREAM_ALARM))
        add("isVolumeFixed", audio.isVolumeFixed.toString(), !audio.isVolumeFixed)

        // ---- vibration: not the unconditional backstop SPEC once claimed ----
        val cr = ctx.contentResolver
        val vibOn = Settings.System.getInt(cr, "vibrate_on", -1)
        add("vibrate_on", vibOn.toString(), vibOn == 1)
        val vibIntensity = Settings.System.getInt(cr, "alarm_vibration_intensity", -1)
        add("alarm_vibration_intensity", "$vibIntensity (0=OFF)", vibIntensity != 0)

        // ---- Wi-Fi Direct + concurrency: decides whether dev tooling can coexist with the group ----
        val wm = ctx.getSystemService(WifiManager::class.java)
        add("p2pSupported", ctx.packageManager.hasSystemFeature("android.hardware.wifi.direct").toString(),
            ctx.packageManager.hasSystemFeature("android.hardware.wifi.direct"))
        val staConc = try { wm.isStaApConcurrencySupported } catch (e: Throwable) { null }
        add("staApConcurrency", staConc?.toString() ?: "unavailable", staConc)
        val staBridged = try { wm.isStaBridgedApConcurrencySupported } catch (e: Throwable) { null }
        add("staBridgedApConcurrency", staBridged?.toString() ?: "unavailable", null)
        add("wifiEnabled", wm.isWifiEnabled.toString(), null)

        // ---- power: Motorola overcharge protection caps at 80%, and may report not-charging ----
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val batt = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val plugged = (batt?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = batt?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        add("battery", "$pct%  plugged=$plugged  status=$status  ${temp}C", plugged)
        add("chargeCounter", bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toString(), null)

        // ---- clock ----
        val netClock = try {
            android.os.SystemClock.currentNetworkTimeClock().millis().let {
                "offset ${it - System.currentTimeMillis()} ms"
            }
        } catch (e: Exception) { "UNAVAILABLE (${e.javaClass.simpleName})" }
        add("networkTime", netClock, !netClock.startsWith("UNAVAILABLE"))
        add("autoTime", Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 0).toString(),
            Settings.Global.getInt(cr, Settings.Global.AUTO_TIME, 0) == 1)
        add("timezone", java.util.TimeZone.getDefault().id, null)

        return out
    }
}
