package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.core.entries

/**
 * ONE set of screens for both roles. Role selects a client, not a screen. The only
 * role-specific pieces arrive as slots from the app module.
 */
@Composable
fun RootScreen(
    app: AppState,
    isAlarmRole: Boolean,
    pairing: @Composable () -> Unit,                  // both roles, role-specific content
    deviceSettings: (@Composable () -> Unit)? = null, // alarm phone: ringtone, password
    roleSwitcher: @Composable () -> Unit,
    onFixGate: (String) -> Unit = {},
    /** CONTROLLER: the whole "trying to connect" screen. Name, passphrase, permission, log. */
    trying: (@Composable () -> Unit)? = null,
) {
    val s = app.snapshot
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pushed by rememberSaveable { mutableStateOf(false) }

    // The alarm phone is its own truth; it is "starting" for a moment and then connected.
    if (isAlarmRole && s == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // The controller has exactly two states: connected, or trying. Trying is one screen
    // with everything you can do about it, and whatever the alarm phone last said, dimmed.
    // A ring seen before the link dropped still wins: the dismiss button keeps retrying.
    // Bounded by link age: a snapshot from before the link dropped cannot hold the ring
    // screen up after the alarm phone has long since stopped on its own.
    val ringing = s != null && (s.mode == Mode.RINGING || s.testUntilMs > s.serverTimeMs) &&
            (isAlarmRole || app.nowMs - app.linkAtMs < 10 * 60_000)
    if (!isAlarmRole && !app.connected && !ringing && trying != null) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LinkBar(app, isAlarmRole)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = S.page)) {
                trying()
                if (s != null) {
                    Section("What it last said")
                    Box(Modifier.alpha(0.55f)) { Column { StatusBlock(s, app.nowMs, false, app.localGates, { app.checkClock() }) { app.reload() } } }
                }
                Spacer(Modifier.height(S.lg))
            }
        }
        return
    }
    s!!

    if (ringing) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LinkBar(app, isAlarmRole)
            RingingScreen(app, s, isAlarmRole)
        }
        return
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(tab) { app.reload() }          // every navigation re-reads the alarm phone
    LaunchedEffect(app.syncMessage) { app.syncMessage?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(app.ringEndMessage) {
        app.ringEndMessage?.let { snackbar.showSnackbar(it); app.clearRingEndMessage() }
    }

    // Setup is always a tab. It is only pushed to the front while something required is
    // missing; once granted, nothing disappears and everything stays reachable.
    val myGates = app.localGates ?: s.gates
    val setupBlocked = myGates.evaluatedAtMs != 0L &&
            setupRows(myGates, isAlarmRole).any { it.first.blocking && !it.second }
    if (setupBlocked && !pushed) { tab = 1; pushed = true }

    Scaffold(
        topBar = { LinkBar(app, isAlarmRole) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Status", "Setup", "Settings", "History").forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = {
                            Icon(when (i) {
                                0 -> Icons.Default.Alarm
                                1 -> if (setupBlocked) Icons.Default.Error else Icons.Default.CheckCircle
                                2 -> Icons.Default.Settings
                                else -> Icons.AutoMirrored.Filled.List
                            }, contentDescription = label, tint = if (i == 1 && setupBlocked) Bad else LocalContentColor.current)
                        },
                        label = { Text(label) })
                }
            }
        }
    ) { pad ->
        // Red bar: what is below came from the other phone earlier. Dim it so it reads as such.
        Box(Modifier.padding(pad).consumeWindowInsets(pad).alpha(if (isAlarmRole || app.connected) 1f else 0.55f)) {
            when (tab) {
                0 -> WaitingScreen(app, s, isAlarmRole)
                1 -> SetupScreen(app, s, isAlarmRole, onFixGate, pairing)
                2 -> SettingsScreen(app, s, isAlarmRole,
                        deviceSettings = deviceSettings?.let { ds -> { pairing(); ds() } },
                        roleSwitcher = if (isAlarmRole) roleSwitcher else ({ pairing(); roleSwitcher() }))
                else -> HistoryScreen(app)
            }
        }
    }
}


// ---------------------------------------------------------------------------
// RINGING
// ---------------------------------------------------------------------------

/** The ring, and only the ring. No tabs, no navigation away. */
@Composable
fun RingOnlyScreen(app: AppState, s: Snapshot) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        LinkBar(app, isAlarmRole = true)
        RingingScreen(app, s, isAlarmRole = true)
    }
}

/**
 * The one line that is always on screen, on both phones: how fresh what you are looking
 * at is. Green while the other phone answers; red the moment a request fails or it has
 * gone quiet, and then everything below is the last known state, and says so.
 */
