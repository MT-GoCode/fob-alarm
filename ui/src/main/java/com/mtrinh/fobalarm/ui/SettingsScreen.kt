package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtrinh.fobalarm.core.Settings

/**
 * ONE settings editor, rendered on both phones. Everything is editable from both --
 * reach is not the control. The settings that can silence tomorrow are password-gated,
 * enforced in :core so the HTTP path cannot bypass it. SPEC.md section 8.
 */
@Composable
fun SettingsScreen(
    app: AppState,
    s: com.mtrinh.fobalarm.core.Snapshot,
    deviceSettings: (@Composable () -> Unit)?,
) {
    var showUnlock by remember { mutableStateOf(false) }
    val unlocked = app.token != null

    Page(snapshot = s) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (!unlocked) TextButton(onClick = { showUnlock = true }) { Text("Unlock") }
            else Text("unlocked", fontSize = 12.sp, color = Good)
        }
        Text("Gated settings need the password. Dismiss never does.",
            fontSize = 11.sp, color = Muted)

        // --- gated -----------------------------------------------------------
        Section("Alarm")
        TimeSetting("Alarm time", s.settings.defaultAlarmTime,
            help = "Every day, unless you set a one-off change.") { v ->
            app.patch { it.copy(defaultAlarmTime = v) }
        }
        SliderSetting("Volume", s.settings.alarmVolumePercent,
            min = Settings.VOLUME_FLOOR, max = 100, step = 5, suffix = "%",
            help = "Cannot go below ${Settings.VOLUME_FLOOR}%.") { v ->
            app.patch { it.copy(alarmVolumePercent = v) }
        }
        ChoiceSetting("Give up after", "Emergency stop. The siren runs this long, then stops.",
            listOf("15m" to 15, "30m" to 30, "45m" to 45, "1h" to 60, "2h" to 120),
            s.settings.maxRingMinutes) { v -> app.patch { it.copy(maxRingMinutes = v) } }
        TimeSetting("Nightly check", s.settings.armGateTime,
            help = "Three chirps if something would stop tomorrow's alarm. Silence means healthy.") { v ->
            app.patch { it.copy(armGateTime = v) }
        }

        Section("Snooze")
        ChoiceSetting("Snooze length", null,
            listOf("30s" to 30, "1m" to 60, "2m" to 120, "5m" to 300, "10m" to 600),
            s.settings.snoozeSeconds) { v -> app.patch { it.copy(snoozeSeconds = v) } }
        ChoiceSetting("Rotation needed", "How far you must turn the box to snooze.",
            listOf("90°" to 90, "120°" to 120, "180°" to 180, "360°" to 360, "720°" to 720),
            s.settings.snoozeThresholdDegrees) { v ->
            app.patch { it.copy(snoozeThresholdDegrees = v) }
        }

        Section("Missed alarms")
        SliderSetting("Ring anyway if late by under", s.settings.missedGraceMinutes,
            min = 0, max = 60, step = 5, suffix = " min",
            help = "If the phone was off and comes back within this window, ring immediately.") { v ->
            app.patch { it.copy(missedGraceMinutes = v) }
        }
        TimeSetting("Still worth waking me before", s.settings.stillWorthWakingBefore,
            help = "Later than this, a missed alarm chirps instead of ringing.") { v ->
            app.patch { it.copy(stillWorthWakingBefore = v) }
        }

        // Trusting a green permission tick is not the same as knowing. SPEC.md section 8.
        Section("Test ring")
        Text("Fires in 10 seconds through the real alarm path, so lock the phone and put " +
             "it down first. Caps at 60s and stops by itself. Touches no schedule.",
            fontSize = 11.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.testRing(false) }, modifier = Modifier.weight(1f)) {
                Text("Test ring", fontSize = 13.sp)
            }
            OutlinedButton(onClick = { app.testRing(true) }, modifier = Modifier.weight(1f)) {
                Text("Silent test", fontSize = 13.sp)
            }
        }
        app.testMessage?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

        // --- alarm-phone-only: credentials, ringtone, password ---------------
        if (deviceSettings != null) {
            Section("This device")
            deviceSettings()
        }

        app.lastError?.let { Text(it, color = Bad, fontSize = 12.sp) }
        Spacer(Modifier.height(40.dp))
    }

    if (showUnlock) {
        PasswordDialog(
            title = "Unlock settings",
            body = "Expires after about two minutes idle.",
            onSubmit = { secret ->
                var ok = false
                app.unlock(secret) { ok = it }
                true                       // result surfaces via app.token
            },
            onDismiss = { showUnlock = false },
        )
    }
}
