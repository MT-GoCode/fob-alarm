package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.core.entries

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
    var tab by rememberSaveable { mutableIntStateOf(0) }

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
        else -> {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(app.syncMessage) {
            app.syncMessage?.let { snackbar.showSnackbar(it) }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(if (s.mode == Mode.INIT) "Setup" else "Status",
                        "Settings", "History").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = {
                                Icon(
                                    when (i) {
                                        0 -> Icons.Default.Alarm
                                        1 -> Icons.Default.Settings
                                        else -> Icons.Default.List
                                    },
                                    contentDescription = label)
                            },
                            label = { Text(label) })
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
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
}

// ---------------------------------------------------------------------------
// RINGING -- identical layout on both phones; only the button text and the
// instrument differ.
// ---------------------------------------------------------------------------

/** The ring, and only the ring. No tabs, no navigation away. */
@Composable
fun RingOnlyScreen(app: AppState, s: Snapshot) = RingingScreen(app, s, isAlarmRole = true)

/** A test ring is visibly a test. */
@Composable
fun TestRingScreen(app: AppState) {
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TEST", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold)
        Text(Fmt.clock(app.nowMs), fontSize = 54.sp, fontWeight = FontWeight.Light)
        Text("This is a test. It stops by itself.", fontSize = 13.sp, color = Muted)
        Spacer(Modifier.height(36.dp))
        Button(onClick = { app.stopTest() },
            modifier = Modifier.fillMaxWidth().height(110.dp)) {
            Text("STOP TEST", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RingingScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean) {
    val ring = s.ring!!
    val snoozed = ring.phase == RingPhase.SNOOZED
    val now = app.nowMs

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {

        Spacer(Modifier.height(8.dp))
        Text(Fmt.clock(now), fontSize = 34.sp, fontWeight = FontWeight.Light, color = Muted)

        if (snoozed) {
            // SNOOZED is a distinct screen: countdown, count, and a still-live dismiss.
            val left = ((ring.snoozeUntilMs ?: now) - now).coerceAtLeast(0)
            Text("SNOOZED", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold)
            Text("rings again in ${Fmt.duration(left)}", fontSize = 15.sp, color = Muted)
        } else {
            if (!isAlarmRole) Text("ends ${Fmt.until(ring.endsByMs, now)}",
                fontSize = 13.sp, color = Muted)
        }

        Spacer(Modifier.height(16.dp))

        val locked = now < app.buttonLockedUntilMs
        Button(
            onClick = { app.dismiss() },
            enabled = !locked && app.dismissUi !is DismissUi.Waiting,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        ) {
            Text(
                when {
                    app.dismissUi is DismissUi.Waiting -> "Waiting…"
                    isAlarmRole -> "PRESS TO DISMISS"
                    else -> "PRESS TO DISMISS REMOTE ALARM"
                },
                fontSize = 26.sp, fontWeight = FontWeight.Bold
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
        if (!snoozed && isAlarmRole) {
            Spacer(Modifier.weight(1f))
            // #39: a naked dial means nothing at 4 AM.
            Text("or turn the phone over to snooze", fontSize = 13.sp, color = Muted)
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                RotationInstrument(ring.rotationDeg, ring.thresholdDeg, null,
                    ring.rvStale, Modifier.fillMaxSize())
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        val muted = ring.audible.contains("muted=true")
        if (muted) Text("SOUND IS MUTED, vibration only", fontSize = 13.sp, color = Bad)

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
        StatusBlock(s, app.nowMs, app.connected) { armed ->
            app.patch { it.copy(armed = armed) }
        }

        Section("Tomorrow only")
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
            TimePickerDialog("Wake me at",
                s.tomorrow.timeMs?.let { Fmt.clock(it) } ?: s.settings.defaultAlarmTime,
                onCancel = { pickTime = false }) { v ->
                app.overrideTime(v); pickTime = false
            }
        }

        Section("Nap")
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

    val rows = g.entries()
    val perms = rows.filter { it.first.kind == GateKind.PERMISSION }
    val compat = rows.filter { it.first.kind == GateKind.COMPAT }
    val blocking = perms.count { !it.second && it.first.blocking }

    Page(
        applyInsets = false,
        title = "Setup",
        subtitle = when {
            !isAlarmRole -> "These are the alarm phone's permissions. Grant them on that phone."
            blocking == 0 -> "All set."
            else -> "$blocking permission${if (blocking == 1) "" else "s"} still needed."
        },
        subtitleColor = if (blocking == 0) Good else Muted,
        snapshot = s,
    ) {
        Spacer(Modifier.height(10.dp))

        perms.forEach { (info, ok) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (ok) "✓" else if (info.blocking) "•" else "◦", fontSize = 18.sp,
                    color = if (ok) Good else Muted, modifier = Modifier.width(26.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.label + if (!info.blocking) "  (optional)" else "",
                        fontSize = 15.sp,
                        color = if (ok) Muted else MaterialTheme.colorScheme.onSurface)
                    if (!ok) Text(info.explain, fontSize = 12.sp, color = Muted)
                }
                if (!ok && isAlarmRole && info.fix != FixAction.NONE) {
                    Button(onClick = { onFix(info.key) },
                        contentPadding = PaddingValues(horizontal = 18.dp)) {
                        Text(if (info.fix == FixAction.REQUEST) "Allow" else "Open")
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF151A1F))
        }

        Section("This phone")
        compat.forEach { (info, ok) -> Fact(info.label, if (ok) "yes" else "no", ok) }

        // Link state, visible HERE so it can be seen before getting past setup.
        Section("Link")
        Fact("group", if (s.ap.running) "on · ${s.ap.clientCount} connected"
             else "off" + (s.ap.lastError?.let { " — $it" } ?: ""), s.ap.running)
        Fact("name", s.ap.ssid ?: "not set", s.ap.ssid != null)
        Text("The group starts once a name and passphrase are set in Settings and " +
             "nearby-devices is allowed.", fontSize = 11.sp, color = Muted)
    }
}

// ---------------------------------------------------------------------------
// HISTORY
// ---------------------------------------------------------------------------

@Composable
private fun HistoryScreen(app: AppState) {
    LaunchedEffect(Unit) { app.loadHistory() }
    Page(title = "History") {
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