@Composable
fun LinkBar(app: AppState, isAlarmRole: Boolean) {
    val now = app.nowMs
    val at = app.linkAtMs
    val ok = app.connected
    val other = if (isAlarmRole) "Controller" else "Alarm phone"
    val text = when {
        at == 0L -> "$other: never heard from"
        ok -> "$other: updated ${Fmt.age(at, now)}"
        else -> "$other: not answering for ${Fmt.duration(now - at)}. Showing what it last said."
    }
    Surface(color = if (ok) Good else Bad, modifier = Modifier.fillMaxWidth()) {
        Text(text, fontSize = T.caption, fontWeight = FontWeight.SemiBold, color = Color.Black,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = S.page, vertical = S.xs))
    }
}

@Composable
private fun RingingScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean) {
    val ring = s.ring
    // From the test window, never from "no session": a forced ring has no RingView
    // either, and labelling a real alarm TEST would be the worst possible mislabel.
    val isTest = s.testUntilMs > s.serverTimeMs
    // A snoozed test reports its countdown the same way as a snoozed alarm.
    val snoozeUntil = ring?.snoozeUntilMs?.takeIf { ring.phase == RingPhase.SNOOZED }
        ?: s.testSnoozedUntilMs.takeIf { isTest && it > app.nowMs }
    val snoozed = snoozeUntil != null
    val now = app.nowMs
    val deg = ring?.rotationDeg ?: app.testRotationDeg
    val threshold = ring?.thresholdDeg ?: s.settings.snoozeThresholdDegrees
    val sending = app.dismissUi is DismissUi.Waiting

    // Three things, centred as one group: the time, the button, the globe. The small
    // lines (TEST, whose alarm, muted) sit at the edges and never push the group around.
    Column(
        Modifier.fillMaxSize().padding(S.page),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isTest) Text("TEST", fontSize = T.label, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        // The controller must say, in the first line, that the noise is elsewhere.
        if (!isAlarmRole) Text("ALARM PHONE IS RINGING", fontSize = T.headline, fontWeight = FontWeight.Bold)

        Spacer(Modifier.weight(1f))

        Text(Fmt.clock(now), fontSize = T.hero, fontWeight = FontWeight.Light,
            color = if (isAlarmRole) MaterialTheme.colorScheme.onSurface else Muted)

        if (snoozed) {
            val left = ((snoozeUntil ?: now) - now).coerceAtLeast(0)
            Spacer(Modifier.height(S.md))
            Card(colors = CardDefaults.cardColors(containerColor = Good),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(S.md), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SNOOZED", fontSize = T.headline, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("rings again in ${Fmt.duration(left)}", fontSize = T.body, color = Color.Black)
                }
            }
        }

        Spacer(Modifier.height(S.lg))

        val locked = now < app.buttonLockedUntilMs
        Button(
            onClick = { if (isTest) app.stopTest() else app.dismiss() },
            enabled = !locked && !sending,
            modifier = Modifier.fillMaxWidth().height(170.dp),
        ) {
            Text(
                when {
                    isTest -> "STOP TEST"
                    sending -> if (isAlarmRole) "Stopping" else "Sending to alarm phone"
                    isAlarmRole -> "PRESS TO DISMISS"
                    else -> "DISMISS IT"
                },
                fontSize = T.title, fontWeight = FontWeight.Bold)
        }

        // Every outcome that is not success is a red card at the button's own size.
        (app.dismissUi as? DismissUi.RingChanged)?.let { RedCard(it.message) }
        (app.dismissUi as? DismissUi.Unreachable)?.let { RedCard(it.message) }

        if (isAlarmRole) {
            Spacer(Modifier.height(S.lg))
            Text(if (snoozed) "Snoozed" else "Turn the box to snooze",
                fontSize = T.body, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(S.sm))
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                RotationInstrument(
                    degrees = deg, threshold = threshold,
                    quaternion = ring?.quaternion ?: app.testQuaternion,
                    snoozed = snoozed, modifier = Modifier.fillMaxSize())
            }
        }

        Spacer(Modifier.weight(1f))

        if (ring?.audible?.contains("muted=true") == true) {
            Text(if (isAlarmRole) "Sound is muted. Vibration only."
                 else "Sound is muted on the alarm phone. Vibration only.", fontSize = T.label, color = Bad)
        }
    }
}

@Composable
private fun RedCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Bad),
        modifier = Modifier.fillMaxWidth().padding(top = S.sm)) {
        Text(text, color = Color.Black, fontSize = T.headline, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(S.md))
    }
}

