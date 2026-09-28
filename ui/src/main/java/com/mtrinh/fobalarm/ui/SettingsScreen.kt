package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mtrinh.fobalarm.core.Settings
import com.mtrinh.fobalarm.core.Snapshot

@Composable
fun SettingsScreen(
    app: AppState,
    s: Snapshot,
    isAlarmRole: Boolean,
    deviceSettings: (@Composable () -> Unit)?,   // alarm phone only: pairing, ringtone, password
    roleSwitcher: @Composable () -> Unit,        // both phones
) {
    var showUnlock by remember { mutableStateOf(false) }
    val hasPassword = s.settings.hasPassword
    val locked = hasPassword && !app.unlocked

    Page(title = "Settings", snapshot = s) {

        // Lock state first, because it decides what else can be changed.
        if (hasPassword) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (locked) "Locked" else "Unlocked", fontSize = T.button,
                color = if (locked) Bad else Good, modifier = Modifier.weight(1f))
            if (locked) Button(onClick = { showUnlock = true }) { Text("Unlock") }
        }

        Section("Alarm")
        LockedRow(locked) { on ->
            TimeSetting("Alarm time", s.settings.defaultAlarmTime, enabled = on) { v ->
                app.patch("Alarm time") { it.copy(defaultAlarmTime = v) }
            }
        }
        LockedRow(locked) { on ->
            SliderSetting("Volume", s.settings.alarmVolumePercent,
                min = Settings.VOLUME_FLOOR, max = 100, step = 5, suffix = "%", enabled = on) { v ->
                app.patch("Volume") { it.copy(alarmVolumePercent = v) }
            }
        }
        LockedRow(locked) { on ->
            ToggleSetting("Vibrate", s.settings.vibrate, enabled = on) { v -> app.patch("Vibrate") { it.copy(vibrate = v) } }
        }
        LockedRow(locked) { on ->
            DurationSetting("Stop ringing after", s.settings.maxRingMinutes * 60,
                minSeconds = 5 * 60, maxSeconds = 120 * 60, enabled = on) { secs ->
                app.patch("Stop ringing after") { it.copy(maxRingMinutes = secs / 60) }
            }
        }

        Section("Snooze")
        LockedRow(locked) { on ->
            DurationSetting("Snooze for", s.settings.snoozeSeconds,
                minSeconds = 10, maxSeconds = Settings.SNOOZE_CEILING_S,
                allowSeconds = true, enabled = on) { secs ->
                app.patch("Snooze for") { it.copy(snoozeSeconds = secs) }
            }
        }
        LockedRow(locked) { on ->
            NumberSetting("Turn the phone to snooze", s.settings.snoozeThresholdDegrees,
                min = 60, max = 720, unit = "°", enabled = on) { v ->
                app.patch("Turn the phone to snooze") { it.copy(snoozeThresholdDegrees = v) }
            }
        }

        Section("Test", if (isAlarmRole) "Rings this phone now." else "Rings the alarm phone now, for up to a minute.")
        Row(horizontalArrangement = Arrangement.spacedBy(S.sm)) {
            Button(onClick = { app.testRing(false) }, modifier = Modifier.weight(1f)) { Text("Test ring") }
            OutlinedButton(onClick = { app.testRing(true) }, modifier = Modifier.weight(1f)) { Text("Silent test") }
        }
        app.testMessage?.let {
            Spacer(Modifier.height(S.sm))
            Text(it, fontSize = T.label, color = if (app.testOk) MaterialTheme.colorScheme.primary else Bad)
        }

        deviceSettings?.invoke()
        roleSwitcher()
    }

    if (showUnlock) {
        PasswordDialog(
            title = "Unlock settings",
            onSubmit = { secret, result -> app.unlock(secret, result) },
            onDismiss = { showUnlock = false },
        )
    }
}

/** Locked settings stay readable but look disabled; tapping says why rather than doing nothing. */
@Composable
fun LockedRow(locked: Boolean, content: @Composable (enabled: Boolean) -> Unit) {
    var explain by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.padding(end = if (locked) 24.dp else 0.dp)) { content(!locked) }
        if (locked) {
            Box(Modifier.matchParentSize().clickableNoRipple { explain = true })
            Icon(Icons.Default.Lock, contentDescription = "Locked", tint = Muted,
                modifier = Modifier.size(16.dp).align(Alignment.CenterEnd))
        }
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Locked") },
            text = { Text("Unlock at the top of this screen to change it.", fontSize = T.label) },
            confirmButton = { TextButton(onClick = { explain = false }) { Text("OK") } })
    }
}

@Composable
fun ToggleSetting(label: String, value: Boolean, enabled: Boolean = true, onSet: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = S.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = T.body, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onSet, enabled = enabled)
    }
}
