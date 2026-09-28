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
fun StatusBlock(s: Snapshot, nowMs: Long, connected: Boolean, localGates: Gates?,
                onCheckClock: () -> Unit, onReload: () -> Unit) {
    val iAmAlarm = s.self.role == Role.ALARM
    val problems = s.problems
    val peerMissing = s.peerBlockers.mapNotNull { GateInfo.of(it)?.label }
    val willRing = problems.isEmpty() && s.nextFire != null
    val alarm = if (iAmAlarm) s.self else s.peer          // the phone that rings
    val controller = if (iAmAlarm) s.peer else s.self     // the phone that stops it
    val peerAge = s.peer?.lastSeenMs?.takeIf { it > 0 }?.let { Fmt.age(it, nowMs) }
    val peerRecent = s.peer != null && nowMs - s.peer!!.lastSeenMs < 120_000

    // --- will it ring, and when ------------------------------------------
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // A bare timestamp at the top of the screen does not say what it is, and
            // once a Move or a nap is in force it is not the ordinary alarm either.
            Text(
                when (s.nextFire?.source) {
                    OccurrenceSource.NAP -> "Next alarm — nap"
                    OccurrenceSource.TOMORROW_OVERRIDE -> "Next alarm — moved"
                    else -> "Next alarm"
                },
                fontSize = T.label, color = Muted)
            Text(
                if (s.nextFire != null) Fmt.absolute(s.nextFire!!.atMs) else "No alarm set",
                fontSize = T.title, fontWeight = FontWeight.Bold,
                color = if (willRing) MaterialTheme.colorScheme.onSurface else Bad)
            Text(
                if (s.nextFire != null) Fmt.until(s.nextFire!!.atMs, nowMs) else "nothing scheduled",
                fontSize = T.body, color = Muted)
        }
        IconButton(onClick = onReload) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
    }
    Text(if (iAmAlarm) "This is the alarm phone" else "This is the controller",
        fontSize = T.label, color = Muted)

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
        Line("Controller", when {
            connected -> "connected"
            peerAge != null -> "not connected, last heard $peerAge"
            else -> "never connected"
        }, ok = connected)
    } else {
        Line("Alarm phone", when {
            connected -> "connected"
            peerAge != null -> "not reachable, last heard $peerAge"
            else -> "not reachable"
        }, ok = connected)
    }

    // --- power ----------------------------------------------------------------
    Section("Power")
    alarm?.let { Line("Alarm phone", battery(it), ok = it.plugged) }
        ?: Line("Alarm phone", "unknown", ok = false)
    controller?.let { Line("Controller", if (iAmAlarm && !peerRecent) "not heard from" else battery(it), ok = null) }

    // --- permissions, this phone and the other one ----------------------------
    Section("Permissions")
    // The same rows Setup shows for this phone, so the two screens can never disagree.
    PermissionLine("This phone", localGates ?: s.gates, iAmAlarm)
    if (iAmAlarm) {
        Line("Controller", when {
            !peerRecent -> "not heard from"
            peerMissing.isEmpty() -> "all allowed"
            else -> peerMissing.joinToString() + " missing"
        }, ok = if (!peerRecent) null else peerMissing.isEmpty())
    } else {
        PermissionLine("Alarm phone", s.gates, true)
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
    val mismatch = s.peer != null && s.peer!!.appVersion != s.self.appVersion
    Line("Versions", if (s.peer == null) s.self.appVersion else "${s.self.appVersion} here, ${s.peer!!.appVersion} there",
        ok = if (mismatch) false else null)

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
private fun PermissionLine(label: String, g: Gates, alarmRole: Boolean) {
    if (g.evaluatedAtMs == 0L) { Line(label, "checking", ok = null); return }
    val missing = setupRows(g, alarmRole).filter { !it.second }
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
