package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
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
        Section("Alarm", "When it rings and how loud. Locked once a password is set.")
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
            DurationSetting("Give up after", s.settings.maxRingMinutes,
                minMinutes = 5, maxMinutes = 120) { v -> app.patch { it.copy(maxRingMinutes = v) } }
        }

        // --- snooze ---
        Section("Snooze", "Rotate the phone this far to snooze. It rings again after the delay.")
        LockedRow(locked) {
            DurationSetting("Snooze for", s.settings.snoozeSeconds / 60,
                minMinutes = 1, maxMinutes = 10,
                secondsValue = s.settings.snoozeSeconds) { v ->
                app.patch { it.copy(snoozeSeconds = v * 60) }
            }
        }
        LockedRow(locked) {
            ChoiceSetting("Rotation needed", null,
                listOf("90°" to 90, "120°" to 120, "180°" to 180, "360°" to 360),
                s.settings.snoozeThresholdDegrees) { v ->
                app.patch { it.copy(snoozeThresholdDegrees = v) }
            }
        }

        // --- test ---
        Section("Test", "Rings this phone now so you can check it wakes the screen and makes noise.")
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
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }

        deviceSettings?.invoke()
    }

    if (showUnlock) {
        PasswordDialog(
            title = "Unlock settings",
            onSubmit = { secret -> app.unlockBlocking(secret) },
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
                Surface(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                    modifier = Modifier.matchParentSize()
                        .clickableNoRipple { explain = true }
                ) {}
                Text("🔒", fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
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
