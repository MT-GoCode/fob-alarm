package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtrinh.fobalarm.core.*

/**
 * ONE set of screens for both roles. Role selects a client, not a screen: everything
 * here is shared, and the only role-conditional pieces are passed in as slots.
 * SPEC.md section 5.
 */

@Composable
fun RootScreen(
    app: AppState,
    isAlarmRole: Boolean,
    deviceSettings: (@Composable () -> Unit)? = null, // alarm-only: credentials + ringtone
    onFixGate: (String) -> Unit = {},
    onRepair: (() -> Unit)? = null,
) {
    val s = app.snapshot
    var tab by remember { mutableIntStateOf(0) }

    if (s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Can't reach the alarm phone", color = Bad, fontSize = 15.sp)
                Text("Retrying. It rings on its own regardless.", color = Muted, fontSize = 12.sp)
                if (onRepair != null) {
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(onClick = onRepair) { Text("Re-pair / change role") }
                }
            }
        }
        return
    }

    when (s.mode) {
        Mode.RINGING -> RingingScreen(app, s, isAlarmRole)
        // INIT keeps the nav bar: an unfixable blocking gate (a replacement phone with
        // no gyroscope, say) must not trap the user on a screen with no way to reach
        // Settings and change anything.
        else -> Scaffold(
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(if (tab == 0 && s.mode == Mode.INIT) "Setup" else "Status",
                        "Settings", "History").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i },
                            icon = {}, label = { Text(label, fontSize = 12.sp) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    0 -> if (s.mode == Mode.INIT) InitScreen(app, s, isAlarmRole, onFixGate)
                         else WaitingScreen(app, s)
                    1 -> SettingsScreen(app, s, deviceSettings)
                    else -> HistoryScreen(app)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// RINGING -- identical layout on both phones; only the button text and the
// instrument differ.
// ---------------------------------------------------------------------------

@Composable
private fun RingingScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean) {
    val ring = s.ring!!
    val snoozed = ring.phase == RingPhase.SNOOZED
    val now = app.nowMs

    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LinkStrip(app.connected, app.linkAgeMs, s.ap.clientCount)

        Spacer(Modifier.height(8.dp))
        Text(Fmt.clock(now), fontSize = 34.sp, fontWeight = FontWeight.Light, color = Muted)

        if (snoozed) {
            // SNOOZED is a distinct screen: countdown, count, and a still-live dismiss.
            val left = ((ring.snoozeUntilMs ?: now) - now).coerceAtLeast(0)
            Text("SNOOZED", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
            Text("rings again in ${Fmt.duration(left)}", fontSize = 15.sp, color = Muted)
            Text("snooze #${ring.snoozeCount}", fontSize = 13.sp, color = Muted)
        } else {
            Text("ends ${Fmt.until(ring.endsByMs, now)}", fontSize = 13.sp, color = Muted)
        }

        Spacer(Modifier.height(16.dp))

        val locked = now < app.buttonLockedUntilMs
        Button(
            onClick = { app.dismiss() },
            enabled = !locked && app.dismissUi !is DismissUi.Waiting,
            modifier = Modifier.fillMaxWidth().height(if (isAlarmRole) 130.dp else 150.dp),
        ) {
            Text(
                when {
                    app.dismissUi is DismissUi.Waiting -> "Waiting…"
                    isAlarmRole -> "PRESS TO DISMISS"
                    else -> "PRESS TO DISMISS REMOTE ALARM"
                },
                fontSize = 21.sp, fontWeight = FontWeight.Bold
            )
        }

        when (val d = app.dismissUi) {
            is DismissUi.RingChanged -> Text(d.message, color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            is DismissUi.Unreachable -> Card(
                colors = CardDefaults.cardColors(containerColor = Bad),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text(d.message, color = Color.Black, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
            }
            is DismissUi.Waiting -> if (d.attempt >= 2) Text(
                "No reply — retrying (${d.attempt})", color = Muted,
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }

        Spacer(Modifier.height(12.dp))
        // The instrument is alarm-only: it is sensor-driven, and snooze is deliberately
        // not remotable -- a bathroom snooze button would defeat the mechanism.
        if (!snoozed) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                RotationInstrument(ring.rotationDeg, ring.thresholdDeg, null,
                    ring.rvStale, Modifier.fillMaxSize())
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        val muted = ring.audible.contains("muted=true")
        Text(if (muted) "SOUND IS MUTED — vibration only" else "sound confirmed",
            fontSize = 12.sp, color = if (muted) Bad else Muted)

        // Gate regressions during a session are shown, never acted on: INIT is entered
        // only from WAITING, so a gate can never preempt audio.
        if (!s.gates.allPass) {
            Text("also wrong: ${s.gates.failing().joinToString(", ")}", fontSize = 11.sp, color = Bad)
        }
    }

    if (app.dismissUi is DismissUi.Dismissed) {
        Box(Modifier.fillMaxSize().padding(bottom = 60.dp), contentAlignment = Alignment.BottomCenter) {
            Snackbar { Text("Dismissed!") }
        }
    }
}

// ---------------------------------------------------------------------------
// WAITING
// ---------------------------------------------------------------------------

@Composable
private fun WaitingScreen(app: AppState, s: Snapshot) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val nag = Nag.evaluate(s, app.lastOkMs, now)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LinkStrip(app.connected, app.linkAgeMs, s.ap.clientCount)

        // The only cover for "the alarm phone died silently at 03:00", which the arm
        // gate structurally cannot catch.
        if (nag != Nag.Reason.NONE) {
            Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
                Column(Modifier.padding(12.dp)) {
                    Text("ALARM MAY HAVE FAILED", fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = Color.Black)
                    Text(Nag.message(nag), fontSize = 13.sp, color = Color.Black)
                }
            }
        }
        StatusBlock(s, app.nowMs)

        HorizontalDivider(color = Color(0xFF1A2026))
        Text("next alarm only", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 60, 120).forEach { m ->
                OutlinedButton(onClick = { app.overrideShift(m) }, contentPadding = PaddingValues(8.dp)) {
                    Text(if (m < 60) "+${m}m" else "+${m / 60}h", fontSize = 12.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { app.overrideSkip() }) { Text("Skip next", fontSize = 12.sp) }
            if (s.tomorrow.kind != "NONE") {
                Button(onClick = { app.clearOverride() }) { Text("Clear", fontSize = 12.sp) }
            }
        }

        HorizontalDivider(color = Color(0xFF1A2026))
        Text("nap", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        var napMin by remember(s.settings.napMinutes) { mutableIntStateOf(s.settings.napMinutes) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(value = napMin.toFloat(), onValueChange = { napMin = it.toInt() },
                valueRange = 1f..300f, modifier = Modifier.weight(1f))
            Text("${napMin}m", fontSize = 13.sp, modifier = Modifier.width(48.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { app.nap(napMin) }) { Text("Start nap", fontSize = 12.sp) }
            if (s.nap.armed) OutlinedButton(onClick = { app.clearNap() }) { Text("Cancel", fontSize = 12.sp) }
        }
        app.lastError?.let { Text(it, color = Bad, fontSize = 12.sp) }
        Spacer(Modifier.height(30.dp))
    }
}

// ---------------------------------------------------------------------------
// INIT -- one row per gate, each with a Fix that deep-links to the right page.
// ---------------------------------------------------------------------------

@Composable
private fun InitScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean, onFix: (String) -> Unit) {
    val g = s.gates
    val rows = listOf(
        "scheduleExists" to g.scheduleExists,
        "exactAlarm" to g.exactAlarm,
        "notHibernating" to g.notHibernating,
        "fullScreenIntent" to g.fullScreenIntent,
        "audioPlayable" to g.audioPlayable,
        "dndAllowsAlarms" to g.dndAllowsAlarms,
        "volumeNotFixed" to g.volumeNotFixed,
        "gyroscopePresent" to g.gyroscopePresent,
        "foregroundService" to g.foregroundService,
        "freeDiskOk" to g.freeDiskOk,
        "notificationPolicyAccess" to g.notificationPolicyAccess,
        "localNetworkPermission" to g.localNetworkPermission,
        "groupCredentialsSet" to g.groupCredentialsSet,
        "p2pSupported" to g.p2pSupported,
        "staApConcurrent" to g.staApConcurrent,
        "vibrationEnabled" to g.vibrationEnabled,
        "noBluetoothAudio" to g.noBluetoothAudio,
        "powerOk" to g.powerOk,
        "thermalOk" to g.thermalOk,
    )
    val blocking = setOf("scheduleExists", "exactAlarm", "foregroundService", "gyroscopePresent",
        "notHibernating", "fullScreenIntent", "audioPlayable", "dndAllowsAlarms",
        "volumeNotFixed", "freeDiskOk", "noBluetoothAudio")
    // Failing-and-blocking first: seven of these rows can never be what is holding you here.
    val sorted = rows.sortedBy { (k, ok) -> (if (!ok && k in blocking) 0 else if (!ok) 1 else 2) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Setup required", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (isAlarmRole) "The alarm will not arm until every red row above the line is fixed."
             else "These are the ALARM phone's gates. Fix them on that phone.",
            fontSize = 12.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        sorted.forEach { (k, ok) ->
            ListItem(
                headlineContent = {
                    Text(label(k) + if (!ok && k !in blocking) "  (advisory)" else "",
                        fontSize = 14.sp)
                },
                supportingContent = { Text(explain(k), fontSize = 11.sp, color = Muted) },
                leadingContent = { Text(if (ok) "OK" else "✗", color = if (ok) Good else Bad,
                    fontFamily = FontFamily.Monospace) },
                trailingContent = {
                    // On the controller these rows describe the OTHER phone, so a Fix
                    // button here would open local settings and change nothing.
                    if (!ok && isAlarmRole) TextButton(onClick = { onFix(k) }) { Text("Fix", fontSize = 12.sp) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

/** Row headlines must be language, not variable names. */
private fun label(k: String) = when (k) {
    "scheduleExists" -> "An alarm is scheduled"
    "exactAlarm" -> "Exact alarms allowed"
    "notHibernating" -> "App hibernation disabled"
    "fullScreenIntent" -> "Can show over the lock screen"
    "audioPlayable" -> "Ringtone is readable"
    "dndAllowsAlarms" -> "Do Not Disturb allows alarms"
    "volumeNotFixed" -> "Alarm volume is settable"
    "gyroscopePresent" -> "Gyroscope present"
    "foregroundService" -> "Background service running"
    "freeDiskOk" -> "Enough free storage"
    "notificationPolicyAccess" -> "Can read Do Not Disturb policy"
    "localNetworkPermission" -> "Nearby devices permission"
    "groupCredentialsSet" -> "Phones paired"
    "p2pSupported" -> "Wi-Fi Direct supported"
    "staApConcurrent" -> "Wi-Fi and group at once"
    "vibrationEnabled" -> "Vibration enabled"
    "noBluetoothAudio" -> "No Bluetooth speaker connected"
    "powerOk" -> "Plugged in and charged"
    "thermalOk" -> "Not overheating"
    else -> k
}

private fun explain(k: String) = when (k) {
    "scheduleExists" -> "No alarm is scheduled at all — a dropped setting or bad migration."
    "exactAlarm" -> "Without exact alarms the ring time is not guaranteed."
    "notHibernating" -> "Hibernation force-stops the app and cancels every alarm. The single most likely total kill."
    "fullScreenIntent" -> "The ring screen cannot appear over the lock screen."
    "audioPlayable" -> "The selected ringtone cannot be opened."
    "dndAllowsAlarms" -> "A Do Not Disturb or Bedtime rule can mute the alarm stream."
    "volumeNotFixed" -> "Alarm volume is muted or cannot be set."
    "gyroscopePresent" -> "No gyroscope: the snooze gesture cannot work on this device."
    "foregroundService" -> "The ring service is not running."
    "freeDiskOk" -> "Low storage can fail the write that records a ring session."
    "notificationPolicyAccess" -> "Needed to read whether DND would mute the alarm."
    "localNetworkPermission" -> "Needed for the Wi-Fi Direct link to the other phone."
    "groupCredentialsSet" -> "Set the group name and passphrase to pair the phones."
    "p2pSupported" -> "This device does not support Wi-Fi Direct."
    "staApConcurrent" -> "Cannot host the group and stay on home WiFi at once — log pull and updates need a maintenance window."
    "vibrationEnabled" -> "Vibration is switched off, removing the last backstop."
    "noBluetoothAudio" -> "A Bluetooth speaker is connected; audio could route out of the box."
    "powerOk" -> "Not plugged in, or below 50%."
    "thermalOk" -> "Device is too hot; audio may be throttled."
    else -> ""
}

// ---------------------------------------------------------------------------
// HISTORY
// ---------------------------------------------------------------------------

@Composable
private fun HistoryScreen(app: AppState) {
    LaunchedEffect(Unit) { app.loadHistory() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("History", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("what happened, when, and which device did it", fontSize = 11.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        if (app.history.isEmpty()) Text("no events yet", color = Muted, fontSize = 13.sp)
        app.history.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(Fmt.absolute(e.atMs), fontSize = 11.sp, color = Muted,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.width(130.dp))
                Column {
                    Text("${e.type}  ·  ${e.actor.name.lowercase()}", fontSize = 12.sp,
                        color = if (e.type.contains("fail") || e.type == "missed" || e.type == "capped") Bad
                                else MaterialTheme.colorScheme.onSurface)
                    if (e.detail.isNotEmpty()) {
                        Text(e.detail.entries.joinToString(" ") { "${it.key}=${it.value}" },
                            fontSize = 10.sp, color = Muted, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}
