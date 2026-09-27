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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtrinh.fobalarm.core.*

/** Everything worth knowing, in one place, in one order. */
@Composable
fun StatusBlock(s: Snapshot, nowMs: Long, connected: Boolean, onReload: () -> Unit) {

    val alarmProblems = s.problems
    val peerBlockers = s.peerBlockers.map { GateInfo.of(it)?.label ?: it }
    val willRing = alarmProblems.isEmpty() && s.nextFire != null

    // --- headline -----------------------------------------------------------
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
        IconButton(onClick = onReload) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
        }
    }

    // --- anything wrong, on either phone, as sentences ---------------------
    if (alarmProblems.isNotEmpty() || peerBlockers.isNotEmpty()) {
        Spacer(Modifier.height(S.sm))
        Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
            Column(Modifier.padding(S.md)) {
                Text(if (alarmProblems.isNotEmpty()) "The alarm will not ring" else "Needs attention",
                    fontSize = T.body, fontWeight = FontWeight.Bold, color = Color.Black)
                alarmProblems.forEach { Text(it, fontSize = T.label, color = Color.Black) }
                peerBlockers.forEach { Text("Controller: missing $it", fontSize = T.label, color = Color.Black) }
            }
        }
    }

    // --- one-off changes ----------------------------------------------------
    if (s.tomorrow.kind != "NONE" || s.nap.armed) {
        Spacer(Modifier.height(S.sm))
        if (s.tomorrow.kind == "SKIP") {
            Fact("tomorrow", "skipped")
        } else if (s.tomorrow.kind != "NONE") {
            Fact("tomorrow", s.tomorrow.timeMs?.let { Fmt.absolute(it) } ?: "?")
        }
        if (s.nap.armed) Fact("nap", s.nap.atMs?.let { Fmt.until(it, nowMs) } ?: "?")
    }

    val iAmAlarm = s.self.role == Role.ALARM
    Section("This phone")
    Fact("role", s.self.role?.name ?: "not set", s.self.role != null)
    Fact("battery", Fmt.battery(s.self.batteryPct, s.self.plugged), s.self.batteryPct >= 20)
    // s.ap always describes the ALARM phone, so only show it as "this phone" there.
    if (iAmAlarm) {
        Fact("group", if (s.ap.running) "on · ${s.ap.clientCount} connected" else "off", s.ap.running)
        Fact("name", s.ap.ssid ?: "not set", s.ap.ssid != null)
    }

    Section("Other phone")
    if (s.peer == null) {
        Fact("status", "not connected", false)
    } else {
        Fact("status",
            if (connected) "connected" else "last heard ${Fmt.age(s.peer!!.lastSeenMs, nowMs)}",
            connected)
        Fact("role", s.peer!!.role?.name ?: "unknown", s.peer!!.role != null)
        Fact("last heartbeat",
            if (s.lastHeartbeatMs == 0L) "never" else Fmt.age(s.lastHeartbeatMs, nowMs),
            connected)
        Fact("battery", Fmt.battery(s.peer!!.batteryPct, s.peer!!.plugged),
            s.peer!!.batteryPct >= 20)
    }
    if (!iAmAlarm) {
        Fact("group", if (s.ap.running) "on · ${s.ap.clientCount} connected" else "off", s.ap.running)
        Fact("name", s.ap.ssid ?: "not set", s.ap.ssid != null)
    }

    Section("Alarm")
    Fact("last result", s.lastOutcome?.let {
        "${it.kind.name.lowercase().replace('_', ' ')} · ${Fmt.absolute(it.atMs)}"
    } ?: "nothing yet")
    Fact("ringtone", if (s.settings.ringtoneUri != null) "your file" else "built-in",
        s.gates.audioPlayable)
    Fact("volume", "${s.settings.alarmVolumePercent}%" +
            (if (!s.gates.volumeNotFixed) " · MUTED" else "") +
            (if (s.settings.vibrate) " · vibrate" else ""), s.gates.volumeNotFixed)
    Fact("clock synced",
        if (s.clock.lastSyncOkMs == 0L) "never" else Fmt.age(s.clock.lastSyncOkMs, nowMs),
        s.clock.lastSyncOkMs != 0L)
}