// ---------------------------------------------------------------------------
// STATUS
// ---------------------------------------------------------------------------

@Composable
private fun WaitingScreen(app: AppState, s: Snapshot, isAlarmRole: Boolean) {
    // The nag is the controller's job: "go and check it" makes no sense on the phone itself.
    val nag = if (isAlarmRole) Nag.Reason.NONE else Nag.evaluate(s, app.lastOkMs, app.nowMs)
    var confirmSkip by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickNap by remember { mutableStateOf(false) }

    Page(snapshot = s) {
        if (nag != Nag.Reason.NONE) {
            Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
                Column(Modifier.padding(S.md)) {
                    Text(Nag.headline(nag), fontSize = T.button, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text(Nag.message(nag), fontSize = T.label, color = Color.Black)
                }
            }
            Spacer(Modifier.height(S.sm))
        }

        StatusBlock(s, app.nowMs, app.connected, app.localGates, { app.checkClock() }) { app.reload() }

        Section("Next alarm only")
        Row(horizontalArrangement = Arrangement.spacedBy(S.sm)) {
            if (s.tomorrow.kind != "NONE") {
                OutlinedButton(onClick = { app.clearOverride() }) { Text("Revert") }
            } else {
                OutlinedButton(onClick = { pickTime = true }) { Text("Move it") }
                OutlinedButton(onClick = { confirmSkip = true }) { Text("Skip it") }
            }
        }

        Section("Nap")
        Row(horizontalArrangement = Arrangement.spacedBy(S.sm)) {
            Button(onClick = { pickNap = true }) { Text("Nap") }
            if (s.nap.armed) OutlinedButton(onClick = { app.clearNap() }) { Text("Cancel nap") }
        }
    }

    if (pickTime) {
        TimePickerDialog("Ring the next alarm at",
            s.tomorrow.timeMs?.let { Fmt.hhmm(it) } ?: s.settings.defaultAlarmTime,
            onCancel = { pickTime = false }) { v -> app.overrideTime(v); pickTime = false }
    }
    if (confirmSkip) {
        val next = s.nextFire?.let { Fmt.absolute(it.atMs) } ?: "the next alarm"
        AlertDialog(
            onDismissRequest = { confirmSkip = false },
            title = { Text("Skip $next?") },
            text = { Text("It will not ring. The one after is unchanged.", fontSize = T.label) },
            confirmButton = { TextButton(onClick = { app.overrideSkip(); confirmSkip = false }) { Text("Skip") } },
            dismissButton = { TextButton(onClick = { confirmSkip = false }) { Text("Keep it") } })
    }
    if (pickNap) {
        DurationDialog("Nap for", s.settings.napMinutes * 60, minSeconds = 60, maxSeconds = 720 * 60,
            onCancel = { pickNap = false }) { secs -> app.nap(secs / 60); pickNap = false }
    }
}

// ---------------------------------------------------------------------------
// SETUP -- permissions, then pairing. A Done state, so you know when to stop.
// ---------------------------------------------------------------------------

@Composable
private fun SetupScreen(
    app: AppState, s: Snapshot, isAlarmRole: Boolean,
    onFix: (String) -> Unit, pairing: @Composable () -> Unit,
) {
    // Always THIS phone's gates; the controller has its own permissions to grant.
    val g = app.localGates ?: s.gates
    if (g.evaluatedAtMs == 0L) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(S.md), strokeWidth = 2.dp)
        }
        return
    }

    val rows = setupRows(g, isAlarmRole)
    val required = rows.filter { it.first.blocking }
    val optional = rows.filter { !it.first.blocking }
    val missing = required.count { !it.second }
    val compatMissing = g.entries().filter { it.first.kind == GateKind.COMPAT && !it.second }
        .filter { isAlarmRole || it.first.key != "gyroscopePresent" }

    Page(title = "Setup", snapshot = s) {
        if (missing > 0) {
            val ringBlocked = required.any { !it.second && GateInfo.of(it.first.key)?.blocking == true }
            Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
                Column(Modifier.padding(S.md)) {
                    Text(if (ringBlocked && isAlarmRole) "The alarm cannot ring yet" else "The phones cannot connect yet",
                        fontSize = T.body, fontWeight = FontWeight.Bold, color = Color.Black)
                    Text("See the $missing item${if (missing == 1) "" else "s"} marked below.",
                        fontSize = T.caption, color = Color.Black)
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = Good)) {
                Text("Done. Everything this phone needs is allowed.",
                    fontSize = T.body, fontWeight = FontWeight.Bold, color = Color.Black,
                    modifier = Modifier.padding(S.md))
            }
        }

        Section("Required")
        required.forEach { PermissionRow(it.first, it.second, required = true, onFix) }
        if (optional.isNotEmpty()) {
            Section("Recommended")
            optional.forEach { PermissionRow(it.first, it.second, required = false, onFix) }
        }
        if (compatMissing.isNotEmpty()) {
            Section("This phone")
            compatMissing.forEach { (info, _) -> Text(info.explain, fontSize = T.label, color = Muted) }
        }

        if (isAlarmRole) pairing()
    }
}

