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
    val gated = s.settings.let { true }   // the alarm phone reports whether a password exists
    val unlocked = app.token != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (!unlocked) TextButton(onClick = { showUnlock = true }) { Text("Unlock") }
            else Text("unlocked", fontSize = 12.sp, color = Good)
        }
        Text("Gated settings need the password. Dismiss never does.",
            fontSize = 11.sp, color = Muted)

        // --- gated -----------------------------------------------------------
        Section("Alarm")
        TimeField("Default alarm time", s.settings.defaultAlarmTime) { v ->
            app.patch { it.copy(defaultAlarmTime = v) }
        }
        IntSlider("Volume", s.settings.alarmVolumePercent, Settings.VOLUME_FLOOR, 100, "%") { v ->
            app.patch { it.copy(alarmVolumePercent = v) }
        }
        Text("floor ${Settings.VOLUME_FLOOR}% — enforced server-side", fontSize = 10.sp, color = Muted)
        IntSlider("Max ring", s.settings.maxRingMinutes, Settings.MAX_RING_FLOOR_M, 120, " min") { v ->
            app.patch { it.copy(maxRingMinutes = v) }
        }
        TimeField("Arm gate time", s.settings.armGateTime) { v ->
            app.patch { it.copy(armGateTime = v) }
        }

        Section("Snooze")
        IntSlider("Snooze length", s.settings.snoozeSeconds, 5, Settings.SNOOZE_CEILING_S, " s") { v ->
            app.patch { it.copy(snoozeSeconds = v) }
        }
        Text("hard ceiling ${Settings.SNOOZE_CEILING_S}s regardless of password",
            fontSize = 10.sp, color = Muted)
        IntSlider("Rotation threshold", s.settings.snoozeThresholdDegrees, 60, 720, "°") { v ->
            app.patch { it.copy(snoozeThresholdDegrees = v) }
        }

        Section("Scheduling")
        IntSlider("Missed grace", s.settings.missedGraceMinutes, 0, 120, " min") { v ->
            app.patch { it.copy(missedGraceMinutes = v) }
        }
        TimeField("Still worth waking before", s.settings.stillWorthWakingBefore) { v ->
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
        var secret by remember { mutableStateOf("") }
        var failed by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showUnlock = false },
            title = { Text("Unlock settings") },
            text = {
                Column {
                    OutlinedTextField(secret, { secret = it; failed = false },
                        label = { Text("Password or recovery code") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (failed) Text("Rejected", color = Bad, fontSize = 12.sp)
                    Text("Expires after ~2 minutes idle.", fontSize = 11.sp, color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.unlock(secret) { ok -> if (ok) showUnlock = false else failed = true }
                }) { Text("Unlock") }
            },
            dismissButton = { TextButton(onClick = { showUnlock = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun Section(title: String) {
    HorizontalDivider(color = Color(0xFF1A2026))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun TimeField(label: String, value: String, onSet: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(text, { text = it }, label = { Text(label, fontSize = 12.sp) },
            singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onSet(text) }, enabled = text != value) { Text("Set", fontSize = 12.sp) }
    }
}

@Composable
fun IntSlider(label: String, value: Int, min: Int, max: Int, suffix: String, onSet: (Int) -> Unit) {
    var v by remember(value) { mutableIntStateOf(value) }
    Column {
        Row {
            Text(label, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("$v$suffix", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value = v.toFloat(), onValueChange = { v = it.toInt() },
                valueRange = min.toFloat()..max.toFloat(), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSet(v) }, enabled = v != value,
                contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Set", fontSize = 12.sp) }
        }
    }
}
