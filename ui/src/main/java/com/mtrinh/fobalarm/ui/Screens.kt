package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center) {
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

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
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

    Page(snapshot = s) {
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

        Section("Tomorrow only", "Applies to the next alarm, then clears itself.")
        var pickTime by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("+15m" to 15, "+30m" to 30, "+1h" to 60, "+2h" to 120).forEach { (t, m) ->
                OutlinedButton(onClick = { app.overrideShift(m) },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(36.dp)) { Text(t, fontSize = 12.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { pickTime = true }) { Text("Set a time", fontSize = 12.sp) }
            OutlinedButton(onClick = { app.overrideSkip() }) { Text("Skip next", fontSize = 12.sp) }
            if (s.tomorrow.kind != "NONE") {
                Button(onClick = { app.clearOverride() }) { Text("Clear", fontSize = 12.sp) }
            }
        }
        if (pickTime) {
            TimePickerDialog("Wake me at", s.settings.defaultAlarmTime) { v ->
                app.overrideTime(v); pickTime = false
            }
        }

        Section("Nap", "A one-off timer. Touches nothing else.")
        ChoiceSetting("Wake me in", null,
            listOf("10m" to 10, "20m" to 20, "30m" to 30, "45m" to 45, "1h" to 60, "2h" to 120),
            s.settings.napMinutes) { v -> app.nap(v) }
        if (s.nap.armed) {
            OutlinedButton(onClick = { app.clearNap() }) { Text("Cancel nap", fontSize = 12.sp) }
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
    if (g.evaluatedAtMs == 0L) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Checking this device…", fontSize = 14.sp, color = Muted)
            }
        }
        return
    }

    // Permissions: the only things you can actually grant.
    val perms = listOf(
        "foregroundService" to g.foregroundService,
        "exactAlarm" to g.exactAlarm,
        "fullScreenIntent" to g.fullScreenIntent,
        "notHibernating" to g.notHibernating,
        "localNetworkPermission" to g.localNetworkPermission,
    )
    // Compatibility: facts about the hardware. Nothing to grant.
    val compat = listOf(
        "gyroscopePresent" to g.gyroscopePresent,
        "p2pSupported" to g.p2pSupported,
    )
    val pending = perms.count { !it.second }

    Page(
        title = "Setup",
        subtitle = if (!isAlarmRole) "These are the alarm phone's permissions. Grant them on that phone."
                   else if (pending == 0) "All set."
                   else "$pending permission${if (pending == 1) "" else "s"} still needed.",
        subtitleColor = if (pending == 0) Good else Muted,
        snapshot = s,
    ) {
        Spacer(Modifier.height(10.dp))

        perms.forEach { (k, ok) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (ok) "✓" else "•", fontSize = 18.sp,
                    color = if (ok) Good else Muted, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(label(k), fontSize = 15.sp,
                        color = if (ok) Muted else MaterialTheme.colorScheme.onSurface)
                    if (!ok) Text(explain(k), fontSize = 12.sp, color = Muted)
                }
                if (!ok && isAlarmRole) {
                    Button(onClick = { onFix(k) },
                        contentPadding = PaddingValues(horizontal = 18.dp)) { Text("Allow") }
                }
            }
            HorizontalDivider(color = Color(0xFF151A1F))
        }

        Spacer(Modifier.height(24.dp))
        Text("This phone", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted)
        compat.forEach { (k, ok) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(if (ok) "✓" else "✗", color = if (ok) Good else Bad,
                    modifier = Modifier.width(26.dp))
                Column {
                    Text(label(k), fontSize = 13.sp)
                    if (!ok) Text(explain(k), fontSize = 11.sp, color = Muted)
                }
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

private fun label(k: String) = when (k) {
    "scheduleExists" -> "An alarm is scheduled"
    "exactAlarm" -> "Exact alarms allowed"
    "notHibernating" -> "Android won't pause this app"
    "fullScreenIntent" -> "Can show over the lock screen"
    "audioPlayable" -> "Ringtone is readable"
    "dndAllowsAlarms" -> "Do Not Disturb allows alarms"
    "volumeNotFixed" -> "Alarm volume is settable"
    "gyroscopePresent" -> "Gyroscope present"
    "foregroundService" -> "Notifications allowed"
    "freeDiskOk" -> "Enough free storage"
    "notificationPolicyAccess" -> "Can read Do Not Disturb policy"
    "localNetworkPermission" -> "Nearby devices"
    "groupCredentialsSet" -> "Phones paired"
    "p2pSupported" -> "Wi-Fi Direct supported"
    "staApConcurrent" -> "Wi-Fi and group at once"
    "vibrationEnabled" -> "Vibration enabled"
    "noBluetoothAudio" -> "No Bluetooth speaker connected"
    "powerOk" -> "Plugged in right now"
    "thermalOk" -> "Not overheating"
    else -> k
}

private fun explain(k: String) = when (k) {
    "scheduleExists" -> "No alarm is scheduled at all — a dropped setting or bad migration."
    "exactAlarm" -> "Without exact alarms the ring time is not guaranteed."
    "notHibernating" -> "If Android pauses unused apps it force-stops this one and cancels every alarm, silently. Turn OFF \"Pause app activity if unused\" in App info."
    "fullScreenIntent" -> "Granted automatically to alarm apps on Android 14+. Use Test alarm below to confirm it really works."
    "audioPlayable" -> "The selected ringtone cannot be opened."
    "dndAllowsAlarms" -> "A Do Not Disturb or Bedtime rule can mute the alarm stream."
    "volumeNotFixed" -> "Alarm volume is muted or cannot be set."
    "gyroscopePresent" -> "Needed for the rotate-to-snooze gesture. The alarm rings either way."
    "foregroundService" -> "Already granted when you allowed notifications at first launch."
    "freeDiskOk" -> "Low storage can fail the write that records a ring session."
    "notificationPolicyAccess" -> "Lets the app detect a Do Not Disturb rule that would mute the alarm."
    "localNetworkPermission" -> "Required to pair the two phones over Wi-Fi Direct."
    "groupCredentialsSet" -> "Set the group name and passphrase to pair the phones."
    "p2pSupported" -> "Wi-Fi Direct, used for the phone-to-phone link."
    "staApConcurrent" -> "Whether this phone can host the link and stay on home WiFi at the same time."
    "vibrationEnabled" -> "Vibration is switched off, removing the last backstop."
    "noBluetoothAudio" -> "A Bluetooth speaker is connected; audio could route out of the box."
    "powerOk" -> "Right now this phone is on battery or under 50%. Not a setting — just plug it in."
    "thermalOk" -> "Device is too hot; audio may be throttled."
    else -> ""
}

// ---------------------------------------------------------------------------
// HISTORY
// ---------------------------------------------------------------------------

@Composable
private fun HistoryScreen(app: AppState) {
    LaunchedEffect(Unit) { app.loadHistory() }
    Page(title = "History", subtitle = "what happened, when, and which device did it") {
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
