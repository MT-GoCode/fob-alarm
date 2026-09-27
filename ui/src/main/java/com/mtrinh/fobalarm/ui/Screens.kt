package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
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
                Spacer(Modifier.height(S.sm))
                Text("Can't reach the alarm phone", color = Bad, fontSize = T.body)
                Text("Retrying. It rings on its own regardless.", color = Muted, fontSize = T.caption)
                if (onRepair != null) {
                    Spacer(Modifier.height(S.md))
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
        // Announced by the client that performed it, so it shows once, on the right
        // phone, and cannot persist in a snapshot for days.
        LaunchedEffect(app.ringEndMessage) {
            app.ringEndMessage?.let { snackbar.showSnackbar(it); app.clearRingEndMessage() }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(if ((app.localGates ?: s.gates).allPass) "Status" else "Setup",
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
                    0 -> {
                        val myGates = app.localGates ?: s.gates
                        val mySetupDone = myGates.evaluatedAtMs == 0L || myGates.allPass
                        if (!mySetupDone || (isAlarmRole && s.mode == Mode.INIT))
                            InitScreen(app, s, isAlarmRole, onFixGate)
                        else WaitingScreen(app, s)
                    }
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

@Composable
private fun RingingScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean) {
    val ring = s.ring
    // From the test window, never from "no session": a forced ring has no RingView
    // either, and labelling a real alarm TEST would be the worst possible mislabel.
    val isTest = s.testUntilMs > s.serverTimeMs
    val snoozed = ring?.phase == RingPhase.SNOOZED
    val now = app.nowMs
    val deg = ring?.rotationDeg ?: app.testRotationDeg
    val threshold = ring?.thresholdDeg ?: s.settings.snoozeThresholdDegrees

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {

        if (isTest) {
            Text("TEST", fontSize = T.label, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(S.sm))
        Text(Fmt.clock(now), fontSize = T.title, fontWeight = FontWeight.Light, color = Muted)

        if (snoozed) {
            // Unmistakable: a full-width card, not a line of text among others.
            val left = ((ring?.snoozeUntilMs ?: now) - now).coerceAtLeast(0)
            Card(colors = CardDefaults.cardColors(containerColor = Good),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(S.md),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SNOOZED", fontSize = T.headline, fontWeight = FontWeight.Bold,
                        color = Color.Black)
                    Text("rings again in ${Fmt.duration(left)}", fontSize = T.body,
                        color = Color.Black)
                }
            }
        } else {
            if (!isAlarmRole && ring != null) Text("ends ${Fmt.until(ring.endsByMs, now)}",
                fontSize = T.label, color = Muted)
        }

        Spacer(Modifier.height(S.md))

        val locked = now < app.buttonLockedUntilMs
        Button(
            onClick = { if (isTest) app.stopTest() else app.dismiss() },
            enabled = !locked && app.dismissUi !is DismissUi.Waiting,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        ) {
            Text(
                when {
                    isTest -> "STOP TEST"
                    app.dismissUi is DismissUi.Waiting -> "Waiting…"
                    isAlarmRole -> "PRESS TO DISMISS"
                    else -> "PRESS TO DISMISS REMOTE ALARM"
                },
                fontSize = T.title, fontWeight = FontWeight.Bold
            )
        }

        when (val d = app.dismissUi) {
            is DismissUi.RingChanged -> Text(d.message, color = MaterialTheme.colorScheme.primary,
                fontSize = T.label, modifier = Modifier.padding(top = 8.dp))
            is DismissUi.Unreachable -> Card(
                colors = CardDefaults.cardColors(containerColor = Bad),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text(d.message, color = Color.Black, fontSize = T.headline,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
            }
            is DismissUi.Waiting -> if (d.attempt >= 2) Text(
                "No reply — retrying (${d.attempt})", color = Muted,
                fontSize = T.label, modifier = Modifier.padding(top = 8.dp))
            else -> {}
        }

        Spacer(Modifier.height(S.sm))
        // The instrument is alarm-only: it is sensor-driven, and snooze is deliberately
        // not remotable -- a bathroom snooze button would defeat the mechanism.
        if (isAlarmRole) {
            Spacer(Modifier.weight(1f))
            Text(
                if (snoozed) "snoozed" else "or turn the phone over to snooze",
                fontSize = T.label, color = Muted)
            Box(Modifier.fillMaxWidth().height(170.dp)) {
                RotationInstrument(
                    degrees = deg,
                    threshold = threshold,
                    quaternion = ring?.quaternion ?: app.testQuaternion,
                    stale = ring?.rvStale ?: false,
                    snoozed = snoozed,
                    modifier = Modifier.fillMaxSize())
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        val muted = ring?.audible?.contains("muted=true") == true
        if (muted) Text("SOUND IS MUTED, vibration only", fontSize = T.label, color = Bad)

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
    val nag = Nag.evaluate(s, app.lastOkMs, app.nowMs)

    Page(snapshot = s) {

        // The only cover for "the alarm phone died silently at 03:00", which the arm
        // gate structurally cannot catch.
        if (nag != Nag.Reason.NONE) {
            Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
                Column(Modifier.padding(12.dp)) {
                    Text("ALARM MAY HAVE FAILED", fontSize = T.button, fontWeight = FontWeight.Bold,
                        color = Color.Black)
                    Text(Nag.message(nag), fontSize = T.label, color = Color.Black)
                }
            }
        }
        StatusBlock(s, app.nowMs, app.connected) { app.reload() }

        Section("Tomorrow only", "Changes the next alarm once, then goes back to normal.")
        var pickTime by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickTime = true }) { Text("Set a time") }
            OutlinedButton(onClick = { app.overrideSkip() }) { Text("Skip") }
            if (s.tomorrow.kind != "NONE") {
                Button(onClick = { app.clearOverride() }) { Text("Clear") }
            }
        }
        if (pickTime) {
            TimePickerDialog("Wake me at",
                s.tomorrow.timeMs?.let { Fmt.clock(it) } ?: s.settings.defaultAlarmTime,
                onCancel = { pickTime = false }) { v -> app.overrideTime(v); pickTime = false }
        }

        Section("Nap", "A one-off timer from now. Does not change the alarm.")
        DurationSetting("Wake me in", s.settings.napMinutes * 60,
            minSeconds = 60, maxSeconds = 720 * 60) { secs -> app.nap(secs / 60) }
        if (s.nap.armed) {
            OutlinedButton(onClick = { app.clearNap() }) { Text("Cancel nap") }
        }

        app.lastError?.let { Text(it, color = Bad, fontSize = T.caption) }
        Spacer(Modifier.height(S.lg))
    }
}

// ---------------------------------------------------------------------------
// INIT -- one row per gate, each with a Fix that deep-links to the right page.
// ---------------------------------------------------------------------------

@Composable
private fun InitScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean, onFix: (String) -> Unit) {
    // ALWAYS this phone's own gates. The controller must be able to grant its own
    // permissions; showing it the alarm phone's list gave it dead rows.
    val g = app.localGates ?: s.gates
    if (g.evaluatedAtMs == 0L) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(S.md), strokeWidth = 2.dp)
        }
        return
    }

    val rows = g.entries()
    val needed = rows.filter { it.first.kind == GateKind.PERMISSION }
        .filter { isAlarmRole || it.first.key in CONTROLLER_PERMISSIONS }
    val required = needed.filter { it.first.blocking }
    val optional = needed.filter { !it.first.blocking }
    val missing = required.filter { !it.second }
    val compat = rows.filter { it.first.kind == GateKind.COMPAT }

    Page(title = "Setup", snapshot = s) {
        if (missing.isEmpty()) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("Everything required is allowed.", fontSize = T.label, color = Good,
                    modifier = Modifier.padding(S.md))
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
                Column(Modifier.padding(S.md)) {
                    Text("The alarm cannot ring", fontSize = T.body,
                        fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("Allow the ${missing.size} required item" +
                         (if (missing.size == 1) "" else "s") + " below.",
                        fontSize = T.caption, color = Color.Black)
                }
            }
        }

        Section("Required", "The alarm will not ring without these.")
        required.forEach { PermissionRow(it.first, it.second, required = true, onFix) }

        if (optional.isNotEmpty()) {
            Section("Recommended", "Not required, but the alarm is safer with them.")
            optional.forEach { PermissionRow(it.first, it.second, required = false, onFix) }
        }

        Section("This phone")
        compat.forEach { (info, ok) -> Fact(info.label, if (ok) "yes" else "no", ok) }
    }
}

