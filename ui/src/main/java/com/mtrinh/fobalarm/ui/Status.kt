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
 * Answers two questions in two seconds, without reading: will it ring, and can I stop
 * it from here. Everything else on this screen had to justify itself against that.
 */
@Composable
fun StatusBlock(s: Snapshot, nowMs: Long, connected: Boolean, onReload: () -> Unit) {
    val iAmAlarm = s.self.role == Role.ALARM
    val problems = s.problems
    val peerMissing = s.peerBlockers.map { GateInfo.of(it)?.label ?: it }
    val willRing = problems.isEmpty() && s.nextFire != null

    // --- will it ring, and when ------------------------------------------
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
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

    // --- can I stop it from here (controller only) -------------------------
    if (!iAmAlarm) {
        Spacer(Modifier.height(S.sm))
        Text(
            if (connected) "Alarm phone: connected"
            else "Alarm phone: not reachable" +
                    (s.peer?.lastSeenMs?.takeIf { it > 0 }?.let { ", last heard ${Fmt.age(it, nowMs)}" } ?: ""),
            fontSize = T.body, fontWeight = FontWeight.SemiBold,
            color = if (connected) Good else Bad)
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
    } else if (s.tomorrow.kind != "NONE") {
        Spacer(Modifier.height(S.sm))
        Text("Next alarm moved once, to " + (s.tomorrow.timeMs?.let { Fmt.absolute(it) } ?: "?"),
            fontSize = T.body, color = MaterialTheme.colorScheme.primary)
    }
    if (s.nap.armed) {
        Text("Nap: rings " + (s.nap.atMs?.let { Fmt.until(it, nowMs) } ?: "?"),
            fontSize = T.body, color = MaterialTheme.colorScheme.primary)
    }

    // --- what happened last time -------------------------------------------
    s.lastOutcome?.let {
        Spacer(Modifier.height(S.sm))
        val what = when (it.kind) {
            Outcome.DISMISSED_LOCAL -> "stopped on the alarm phone"
            Outcome.DISMISSED_REMOTE -> "stopped from the controller"
            Outcome.CAPPED -> "rang for the full time and was never stopped"
            Outcome.MISSED -> "was missed"
            Outcome.SKIPPED -> "was skipped"
            Outcome.SUPERSEDED -> "was replaced"
        }
        Text("Last alarm ${Fmt.absolute(it.atMs)} $what" +
                (if (it.snoozeCount > 0) ", snoozed ${it.snoozeCount}×" else ""),
            fontSize = T.label, color = if (it.kind == Outcome.CAPPED || it.kind == Outcome.MISSED) Bad else Muted)
    }
}
