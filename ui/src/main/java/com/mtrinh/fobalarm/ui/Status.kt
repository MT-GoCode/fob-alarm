package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.mtrinh.fobalarm.core.*

/**
 * Everything a worried owner wants to see, in the order they worry: will it ring, can I
 * stop it from here, is the link up, is it powered, are the permissions there, when did
 * it last check itself, what happened last night.
 */
@Composable
fun StatusBlock(
    s: Snapshot, role: Role, nowMs: Long, localGates: Gates?, onCheckClock: () -> Unit,
) {
    // ROUTED from RootScreen, not re-derived from the snapshot. Reading `s.self.role` made the
    // role two facts that could disagree, and needed a `?: Role.ALARM` fallback for a case no
    // phone produces -- a silent default on the screen that answers "will it ring".
    val iAmAlarm = role == Role.ALARM
    val problems = s.problems
    val peerMissing = s.peerBlockers.mapNotNull { GateInfo.of(it)?.label }
    val willRing = problems.isEmpty() && s.nextFire != null
    val alarm = if (iAmAlarm) s.self else s.peer          // the phone that rings
    // Three roles, three arms. As `if (iAmAlarm) s.peer else s.self` this showed a SENTINEL its
    // OWN battery under the label "Controller": the client overwrites `peer` with the phone it
    // polled, so a sentinel's snapshot carries the alarm phone and never the controller at all.
    val controller = when (role) {                 // the phone that stops it
        Role.ALARM -> s.peer
        Role.CONTROLLER -> s.self
        Role.SENTINEL -> null
    }
    val peerRecent = s.peer != null && nowMs - s.peer!!.lastSeenMs < 120_000

    // --- will it ring, and when ------------------------------------------
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // Just the label. A bare timestamp did not say what it was; WHICH of the
            // three produced it is already the blue line below, and saying it twice
            // made the headline noisy.
            Text("Next alarm", fontSize = T.label, color = Muted)
            Text(
                if (s.nextFire != null) Fmt.absolute(s.nextFire!!.atMs) else "No alarm set",
                fontSize = T.title, fontWeight = FontWeight.Bold,
                color = if (willRing) MaterialTheme.colorScheme.onSurface else Bad)
            Text(
                if (s.nextFire != null) Fmt.until(s.nextFire!!.atMs, nowMs) else "nothing scheduled",
                fontSize = T.body, color = Muted)
        }
    }

    // --- anything wrong, as sentences ----------------------------------------
    if (problems.isNotEmpty() || peerMissing.isNotEmpty()) {
        Spacer(Modifier.height(S.sm))
        Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
            Column(Modifier.padding(S.md)) {
                Text(if (problems.isNotEmpty()) "The alarm will not ring" else "Needs attention",
                    fontSize = T.body, fontWeight = FontWeight.Bold, color = Color.Black)
                problems.forEach { Text(it, fontSize = T.label, color = Color.Black) }
                peerMissing.forEach { Text("Controller is missing: $it", fontSize = T.label, color = Color.Black) }
            }
        }
    }
    if (s.warnings.isNotEmpty()) {
        Spacer(Modifier.height(S.sm))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(S.md)) {
                s.warnings.forEach { Text(it, fontSize = T.label, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }

    // --- one-off changes in force ---------------------------------------------
    if (s.tomorrow.kind == "SKIP") {
        Spacer(Modifier.height(S.sm))
        Text("Next alarm skipped", fontSize = T.body, color = MaterialTheme.colorScheme.primary)
    } else if (s.tomorrow.kind != "NONE") s.tomorrow.timeMs?.let {
        Spacer(Modifier.height(S.sm))
        Text("Next alarm moved once, to ${Fmt.absolute(it)}", fontSize = T.body, color = MaterialTheme.colorScheme.primary)
    }
    if (s.nap.armed) s.nap.atMs?.let {
        Text("Nap: rings ${Fmt.until(it, nowMs)}", fontSize = T.body, color = MaterialTheme.colorScheme.primary)
    }

    // --- the link -------------------------------------------------------------
    Section("Link")
    if (iAmAlarm) {
        Line("Group", if (s.ap.running) "up" else "down", ok = s.ap.running)
    }

    // One row. Only on the alarm phone, which is the only phone that knows.
    if (iAmAlarm) {
        val sen = s.sentinel
        Line("Sentinel", when {
            sen == null -> "not set up"
            nowMs - sen.lastSeenMs < 120_000 -> "here"
            else -> "away " + Fmt.duration(nowMs - sen.lastSeenMs)
        }, ok = if (sen == null) null else nowMs - sen.lastSeenMs < 120_000)
        // Shown only when a ring would actually be blocked right now, so it is never a
        // theoretical warning the owner learns to ignore.
        s.ringBlock?.let {
            Text("Next ring blocked: $it", fontSize = T.label, color = Bad)
        }
    }

    // --- power ----------------------------------------------------------------
    Section("Power")
    alarm?.let { Line("Alarm phone", battery(it), ok = it.plugged) }
        ?: Line("Alarm phone", "unknown", ok = false)
    controller?.let { Line("Controller", if (iAmAlarm && !peerRecent) "not heard from" else battery(it), ok = null) }
    // The sentinel's own power is load-bearing, not trivia: the gate vetoes its block outright
    // below 20% unplugged, so the phone whose absence is meant to silence the alarm has to be
    // able to show why it currently cannot.
    if (role == Role.SENTINEL) Line("This phone", battery(s.self), ok = s.self.plugged)

    // --- permissions, this phone and the other one ----------------------------
    Section("Permissions")
    // The same rows Setup shows for this phone, so the two screens can never disagree.
    // This phone's OWN role: a sentinel asked for the controller's set was told it was missing
    // a lock-screen permission it has no use for.
    PermissionLine("This phone", localGates ?: s.gates, role)
    if (iAmAlarm) {
        Line("Controller", when {
            !peerRecent -> "not heard from"
            peerMissing.isEmpty() -> "all allowed"
            else -> peerMissing.joinToString() + " missing"
        }, ok = if (!peerRecent) null else peerMissing.isEmpty())
    } else {
        PermissionLine("Alarm phone", s.gates, Role.ALARM)
    }

    // --- self checks -------------------------------------------------------------
    Section("Checks")
    // "Hourly check" said nothing about what it checks, and sat one row above the
    // clock sync, which is a different hourly job entirely. This one re-runs every
    // permission and condition on the Setup tab.
    s.armGate?.let {
        Line("Hourly permission check", "${Fmt.age(it.lastRunAtMs, nowMs)}, " +
                (if (it.failingGates.isEmpty()) "nothing wrong" else it.failingGates.mapNotNull { k -> GateInfo.of(k)?.label }.joinToString()),
            ok = it.failingGates.isEmpty())
    } ?: Line("Hourly permission check", "not yet", ok = null)
    // Android's network time, read locally; the button re-reads it now. Hourly otherwise.
    Row(Modifier.fillMaxWidth().padding(vertical = S.xs), verticalAlignment = Alignment.CenterVertically) {
        Text("Hourly clock sync", fontSize = T.body, modifier = Modifier.weight(1f))
        val synced = s.clock.lastSyncOkMs > 0
        val off = kotlin.math.abs(s.clock.offsetAppliedMs)
        Text(
            when {
                !synced -> "not yet"
                off >= 2_000 -> "${Fmt.age(s.clock.lastSyncOkMs, nowMs)}, ${off / 1000}s off"
                else -> Fmt.age(s.clock.lastSyncOkMs, nowMs)
            },
            fontSize = T.label, fontWeight = FontWeight.SemiBold,
            color = when { !synced -> MaterialTheme.colorScheme.onSurface; off >= 2_000 -> Bad; else -> Good })
        TextButton(onClick = onCheckClock) { Text("Check now") }
    }
    Line("Alarm phone up since", Fmt.absolute(s.bootedAtMs), ok = null)

    // --- what happened last time -------------------------------------------
    s.lastOutcome?.let {
        Section("Last alarm")
        val what = when (it.kind) {
            Outcome.DISMISSED_LOCAL -> "stopped on the alarm phone"
            Outcome.DISMISSED_REMOTE -> "stopped from the controller"
            Outcome.CAPPED -> "rang for the full time and was never stopped"
            Outcome.MISSED -> "was missed"
            Outcome.SKIPPED -> "was skipped"
            Outcome.SUPERSEDED -> "was replaced"
        }
        Text("${Fmt.absolute(it.atMs)} $what" +
                (if (it.snoozeCount > 0) ", snoozed ${it.snoozeCount}×" else ""),
            fontSize = T.label, color = if (it.kind == Outcome.CAPPED || it.kind == Outcome.MISSED) Bad else Muted)
    }
}

@Composable
private fun PermissionLine(label: String, g: Gates, role: Role) {
    if (g.evaluatedAtMs == 0L) { Line(label, "checking", ok = null); return }
    val missing = setupRows(g, role).filter { !it.second }
    Line(label, if (missing.isEmpty()) "all allowed" else missing.joinToString { it.first.label } + " missing",
        ok = if (missing.isEmpty()) true else if (missing.any { it.first.blocking }) false else null)
}

private fun battery(d: DeviceView): String =
    (if (d.batteryPct < 0) "battery unknown" else "${d.batteryPct}%") + if (d.plugged) ", plugged in" else ", on battery"

/** One status line: what, then its state, coloured by whether it is fine. */
@Composable
private fun Line(label: String, value: String, ok: Boolean?) {
    Row(Modifier.fillMaxWidth().padding(vertical = S.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = T.body, modifier = Modifier.weight(1f))
        Text(value, fontSize = T.label, fontWeight = FontWeight.SemiBold,
            color = when (ok) { true -> Good; false -> Bad; null -> MaterialTheme.colorScheme.onSurface })
    }
}