/** Permissions the CONTROLLER genuinely needs; the rest are alarm-phone concerns. */
private val CONTROLLER_PERMISSIONS = setOf(
    "foregroundService", "localNetworkPermission", "notHibernating")

@Composable
private fun PermissionRow(
    info: GateInfo,
    ok: Boolean,
    required: Boolean,
    onFix: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(info.label) },
        supportingContent = { Text(info.explain, fontSize = T.caption) },
        leadingContent = {
            // Required-and-missing is an error. Recommended-and-missing is not, and must
            // not wear the same red icon.
            Icon(
                when {
                    ok -> Icons.Default.CheckCircle
                    required -> Icons.Default.Error
                    else -> Icons.Default.Info
                },
                contentDescription = if (ok) "allowed" else "not allowed",
                tint = when {
                    ok -> Good
                    required -> Bad
                    else -> Muted
                })
        },
        trailingContent = {
            // Every row acts, on every role, granted or not.
            TextButton(onClick = { onFix(info.key) }) { Text(if (ok) "Check" else "Allow") }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---------------------------------------------------------------------------
// HISTORY
// ---------------------------------------------------------------------------

@Composable
private fun HistoryScreen(app: AppState) {
    LaunchedEffect(Unit) { app.loadHistory() }
    Page(title = "History", snapshot = app.snapshot) {
        if (app.history.isEmpty()) Text("no events yet", color = Muted, fontSize = T.label)
        app.history.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = S.xs)) {
                Text(Fmt.absolute(e.atMs), fontSize = T.caption, color = Muted,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.width(130.dp))
                Column {
                    Text("${e.type}  ·  ${e.actor.name.lowercase()}", fontSize = T.caption,
                        color = if (e.type.contains("fail") || e.type == "missed" || e.type == "capped") Bad
                                else MaterialTheme.colorScheme.onSurface)
                    if (e.detail.isNotEmpty()) {
                        Text(e.detail.entries.joinToString(" ") { "${it.key}=${it.value}" },
                            fontSize = T.caption, color = Muted, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(S.lg))
    }
}
