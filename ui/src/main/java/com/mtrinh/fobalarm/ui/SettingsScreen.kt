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

@Composable
fun SettingsScreen(
    app: AppState,
    s: Snapshot,
    deviceSettings: (@Composable () -> Unit)?,
) {
    var showUnlock by remember { mutableStateOf(false) }
    val hasPassword = s.settings.hasPassword
    val locked = hasPassword && app.token == null

    // No status fields here: Status is its own screen.
    Page(title = "Settings", snapshot = s) {

        // One explanation for the whole screen, instead of a caption under every row.
        Card(colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Text(
                "Changes save immediately and sync to the other phone. Setting a password " +
                "locks the settings that could stop the alarm. Dismissing never needs one.",
                fontSize = T.caption, color = Muted, modifier = Modifier.padding(14.dp))
        }

        Spacer(Modifier.height(S.md))

        // --- password first, because it decides what else is editable ---
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (!hasPassword) "No password" else if (locked) "Locked" else "Unlocked",
                    fontSize = T.button, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (locked) Bad else Good)
                Text(
                    if (!hasPassword) "Anything below can be changed by anyone."
                    else if (locked) "Unlock to change the locked settings."
                    else "Locks again after a couple of minutes.",
                    fontSize = T.caption, color = Muted)
                if (s.settings.usingDefaultPassword) {
                    Text("Still using the default password 12345678. Change it below.",
                        fontSize = T.caption, color = Bad)
                }
            }
            if (locked) Button(onClick = { showUnlock = true }) { Text("Unlock") }
        }

        Section("Alarm")
        LockedRow(locked) {
            TimeSetting("Alarm time", s.settings.defaultAlarmTime) { v ->
                app.patch("Alarm time") { it.copy(defaultAlarmTime = v) }
            }
        }
        LockedRow(locked) {
            SliderSetting("Volume", s.settings.alarmVolumePercent,
                min = Settings.VOLUME_FLOOR, max = 100, step = 5, suffix = "%") { v ->
                app.patch("Volume") { it.copy(alarmVolumePercent = v) }
            }
        }
        LockedRow(locked) {
            ToggleSetting("Vibrate", s.settings.vibrate) { v -> app.patch("Vibrate") { it.copy(vibrate = v) } }
        }
        LockedRow(locked) {
            DurationSetting("Give up after", s.settings.maxRingMinutes * 60,
                minSeconds = 5 * 60, maxSeconds = 120 * 60) { secs ->
                app.patch("Give up after") { it.copy(maxRingMinutes = secs / 60) }
            }
        }

        Section("Snooze", "Turn the phone this far to snooze it.")
        LockedRow(locked) {
            DurationSetting("Snooze for", s.settings.snoozeSeconds,
                minSeconds = 10, maxSeconds = Settings.SNOOZE_CEILING_S,
                allowSeconds = true) { secs ->
                app.patch("Snooze length") { it.copy(snoozeSeconds = secs) }
            }
        }
        LockedRow(locked) {
            ChoiceSetting("Rotation", null,
                listOf("90°" to 90, "120°" to 120, "180°" to 180, "360°" to 360),
                s.settings.snoozeThresholdDegrees) { v ->
                app.patch("Rotation") { it.copy(snoozeThresholdDegrees = v) }
            }
        }

        Section("Test",
            if (s.self.role?.name == "CONTROLLER") "Rings the alarm phone now."
            else "Rings this phone now.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { app.testRing(false) }, modifier = Modifier.weight(1f)) {
                Text("Test ring")
            }
            OutlinedButton(onClick = { app.testRing(true) }, modifier = Modifier.weight(1f)) {
                Text("Silent")
            }
        }
        app.testMessage?.let {
            Spacer(Modifier.height(S.sm))
            Text(it, fontSize = T.label,
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

/** Locked settings stay readable; tapping says why rather than doing nothing. */
@Composable
fun LockedRow(locked: Boolean, content: @Composable () -> Unit) {
    var explain by remember { mutableStateOf(false) }
    Box {
        content()
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
fun ToggleSetting(label: String, value: Boolean, onSet: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = S.sm),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = T.body, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onSet)
    }
}
