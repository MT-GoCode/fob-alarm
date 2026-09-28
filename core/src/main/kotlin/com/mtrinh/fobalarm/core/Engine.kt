package com.mtrinh.fobalarm.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Injected so every scheduling rule is testable without waiting for 04:00. */
interface TimeSource {
    fun nowMs(): Long
    fun zone(): ZoneId
}

/** Mutable state the engine owns. Persisted by the caller; the engine never does IO. */
data class EngineState(
    val settings: Settings = Settings(),
    val latches: List<Latch> = emptyList(),
    val override: Override? = null,
    val nap: Nap? = null,
    val session: RingSession? = null,
    val lastOutcome: LastOutcome? = null,
    val stateVersion: Long = 0,
    val lastTimeZone: String? = null,
    /**
     * The last moment this install is known to have been running. It is the ONLY
     * threshold for latching the past: occurrences before it were either already
     * handled or predate us, and occurrences after it genuinely passed while we were
     * gone (a power cut, a force-stop) and are real misses.
     *
     * Seeded to `now` on first run, so a fresh install -- or a destructive Room
     * migration -- cannot invent a fortnight of failures and ring on launch.
     */
    val lastAliveMs: Long = 0,
)

/** What recompute() decided the caller must now do. The engine itself performs no side effects. */
data class RecomputeResult(
    val state: EngineState,
    val nextFire: NextFire?,
    val events: List<PendingEvent>,
    /** Set when a forward clock jump landed on an unfired occurrence we should ring for now. */
    val fireNow: OccurrenceSource? = null,
)


data class PendingEvent(val type: String, val detail: Map<String, String> = emptyMap())

object Engine {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    // -----------------------------------------------------------------------
    // Occurrence maths
    // -----------------------------------------------------------------------

    /**
     * The instant of the scheduled occurrence on [date]. DST-correct by construction:
     * spring-forward into a non-existent time yields the next valid instant, and
     * fall-back ambiguity yields the FIRST of the two occurrences.
     */
    fun scheduledInstant(date: LocalDate, hhmm: String, zone: ZoneId): Long {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        val local = date.atTime(LocalTime.of(h, m))
        // ZonedDateTime.of resolves a gap by shifting forward and an overlap to the earlier offset.
        return ZonedDateTime.of(local, zone).toInstant().toEpochMilli()
    }