/** The one list, from core: what this phone's role needs, and whether it has it. */
internal fun setupRows(g: Gates, isAlarmRole: Boolean): List<Pair<GateInfo, Boolean>> = g.rowsFor(isAlarmRole)

@Composable
private fun PermissionRow(info: GateInfo, ok: Boolean, required: Boolean, onFix: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(info.label) },
        supportingContent = { if (!ok) Text(info.explain, fontSize = T.caption) },
        leadingContent = {
            Icon(
                when { ok -> Icons.Default.CheckCircle; required -> Icons.Default.Error; else -> Icons.Default.Info },
                contentDescription = if (ok) "allowed" else "not allowed",
                tint = when { ok -> Good; required -> Bad; else -> Muted })
        },
        trailingContent = {
            if (!ok && info.fix != FixAction.NONE) {
                Button(onClick = { onFix(info.key) }) {
                    Text(if (info.fix == FixAction.REQUEST) "Allow" else "Open")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ---------------------------------------------------------------------------
// HISTORY -- what happened, as sentences. Everything else is in the phone's log.
// ---------------------------------------------------------------------------

@Composable
private fun HistoryScreen(app: AppState) {
    LaunchedEffect(Unit) { app.loadHistory() }
    val shown = app.history.mapNotNull { e -> sentence(e)?.let { e to it } }
    Page(title = "History", snapshot = app.snapshot) {
        if (shown.isEmpty()) Text("Nothing yet.", color = Muted, fontSize = T.label)
        shown.forEach { (e, text) ->
            ListItem(
                overlineContent = { Text(Fmt.absolute(e.atMs), color = Muted) },
                headlineContent = { Text(text, color = if (e.type in BAD_EVENTS) Bad else MaterialTheme.colorScheme.onSurface) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

private val BAD_EVENTS = setOf("capped", "missed", "crash", "force_stopped_detected", "probe_fail")

/** The link's own events, as sentences, for the trying screen. Null means not a link event. */
fun linkSentence(e: Event): String? = when (e.type) {
    "p2p_waiting_permission" -> "Waiting for the Nearby devices permission"
    "p2p_connect_requested" -> "Asked Android to join the group"
    "p2p_connect_failed" -> "Android refused the join (error ${e.detail["reason"] ?: "?"})"
    "p2p_request_failed" -> "The join request failed"
    "p2p_joined" -> "Joined the group"
    "p2p_lost" -> "Left the group"
    "p2p_left_own_group" -> "Was still hosting a group of its own; left it"
    "role_changed" -> "Role set to ${if (e.detail["to"] == "ALARM") "alarm phone" else "controller"}"
    else -> null
}

private fun sentence(e: Event): String? {
    val ringing = e.detail["ringingMs"]?.toLongOrNull()?.let { " after " + Fmt.duration(it) } ?: ""
    return when (e.type) {
        "ring_start" -> if (e.detail["trigger"] == "NAP") "Nap alarm rang" else "Alarm rang"
        "dismiss_local" -> "Stopped on the alarm phone$ringing"
        "dismiss_remote" -> "Stopped from the controller$ringing"
        "snooze" -> "Snoozed"
        "capped" -> "Rang for the full time and was never stopped"
        "missed" -> "Alarm was missed"
        "nap_set" -> "Nap set"
        "override_set" -> if (e.detail["kind"] == "SKIP") "Next alarm skipped" else "Next alarm moved"
        "override_cleared" -> if (e.detail["reason"] == "user") "Next alarm change undone" else null
        "test_ring" -> "Test ring"
        "settings_change" -> "Settings changed" + (if (e.detail["who"] == "CONTROLLER") " from the controller" else "")
        "password_set" -> "Password set"
        "password_removed" -> "Password removed"
        "role_changed" -> "Role changed"
        "boot" -> if (e.detail["action"]?.contains("BOOT") == true) "Phone restarted" else null
        "package_replaced" -> "App updated"
        "crash" -> "The app crashed"
        "force_stopped_detected" -> "The app had been force stopped"
        "probe_fail" -> "Self test failed"
        "tz_change" -> "Time zone changed"
        else -> null
    }
}
