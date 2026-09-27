package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtrinh.fobalarm.core.Settings
import com.mtrinh.fobalarm.core.Snapshot

/**
 * One settings screen for both phones. Locked settings are visibly locked rather than
 * hidden, so the screen never lies about what can be changed.
 */
@Composable
fun SettingsScreen(
    app: AppState,
    s: Snapshot,
    deviceSettings: (@Composable () -> Unit)?,
) {
    var showUnlock by remember { mutableStateOf(false) }
    val hasPassword = s.settings.hasPassword
    val locked = hasPassword && app.token == null

    Page(title = "Settings", snapshot = s) {

        // --- password, first, because it decides whether anything below is editable ---
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (!hasPassword) "No password set" else if (locked) "Locked" else "Unlocked",
                        fontSize = 15.sp, color = if (locked) Bad else Good)
                    Text(
                        if (!hasPassword) "Anyone holding this phone can change the alarm."
                        else if (locked) "Unlock to change the settings marked with a lock."
                        else "Locks itself again after about two minutes.",
                        fontSize = 12.sp, color = Muted)
                }
                if (locked) Button(onClick = { showUnlock = true }) { Text("Unlock") }
            }
        }

        // --- alarm ---
        Section("Alarm")
        LockedRow(locked) {
            TimeSetting("Alarm time", s.settings.defaultAlarmTime) { v ->
                app.patch { it.copy(defaultAlarmTime = v) }
            }
        }
        LockedRow(locked) {
            SliderSetting("Volume", s.settings.alarmVolumePercent,
                min = Settings.VOLUME_FLOOR, max = 100, step = 5, suffix = "%") { v ->
                app.patch { it.copy(alarmVolumePercent = v) }
            }
        }
        LockedRow(locked) {
            ToggleSetting("Vibrate", s.settings.vibrate) { v -> app.patch { it.copy(vibrate = v) } }
        }
        LockedRow(locked) {
            ChoiceSetting("Give up after", null,
                listOf("15m" to 15, "30m" to 30, "45m" to 45, "1h" to 60, "2h" to 120),
                s.settings.maxRingMinutes) { v -> app.patch { it.copy(maxRingMinutes = v) } }
        }

        // --- snooze ---
        Section("Snooze", "Turn the phone this far to snooze it.")
        LockedRow(locked) {
            // Seconds, not minutes: the 30s default cannot survive a minutes-only control.
            ChoiceSetting("Snooze for", null,
                listOf("30s" to 30, "1m" to 60, "2m" to 120, "5m" to 300, "10m" to 600),
                s.settings.snoozeSeconds) { v -> app.patch { it.copy(snoozeSeconds = v) } }
        }
        LockedRow(locked) {
            ChoiceSetting("Rotation needed", null,
                listOf("90°" to 90, "120°" to 120, "180°" to 180, "360°" to 360),
                s.settings.snoozeThresholdDegrees) { v ->
                app.patch { it.copy(snoozeThresholdDegrees = v) }
            }
        }

        // --- test ---
        Section("Test",
            if (s.self.role?.name == "CONTROLLER")
                "Rings the alarm phone in 10 seconds. Stop it from that phone."
            else "Rings this phone in 10 seconds. Lock it and put it down first.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.testRing(false) }, modifier = Modifier.weight(1f)) {
                Text("Test ring")
            }
            OutlinedButton(onClick = { app.testRing(true) }, modifier = Modifier.weight(1f)) {
                Text("Silent")
            }
        }
        app.testMessage?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = 13.sp,
                color = if (app.testOk) MaterialTheme.colorScheme.primary else Bad)
        }

        deviceSettings?.invoke()
    }

    if (showUnlock) {
        PasswordDialog(
            title = "Unlock settings",
            onSubmit = { secret, result -> app.unlock(secret, result) },
            onDismiss = { showUnlock = false },
        )
    }
}

/** Visibly locked, and says why when tapped, rather than silently doing nothing. */
@Composable
fun LockedRow(locked: Boolean, content: @Composable () -> Unit) {
    var explain by remember { mutableStateOf(false) }
    Box {
        content()
        if (locked) {
            Box(Modifier.matchParentSize()) {
                // Intercept taps, but stay readable: you must be able to check the
                // alarm time without unlocking.
                Box(Modifier.matchParentSize().clickableNoRipple { explain = true })
                Icon(Icons.Default.Lock, contentDescription = "Locked",
                    tint = Muted, modifier = Modifier.size(16.dp)
                        .align(Alignment.CenterEnd))
            }
        }
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Locked") },
            text = { Text("Unlock at the top of this screen to change it.", fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { explain = false }) { Text("OK") } })
    }
}

@Composable
fun ToggleSetting(label: String, value: Boolean, onSet: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onSet)
    }
}
