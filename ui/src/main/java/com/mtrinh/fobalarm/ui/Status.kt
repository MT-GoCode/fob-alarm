package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtrinh.fobalarm.core.*

/**
 * ONE status block. Everything worth knowing, in one place, in one order:
 * what this phone is, whether it will ring, when, and what could stop it.
 */
@Composable
fun StatusBlock(s: Snapshot, nowMs: Long, connected: Boolean, onArm: (Boolean) -> Unit) {
    val armed = s.settings.armed
    val blockers = s.gates.failing().filter { GateInfo.of(it)?.blocking == true }
    val willRing = armed && blockers.isEmpty() && s.nextFire != null

    // --- headline: armed, and when ---------------------------------------
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    !armed -> "DISARMED"
                    !willRing -> "WILL NOT RING"
                    else -> "ARMED"
                },
                fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (willRing) Good else Bad)
            Text(
                if (s.nextFire != null) "${Fmt.absolute(s.nextFire!!.atMs)}  ·  ${Fmt.until(s.nextFire!!.atMs, nowMs)}"
                else "no alarm scheduled",
                fontSize = 14.sp,
                color = if (s.nextFire != null) MaterialTheme.colorScheme.onSurface else Bad)
        }
        Switch(checked = armed, onCheckedChange = onArm)
    }

    if (blockers.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Bad)) {
            Column(Modifier.padding(12.dp)) {
                Text("Cannot ring", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = Color.Black)
                blockers.forEach {
                    Text("· " + (GateInfo.of(it)?.label ?: it), fontSize = 13.sp, color = Color.Black)
                }
                Text("Fix these in Setup.", fontSize = 11.sp, color = Color.Black)
            }
        }
    }

    // --- one-off changes --------------------------------------------------
    if (s.tomorrow.kind != "NONE" || s.nap.armed) {
        Spacer(Modifier.height(10.dp))
        if (s.tomorrow.kind == "SKIP") {
            Fact("tomorrow", "skipping " + (s.tomorrow.replacesMs?.let { Fmt.absolute(it) } ?: ""))
        } else if (s.tomorrow.kind != "NONE") {
            Fact("tomorrow", (s.tomorrow.timeMs?.let { Fmt.absolute(it) } ?: "?") +
                    "  instead of  " + (s.tomorrow.replacesMs?.let { Fmt.absolute(it) } ?: "?"))
        }
        if (s.nap.armed) Fact("nap", s.nap.atMs?.let { Fmt.until(it, nowMs) } ?: "?")
    }

    Section("This phone", null)
    Fact("role", s.self.role?.name ?: "not set", s.self.role != null)
    Fact("battery", Fmt.battery(s.self.batteryPct, s.self.plugged), s.self.plugged)
    Fact("build", "${s.self.variant.name.lowercase()} ${s.self.appVersion}")

    Section("Other phone", null)
    if (s.peer == null) {
        Fact("status", "never connected", false)
    } else {
        Fact("status", if (connected) "connected" else "last seen ${Fmt.age(s.peer!!.lastSeenMs, nowMs)}",
            connected)
        Fact("role", s.peer!!.role?.name ?: "unknown", s.peer!!.role != null)
        Fact("battery", Fmt.battery(s.peer!!.batteryPct, s.peer!!.plugged), s.peer!!.plugged)
        Fact("build", "${s.peer!!.variant.name.lowercase()} ${s.peer!!.appVersion}",
            s.peer!!.appVersion == s.self.appVersion)
    }
    Fact("group", if (s.ap.running) "on, ${s.ap.clientCount} connected" else "off", s.ap.running)
    Fact("name", s.ap.ssid ?: "not set", s.ap.ssid != null)

    Section("Alarm health", null)
    Fact("last result", s.lastOutcome?.let {
        "${it.kind.name.lowercase().replace('_', ' ')}, ${Fmt.absolute(it.atMs)}"
    } ?: "nothing yet")
    Fact("ringtone", if (s.gates.audioPlayable) "ready" else "cannot be read", s.gates.audioPlayable)
    Fact("volume", "${s.settings.alarmVolumePercent}%" +
            if (!s.gates.volumeNotFixed) ", MUTED" else "", s.gates.volumeNotFixed)
    Fact("clock synced", if (s.clock.lastSyncOkMs == 0L) "never" else Fmt.age(s.clock.lastSyncOkMs, nowMs),
        s.clock.lastSyncOkMs != 0L)
    Fact("checked", if (s.gates.evaluatedAtMs == 0L) "never" else Fmt.age(s.gates.evaluatedAtMs, nowMs))
}
