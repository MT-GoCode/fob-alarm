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
 * ONE status row, rendered identically on both phones. The present state is one glance
 * away with no navigation, and nothing is ever blank. SPEC.md invariant 6.
 */
@Composable
fun StatusBlock(s: Snapshot, nowMs: Long, modifier: Modifier = Modifier) {
    // Ages must advance from a LIVE clock. Deriving them from the snapshot's frozen
    // serverTimeMs made a dead link render as a confidently current screen.
    val now = nowMs
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // --- next alarm: absolute AND relative -----------------------------
        if (s.nextFire != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.absolute(s.nextFire!!.atMs), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(Fmt.until(s.nextFire!!.atMs, now), fontSize = 14.sp, color = Muted)
            }
            if (s.nextFire!!.source != OccurrenceSource.SCHEDULED) {
                Text(s.nextFire!!.source.name.lowercase().replace('_', ' '),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            // nextFire == null is a blocking gate, not a cosmetic gap.
            Text("NO ALARM SCHEDULED", fontSize = 20.sp, color = Bad, fontWeight = FontWeight.Bold)
        }

        // --- the override, naming BOTH instants -----------------------------
        if (s.tomorrow.kind != "NONE") {
            val txt = when (s.tomorrow.kind) {
                "SKIP" -> "SKIPPING ${s.tomorrow.replacesMs?.let { Fmt.absolute(it) } ?: "next alarm"}"
                else -> "${s.tomorrow.timeMs?.let { Fmt.absolute(it) } ?: "?"} — replaces " +
                        (s.tomorrow.replacesMs?.let { Fmt.absolute(it) } ?: "?")
            }
            Text(txt, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }
        if (s.nap.armed) {
            Text("nap ${s.nap.atMs?.let { Fmt.until(it, now) } ?: "?"}",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider(color = Color(0xFF1A2026))

        // --- both devices, always -------------------------------------------
        DeviceLine("this", s.self, now)
        if (s.peer != null) DeviceLine("peer", s.peer!!, now)
        else Fact("other phone", "never seen", false)

        // --- health ----------------------------------------------------------
        Fact("last sync", if (s.clock.lastSyncOkMs == 0L) "never" else Fmt.age(s.clock.lastSyncOkMs, now) +
                "  offset ${s.clock.offsetAppliedMs}ms", s.clock.lastSyncOkMs != 0L)
        Fact("last outcome", s.lastOutcome?.let {
            "${it.kind.name.lowercase().replace('_', ' ')} · ${Fmt.absolute(it.atMs)}" +
                    if (it.snoozeCount > 0) " · ${it.snoozeCount} snoozes" else ""
        } ?: "nothing recorded yet", s.lastOutcome != null)
        Fact("group", if (s.ap.running) "${s.ap.ssid ?: "?"} · ${s.ap.clientCount} client(s)"
             else "DOWN" + (s.ap.lastError?.let { " — $it" } ?: ""), s.ap.running)
        Fact("arm gate", s.armGate?.let {
            "${it.result} · ${Fmt.age(it.lastRunAtMs, now)}" +
                    if (it.failingGates.isNotEmpty()) " · ${it.failingGates.joinToString(",")}" else ""
        } ?: "not run yet", s.armGate?.result == "PASS")
        if (!s.gates.allPass) {
            Text("failing: ${s.gates.failing().joinToString(", ")}", fontSize = 12.sp, color = Bad)
        }
    }
}

@Composable
private fun DeviceLine(label: String, d: DeviceView, now: Long) {
    // Charger failure must be visible the moment it happens, not at the next 22:00 gate.
    val ok = d.batteryPct > 30 && d.plugged
    Row(Modifier.fillMaxWidth()) {
        Text(label.padEnd(6), fontSize = 12.sp, color = Muted)
        Text(Fmt.battery(d.batteryPct, d.plugged), fontSize = 12.sp, color = if (ok) Good else Bad)
        Spacer(Modifier.weight(1f))
        Text(if (d.lastSeenMs > 0 && label == "peer") Fmt.age(d.lastSeenMs, now) else "",
            fontSize = 12.sp, color = Muted)
    }
}

/** Header strip: link state and age, on every screen. */
@Composable
fun LinkStrip(connected: Boolean, ageMs: Long, clients: Int) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (connected) "● Connected" else "○ Disconnected",
            fontSize = 13.sp, color = if (connected) Good else Bad)
        Spacer(Modifier.width(8.dp))
        Text("· ${Fmt.duration(ageMs)}", fontSize = 13.sp, color = Muted)
        Spacer(Modifier.weight(1f))
        Text("AP: ${if (clients > 0) "$clients client(s)" else "no clients"}",
            fontSize = 12.sp, color = Muted)
    }
}