    fun localDateOf(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().format(DATE)

    fun todayId(ts: TimeSource): OccurrenceId =
        OccurrenceId(localDateOf(ts.nowMs(), ts.zone()), OccurrenceSource.SCHEDULED)

    /** The next SCHEDULED occurrence with no latch, starting from today. */
    fun nextUnlatchedScheduled(st: EngineState, ts: TimeSource): Pair<OccurrenceId, Long> {
        val zone = ts.zone()
        val now = ts.nowMs()
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(400) {
            val id = OccurrenceId(date.format(DATE), OccurrenceSource.SCHEDULED)
            val at = scheduledInstant(date, st.settings.defaultAlarmTime, zone)
            val latched = st.latches.any { it.id == id }
            if (!latched && at > now) return id to at
            date = date.plusDays(1)
        }
        error("no unlatched occurrence within 400 days")
    }

    // -----------------------------------------------------------------------
    // The single chokepoint. Every state transaction ends here.
    // -----------------------------------------------------------------------

    fun recompute(st0: EngineState, ts: TimeSource, reason: String): RecomputeResult {
        var st = if (st0.lastAliveMs == 0L) st0.copy(lastAliveMs = ts.nowMs()) else st0
        val aliveSince = st.lastAliveMs
        val events = mutableListOf<PendingEvent>()
        val zone = ts.zone()
        val now = ts.nowMs()
        val today = LocalDate.parse(localDateOf(now, zone))

        // 0. Timezone change clears wall-clock-relative intent.
        val zoneId = zone.id
        if (st.lastTimeZone != null && st.lastTimeZone != zoneId) {
            events += PendingEvent("tz_change", mapOf("from" to st.lastTimeZone!!, "to" to zoneId))
            if (st.override != null) events += PendingEvent("override_cleared", mapOf("reason" to "tz_change"))
            st = st.copy(override = null, nap = null)
        }
        st = st.copy(lastTimeZone = zoneId)

        // 0. Reap a session that outlived its own cap. Without this, a process death
        //    more than maxRingMinutes into a ring leaves a zombie that onTrigger then
        //    absorbs every future trigger into -- silently, with every gate green.
        st.session?.let { open ->
            if (open.endsByMs <= now) {
                events += PendingEvent("capped", mapOf("ringId" to open.ringId,
                    "reason" to "expired_while_gone"))
                st = st.copy(
                    session = null,
                    lastOutcome = LastOutcome(Outcome.CAPPED, open.endsByMs,
                        open.occurrenceId.toString(), open.ringId, open.snoozeCount),
                    latches = if (st.latches.none { it.id == open.occurrenceId } &&
                                  open.trigger != OccurrenceSource.NAP)
                        st.latches + Latch(open.occurrenceId, LatchReason.MISSED, open.endsByMs)
                    else st.latches,
                )
            }
        }

        // 1. Discard future-dated latches. A forward clock excursion must not silently
        //    eat an alarm a year later.
        val futureLatches = st.latches.filter { LocalDate.parse(it.id.localDate).isAfter(today) }
        if (futureLatches.isNotEmpty()) {
            events += PendingEvent("latch_discarded", mapOf("count" to futureLatches.size.toString()))
            st = st.copy(latches = st.latches - futureLatches.toSet())
        }

        // 2. Latch the past. THE ONLY place a latch is written.
        var fireNow: OccurrenceSource? = null

        // Walk backwards over recent scheduled dates so a multi-day outage latches each day.
        for (back in 14 downTo 0) {
            val date = today.minusDays(back.toLong())
            val id = OccurrenceId(date.format(DATE), OccurrenceSource.SCHEDULED)
            if (st.latches.any { it.id == id }) continue
            val at = scheduledInstant(date, st.settings.defaultAlarmTime, zone)
            if (at > now) continue                              // not in the past yet
            if (st.session?.occurrenceId == id) continue        // currently ringing for it
            if (at < aliveSince) continue                       // already handled, or predates us

            val ov = st.override
            // An override that MOVED this occurrence later has not resolved it -- the
            // replacement instant is still ahead of us. Latching here would clear the
            // override in step 3 and delete the alarm the user just asked for.
            if (ov != null && ov.boundOccurrenceId == id && ov.kind == OverrideKind.TIME &&
                ov.fireAtMs != null && ov.fireAtMs > now) continue

            val suppressed = ov != null && ov.boundOccurrenceId == id   // DERIVED, never stored
            // Due while a real alarm is already ringing: the trigger is absorbed into that
            // ring, so it was not missed. A nap does not count, a real alarm supersedes it.
            val absorbed = st.session != null && st.session.trigger != OccurrenceSource.NAP
            val reasonFor = when {
                suppressed && ov!!.kind == OverrideKind.SKIP -> LatchReason.SKIPPED
                suppressed || absorbed -> LatchReason.SUPERSEDED
                else -> LatchReason.MISSED
            }

            // One rule: an alarm that should have gone off and did not, rings now.
            if (reasonFor == LatchReason.MISSED) fireNow = OccurrenceSource.SCHEDULED

            st = st.copy(latches = st.latches + Latch(id, reasonFor, now))
            events += PendingEvent("latch", mapOf("occurrence" to id.toString(), "reason" to reasonFor.name))
            if (reasonFor == LatchReason.MISSED) events += PendingEvent("missed", mapOf("occurrence" to id.toString()))
        }

        // A moved alarm whose new instant has passed with nothing ringing for it was
        // missed too. The loop above cannot see it: it walks the ORIGINAL instants, and
        // one older than lastAliveMs is skipped as already handled.
        st.override?.let { ov ->
            if (ov.kind == OverrideKind.TIME && ov.fireAtMs != null && ov.fireAtMs <= now &&
                st.session?.occurrenceId != ov.boundOccurrenceId &&
                st.latches.none { it.id == ov.boundOccurrenceId }) {
                fireNow = OccurrenceSource.SCHEDULED
                st = st.copy(latches = st.latches + Latch(ov.boundOccurrenceId, LatchReason.MISSED, now))
                events += PendingEvent("latch", mapOf("occurrence" to ov.boundOccurrenceId.toString(), "reason" to "MISSED"))
                events += PendingEvent("missed", mapOf("occurrence" to ov.boundOccurrenceId.toString()))
            }
        }

        // 3. An override clears when its bound occurrence acquires ANY latch.
        st.override?.let { ov ->
            if (st.latches.any { it.id == ov.boundOccurrenceId }) {
                events += PendingEvent("override_cleared", mapOf("reason" to "occurrence_resolved"))
                st = st.copy(override = null)
            }
        }

        // 4. Drop an elapsed nap.
        st.nap?.let { if (it.fireAtMs <= now) st = st.copy(nap = null) }

        // 5. Pick nextFire: the earliest of the three independent candidates.
        //
        // The override is a candidate IN ITS OWN RIGHT, not a special case of the next
        // scheduled occurrence. Earlier this was gated on
        // `ov.boundOccurrenceId == nextSchedId`, so the moment the bound occurrence's
        // original instant passed -- 05:00, with a 04:00 default and a 07:00 override --
        // the next scheduled occurrence became tomorrow, the condition went false, and
        // the override the user had just set stopped existing.
        val ov = st.override
        val overrideFire = ov?.takeIf {
            it.kind == OverrideKind.TIME && it.fireAtMs != null && it.fireAtMs > now
        }?.let { NextFire(it.fireAtMs!!, OccurrenceSource.TOMORROW_OVERRIDE, "override") }

        // The scheduled candidate, with the occurrence the override owns removed:
        // SKIP silences it, TIME replaces it. Either way it must not also fire itself.
        val scheduledFire: NextFire? = run {
            var probe = st
            repeat(8) {
                val (id, at) = nextUnlatchedScheduled(probe, ts)
                if (ov != null && ov.boundOccurrenceId == id) {
                    probe = probe.copy(latches = probe.latches + Latch(id, LatchReason.SKIPPED, now))
                } else {
                    return@run NextFire(at, OccurrenceSource.SCHEDULED, "scheduled")
                }
            }
            null
        }

        val napFire = st.nap?.let { NextFire(it.fireAtMs, OccurrenceSource.NAP, "nap") }
        val nextFire = listOfNotNull(overrideFire, scheduledFire, napFire).minByOrNull { it.atMs }

        // Ten years is 3650 latches, re-serialized on every save and linearly scanned
        // inside recompute's loops -- on the main thread, on the fire path.
        val cutoff = today.minusDays(30)
        val stale = st.latches.filter { LocalDate.parse(it.id.localDate).isBefore(cutoff) }
        if (stale.isNotEmpty()) st = st.copy(latches = st.latches - stale.toSet())

        // Monotonic: a bad RTC must never drag the liveness threshold backwards (which
        // would invent a fortnight of misses) nor leap it forwards (which would hide
        // real misses until the clock caught up).
        st = st.copy(stateVersion = st.stateVersion + 1,
            lastAliveMs = maxOf(st.lastAliveMs, now))
        events += PendingEvent("recompute", mapOf("reason" to reason,
            "nextFireAtMs" to (nextFire?.atMs?.toString() ?: "null")))

        return RecomputeResult(st, nextFire, events, fireNow)
    }

    // -----------------------------------------------------------------------
    // Transactions. Each returns a new state and ends by calling recompute().
    // -----------------------------------------------------------------------

    /** Set the next-alarm override by absolute wall time ("HH:mm"). */
    fun setOverrideTime(st: EngineState, ts: TimeSource, hhmm: String): RecomputeResult {
        val (boundId, _) = nextUnlatchedScheduled(st, ts)
        val (h, m) = hhmm.split(":").map { it.toInt() }
        val zone = ts.zone()
        val now = ts.nowMs()
        // First instant >= now matching that wall time.
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var at = ZonedDateTime.of(date.atTime(LocalTime.of(h, m)), zone).toInstant().toEpochMilli()
        if (at < now) {
            date = date.plusDays(1)
            at = ZonedDateTime.of(date.atTime(LocalTime.of(h, m)), zone).toInstant().toEpochMilli()
        }
        val next = st.copy(override = Override(boundId, OverrideKind.TIME, at))
        return recompute(next, ts, "override_set").let {
            it.copy(events = it.events + PendingEvent("override_set",
                mapOf("kind" to "TIME", "fireAtMs" to at.toString(), "bound" to boundId.toString())))
        }
    }

    fun setSkip(st: EngineState, ts: TimeSource): RecomputeResult {
        val (boundId, _) = nextUnlatchedScheduled(st, ts)
        val next = st.copy(override = Override(boundId, OverrideKind.SKIP, null))
        return recompute(next, ts, "override_skip").let {
            it.copy(events = it.events + PendingEvent("override_set", mapOf("kind" to "SKIP")))
        }
    }

    fun clearOverride(st: EngineState, ts: TimeSource): RecomputeResult =
        recompute(st.copy(override = null), ts, "override_clear").let {
            it.copy(events = it.events + PendingEvent("override_cleared", mapOf("reason" to "user")))
        }

    fun setNap(st: EngineState, ts: TimeSource, minutes: Int): RecomputeResult {
        val m = minutes.coerceIn(1, 720)
        val at = ts.nowMs() + m * 60_000L
        val next = st.copy(nap = Nap(at), settings = st.settings.copy(napMinutes = m))
        return recompute(next, ts, "nap_set").let {
            it.copy(events = it.events + PendingEvent("nap_set", mapOf("minutes" to m.toString(), "atMs" to at.toString())))
        }
    }

    fun clearNap(st: EngineState, ts: TimeSource): RecomputeResult =
        recompute(st.copy(nap = null), ts, "nap_clear")

    /**
     * A trigger arrived. Applies precedence: SCHEDULED/TOMORROW_OVERRIDE > NAP.
     * SPEC.md section 3.
     */
    fun onTrigger(st: EngineState, ts: TimeSource, source: OccurrenceSource, newRingId: String): RecomputeResult {
        val now = ts.nowMs()
        val open = st.session
        val events = mutableListOf<PendingEvent>()

        // Nap arriving while a real alarm rings: dropped.
        if (open != null && open.trigger != OccurrenceSource.NAP && source == OccurrenceSource.NAP) {
            events += PendingEvent("nap_dropped")
            val next = st.copy(nap = null)
            return recompute(next, ts, "nap_dropped").let { it.copy(events = events + it.events) }
        }

        // A trigger while SNOOZED cancels the snooze and rings now, same session.
        if (open != null && open.phase == RingPhase.SNOOZED && source == open.trigger) {
            val next = st.copy(session = open.copy(phase = RingPhase.RINGING, snoozeUntilMs = null))
            return recompute(next, ts, "snooze_cancelled_by_trigger").let { it.copy(events = events + it.events) }
        }

        // Real alarm superseding an open nap session: new ringId, fresh counters, audio never stops.
        var st1 = st
        if (open != null && open.trigger == OccurrenceSource.NAP && source != OccurrenceSource.NAP) {
            events += PendingEvent("superseded", mapOf("oldRingId" to open.ringId))
            st1 = st1.copy(
                session = null,
                lastOutcome = LastOutcome(Outcome.SUPERSEDED, now, open.occurrenceId.toString(), open.ringId, open.snoozeCount),
                latches = st1.latches + Latch(open.occurrenceId, LatchReason.SUPERSEDED, now),
            )
        } else if (open != null && open.endsByMs > now) {
            // Only one session at a time, ever. Absorb a duplicate.
            events += PendingEvent("trigger_absorbed", mapOf("ringId" to open.ringId))
            return recompute(st1, ts, "trigger_absorbed").let { it.copy(events = events + it.events) }
        }

        // An expired session is not a session: record it as CAPPED and open a fresh one.
        st1.session?.takeIf { it.endsByMs <= now }?.let { dead ->
            events += PendingEvent("capped", mapOf("ringId" to dead.ringId, "reason" to "expired_at_trigger"))
            st1 = st1.copy(
                session = null,
                lastOutcome = LastOutcome(Outcome.CAPPED, dead.endsByMs, dead.occurrenceId.toString(),
                    dead.ringId, dead.snoozeCount),
                latches = if (st1.latches.none { it.id == dead.occurrenceId } && dead.trigger != OccurrenceSource.NAP)
                    st1.latches + Latch(dead.occurrenceId, LatchReason.MISSED, dead.endsByMs) else st1.latches)
        }

        val occId = when (source) {
            OccurrenceSource.NAP -> OccurrenceId(localDateOf(now, ts.zone()), OccurrenceSource.NAP)
            else -> occurrenceBeingFired(st1, ts)
        }
        if (occId == null) {
            // Nothing is due and the last due occurrence already rang: a duplicate or a
            // resurrect after the cap. Ringing now would be the surprise.
            events += PendingEvent("trigger_stale")
            return recompute(st1, ts, "trigger_stale").let { it.copy(events = events + it.events) }
        }
        val session = RingSession(
            ringId = newRingId,
            occurrenceId = occId,
            startedAtMs = now,
            trigger = source,
            phase = RingPhase.RINGING,
            snoozeCount = 0,
            snoozeUntilMs = null,
            endsByMs = now + st1.settings.maxRingMinutes * 60_000L,
        )
        st1 = st1.copy(session = session, nap = if (source == OccurrenceSource.NAP) null else st1.nap)
        events += PendingEvent("ring_start", mapOf("ringId" to newRingId, "trigger" to source.name))
        return recompute(st1, ts, "ring_start").let { it.copy(events = events + it.events) }
    }

    /**
     * The occurrence this trigger is FOR, or null when it is for nothing: the most recent
     * scheduled instant at or before now, unless that one already rang (a duplicate, or
     * a resurrect after the cap) or was skipped. Never a future occurrence: binding a
     * ring to tomorrow latches tomorrow when it ends, and that is how a day gets lost.
     * A late delivery, even hours late after the phone was dead, still names today.
     */
    fun occurrenceBeingFired(st: EngineState, ts: TimeSource): OccurrenceId? {
        val now = ts.nowMs()
        val zone = ts.zone()

        // An override owns the instant if one is bound and due.
        st.override?.let { ov ->
            if (ov.kind == OverrideKind.TIME && ov.fireAtMs != null &&
                now >= ov.fireAtMs - 5_000 && now - ov.fireAtMs < 60 * 60_000L) {
                return ov.boundOccurrenceId
            }
        }
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        repeat(2) {
            val id = OccurrenceId(date.format(DATE), OccurrenceSource.SCHEDULED)
            val at = scheduledInstant(date, st.settings.defaultAlarmTime, zone)
            if (at <= now + 5_000) {
                val rang = st.lastOutcome?.occurrenceId == id.toString()
                val latch = st.latches.firstOrNull { it.id == id }
                val skipped = st.override?.let { it.boundOccurrenceId == id && it.kind == OverrideKind.SKIP } == true
                return if (rang || skipped || (latch != null && latch.reason != LatchReason.MISSED)) null else id
            }
            date = date.minusDays(1)
        }
        return null
    }

    fun snooze(st: EngineState, ts: TimeSource): RecomputeResult {
        val s = st.session ?: return recompute(st, ts, "snooze_noop")
        val now = ts.nowMs()
        val until = now + st.settings.snoozeSeconds * 1000L
        val next = st.copy(session = s.copy(
            phase = RingPhase.SNOOZED, snoozeCount = s.snoozeCount + 1, snoozeUntilMs = until))
        return recompute(next, ts, "snooze").let {
            it.copy(events = listOf(PendingEvent("snooze",
                mapOf("ringId" to s.ringId, "count" to (s.snoozeCount + 1).toString(),
                      "untilMs" to until.toString()))) + it.events)
        }
    }

    fun endSession(st: EngineState, ts: TimeSource, outcome: Outcome): RecomputeResult {
        val s = st.session ?: return recompute(st, ts, "end_noop")
        val now = ts.nowMs()
        // CAPPED means the siren ran an hour and the user never woke. Recording it as
        // FIRED makes a slept-through night indistinguishable from a normal one.
        val latchReason = if (outcome == Outcome.CAPPED) LatchReason.MISSED else LatchReason.FIRED
        var next = st.copy(
            session = null,
            lastOutcome = LastOutcome(outcome, now, s.occurrenceId.toString(), s.ringId, s.snoozeCount),
        )
        if (next.latches.none { it.id == s.occurrenceId } && s.trigger != OccurrenceSource.NAP) {
            next = next.copy(latches = next.latches + Latch(s.occurrenceId, latchReason, now))
        }
        val evType = when (outcome) {
            Outcome.DISMISSED_LOCAL -> "dismiss_local"
            Outcome.DISMISSED_REMOTE -> "dismiss_remote"
            Outcome.CAPPED -> "capped"
            else -> "session_end"
        }
        return recompute(next, ts, "session_end:$outcome").let {
            it.copy(events = listOf(PendingEvent(evType, mapOf(
                "ringId" to s.ringId,
                "snoozeCount" to s.snoozeCount.toString(),
                "ringingMs" to (now - s.startedAtMs).toString()))) + it.events)
        }
    }

    /** Settings patch. Gating is the caller's concern; floors are enforced here. */
    fun patchSettings(st: EngineState, ts: TimeSource, patch: Settings, who: Actor): RecomputeResult {
        val normalized = SettingsValidator.normalize(patch)
        val timeChanged = normalized.defaultAlarmTime != st.settings.defaultAlarmTime
        var next = st.copy(settings = normalized)
        if (timeChanged && next.override != null) next = next.copy(override = null)
        return recompute(next, ts, "settings_patch").let {
            it.copy(events = listOf(PendingEvent("settings_change",
                mapOf("who" to who.name, "diff" to diff(st.settings, normalized)))) + it.events)
        }
    }

    private fun diff(a: Settings, b: Settings): String = buildList {
        if (a.defaultAlarmTime != b.defaultAlarmTime) add("defaultAlarmTime:${a.defaultAlarmTime}->${b.defaultAlarmTime}")
        if (a.alarmVolumePercent != b.alarmVolumePercent) add("volume:${a.alarmVolumePercent}->${b.alarmVolumePercent}")
        if (a.snoozeSeconds != b.snoozeSeconds) add("snoozeSeconds:${a.snoozeSeconds}->${b.snoozeSeconds}")
        if (a.snoozeThresholdDegrees != b.snoozeThresholdDegrees) add("threshold:${a.snoozeThresholdDegrees}->${b.snoozeThresholdDegrees}")
        if (a.maxRingMinutes != b.maxRingMinutes) add("maxRingMinutes:${a.maxRingMinutes}->${b.maxRingMinutes}")
        if (a.vibrate != b.vibrate) add("vibrate:${a.vibrate}->${b.vibrate}")
        if (a.ringtoneUri != b.ringtoneUri) add("ringtone")
        if (a.ssid != b.ssid) add("ssid")
        if (a.passphrase != b.passphrase) add("passphrase")
        if (a.role != b.role) add("role:${a.role}->${b.role}")
    }.joinToString(",").ifEmpty { "none" }
}
