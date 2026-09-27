package com.mtrinh.fobalarm.core

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.*

class FakeClock(var ms: Long, var z: ZoneId = ZoneId.of("America/Los_Angeles")) : TimeSource {
    override fun nowMs() = ms
    override fun zone() = z
    fun set(iso: String) { ms = ZonedDateTime.parse(iso).toInstant().toEpochMilli() }
}

private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

class EngineTest {

    /** An ESTABLISHED install: firstSeenMs is well in the past, so history is real. */
    private fun fresh(iso: String = "2026-09-26T01:00:00-07:00[America/Los_Angeles]") =
        FakeClock(at(iso)) to EngineState(
            settings = Settings(defaultAlarmTime = "04:00"),
            lastAliveMs = at("2026-01-01T00:00:00-08:00[America/Los_Angeles]"))

    // --- basic scheduling -------------------------------------------------

    @Test fun `next fire is today 0400 when it is 0100`() {
        val (c, s) = fresh()
        val r = Engine.recompute(s, c, "t")
        assertEquals(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }

    @Test fun `next fire rolls to tomorrow once today has fired`() {
        val (c, s0) = fresh("2026-09-26T05:00:00-07:00[America/Los_Angeles]")
        val r = Engine.recompute(s0, c, "t")
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }

    // --- the latch rule ---------------------------------------------------

    @Test fun `a past occurrence always acquires exactly one latch`() {
        val (c, s0) = fresh("2026-09-26T05:00:00-07:00[America/Los_Angeles]")
        val r1 = Engine.recompute(s0, c, "t")
        val n = r1.state.latches.count { it.id.localDate == "2026-09-26" }
        assertEquals(1, n)
        val r2 = Engine.recompute(r1.state, c, "t")
        assertEquals(1, r2.state.latches.count { it.id.localDate == "2026-09-26" })
    }

    @Test fun `SKIP suppresses exactly one occurrence, never the next day too`() {
        // Friday 23:00, skip tomorrow's 04:00.
        val (c, s0) = fresh("2026-09-25T23:00:00-07:00[America/Los_Angeles]")
        val skipped = Engine.setSkip(s0, c)
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), skipped.nextFire!!.atMs)

        // Move past the skipped occurrence. Sunday must NOT also be suppressed.
        c.set("2026-09-26T06:00:00-07:00[America/Los_Angeles]")
        val after = Engine.recompute(skipped.state, c, "t")
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), after.nextFire!!.atMs)
        assertNull(after.state.override, "override must clear once its occurrence latched")
    }

    @Test fun `clearing an override restores the original occurrence`() {
        val (c, s0) = fresh("2026-09-26T01:00:00-07:00[America/Los_Angeles]")
        val ov = Engine.setOverrideTime(s0, c, "07:00")
        assertEquals(at("2026-09-26T07:00:00-07:00[America/Los_Angeles]"), ov.nextFire!!.atMs)
        val cleared = Engine.clearOverride(ov.state, c)
        assertEquals(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"), cleared.nextFire!!.atMs)
    }

    @Test fun `future-dated latches are discarded`() {
        val (c, s0) = fresh()
        val bogus = s0.copy(latches = listOf(
            Latch(OccurrenceId("2027-01-01", OccurrenceSource.SCHEDULED), LatchReason.FIRED, 0)))
        val r = Engine.recompute(bogus, c, "t")
        assertTrue(r.state.latches.none { it.id.localDate == "2027-01-01" })
    }

    // --- override binding -------------------------------------------------

    @Test fun `override at 0100 binds to today, at 0500 binds to tomorrow`() {
        val (c1, s1) = fresh("2026-09-26T01:00:00-07:00[America/Los_Angeles]")
        assertEquals(at("2026-09-26T07:00:00-07:00[America/Los_Angeles]"),
            Engine.setOverrideTime(s1, c1, "07:00").nextFire!!.atMs)

        val (c2, s2) = fresh("2026-09-26T05:00:00-07:00[America/Los_Angeles]")
        val r = Engine.setOverrideTime(s2, c2, "07:00")
        // Binds to tomorrow's occurrence, but the instant itself is the next 07:00 -- today.
        assertEquals(OccurrenceId("2026-09-27", OccurrenceSource.SCHEDULED),
            r.state.override!!.boundOccurrenceId)
    }

    @Test fun `spring forward into a non-existent local time still fires`() {
        // US DST 2027-03-14: 02:00 -> 03:00. An 02:30 alarm does not exist that day.
        val c = FakeClock(at("2027-03-13T12:00:00-08:00[America/Los_Angeles]"))
        val s = EngineState(settings = Settings(defaultAlarmTime = "02:30"),
            lastAliveMs = at("2026-01-01T00:00:00-08:00[America/Los_Angeles]"))
        val r = Engine.recompute(s, c, "t")
        assertNotNull(r.nextFire)
        assertTrue(r.nextFire!!.atMs > c.ms)
    }

    @Test fun `fall back ambiguity fires at the first occurrence`() {
        // 2027-11-07: 02:00 happens twice. Must pick the earlier (PDT, -07:00).
        val zone = ZoneId.of("America/Los_Angeles")
        val ms = Engine.scheduledInstant(java.time.LocalDate.parse("2027-11-07"), "01:30", zone)
        assertEquals(at("2027-11-07T01:30:00-07:00[America/Los_Angeles]"), ms)
    }

    @Test fun `timezone change clears override and nap`() {
        val (c, s0) = fresh()
        val withOv = Engine.setOverrideTime(s0, c, "07:00").state
        val withNap = Engine.setNap(withOv, c, 30).state
        c.z = ZoneId.of("America/New_York")
        val r = Engine.recompute(withNap, c, "tz")
        assertNull(r.state.override)
        assertNull(r.state.nap)
        assertTrue(r.events.any { it.type == "tz_change" })
    }

    // --- clock jumps ------------------------------------------------------
    @Test fun `a missed alarm rings, whenever it is noticed`() {
        // One rule, no grace window, no silent branch, no chirp variant.
        val s = EngineState(settings = Settings(defaultAlarmTime = "04:00"),
            lastAliveMs = at("2026-09-26T03:00:00-07:00[America/Los_Angeles]"))
        assertEquals(OccurrenceSource.SCHEDULED,
            Engine.recompute(s, FakeClock(at("2026-09-26T04:20:00-07:00[America/Los_Angeles]")), "t").fireNow)
        assertEquals(OccurrenceSource.SCHEDULED,
            Engine.recompute(s, FakeClock(at("2026-09-26T15:00:00-07:00[America/Los_Angeles]")), "t").fireNow)
    }

    @Test fun `backward jump past a fired occurrence does not re-fire`() {
        val (c, s0) = fresh("2026-09-26T05:00:00-07:00[America/Los_Angeles]")
        val fired = Engine.recompute(s0, c, "t").state
        c.set("2026-09-26T03:00:00-07:00[America/Los_Angeles]")
        val r = Engine.recompute(fired, c, "backward")
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }

    // --- trigger precedence -----------------------------------------------

    @Test fun `scheduled supersedes an open nap session with a fresh ringId`() {
        val (c, s0) = fresh("2026-09-26T03:50:00-07:00[America/Los_Angeles]")
        val nap = Engine.onTrigger(s0, c, OccurrenceSource.NAP, "nap-1").state
        assertEquals("nap-1", nap.session!!.ringId)
        c.set("2026-09-26T04:00:00-07:00[America/Los_Angeles]")
        val r = Engine.onTrigger(nap, c, OccurrenceSource.SCHEDULED, "sched-1")
        assertEquals("sched-1", r.state.session!!.ringId)
        assertEquals(0, r.state.session!!.snoozeCount)
        assertTrue(r.events.any { it.type == "superseded" })
    }

    @Test fun `nap arriving during a real alarm is dropped`() {
        val (c, s0) = fresh("2026-09-26T04:00:00-07:00[America/Los_Angeles]")
        val ring = Engine.onTrigger(s0, c, OccurrenceSource.SCHEDULED, "r1").state
        val r = Engine.onTrigger(ring, c, OccurrenceSource.NAP, "nap-1")
        assertEquals("r1", r.state.session!!.ringId)
        assertTrue(r.events.any { it.type == "nap_dropped" })
    }

    @Test fun `only one session exists at a time`() {
        val (c, s0) = fresh("2026-09-26T04:00:00-07:00[America/Los_Angeles]")
        val a = Engine.onTrigger(s0, c, OccurrenceSource.SCHEDULED, "r1").state
        val b = Engine.onTrigger(a, c, OccurrenceSource.SCHEDULED, "r2").state
        assertEquals("r1", b.session!!.ringId)
    }

    @Test fun `snooze counts against the session cap, not against airtime`() {
        val (c, s0) = fresh("2026-09-26T04:00:00-07:00[America/Los_Angeles]")
        val ring = Engine.onTrigger(s0, c, OccurrenceSource.SCHEDULED, "r1").state
        val endsBy = ring.session!!.endsByMs
        val snoozed = Engine.snooze(ring, c).state
        assertEquals(endsBy, snoozed.session!!.endsByMs, "you cannot snooze past the cap")
        assertEquals(1, snoozed.session!!.snoozeCount)
    }

    @Test fun `a trigger while snoozed rings now in the same session`() {
        val (c, s0) = fresh("2026-09-26T04:00:00-07:00[America/Los_Angeles]")
        val ring = Engine.onTrigger(s0, c, OccurrenceSource.SCHEDULED, "r1").state
        val snoozed = Engine.snooze(ring, c).state
        val again = Engine.onTrigger(snoozed, c, OccurrenceSource.SCHEDULED, "r2").state
        assertEquals("r1", again.session!!.ringId)
        assertEquals(RingPhase.RINGING, again.session!!.phase)
        assertNull(again.session!!.snoozeUntilMs)
    }

    // --- settings floors --------------------------------------------------

    @Test fun `volume floor and snooze ceiling cannot be bypassed`() {
        val s = SettingsValidator.normalize(Settings(alarmVolumePercent = 0, snoozeSeconds = 1800, maxRingMinutes = 1))
        assertEquals(50, s.alarmVolumePercent)
        assertEquals(600, s.snoozeSeconds)
        assertEquals(5, s.maxRingMinutes)
    }

    @Test fun `changing defaultAlarmTime clears the override`() {
        val (c, s0) = fresh()
        val ov = Engine.setOverrideTime(s0, c, "07:00").state
        val r = Engine.patchSettings(ov, c, ov.settings.copy(defaultAlarmTime = "05:00"), Actor.ALARM)
        assertNull(r.state.override)
    }

    @Test fun `nextFire is never null under normal settings`() {
        val (c, s0) = fresh()
        assertNotNull(Engine.recompute(s0, c, "t").nextFire)
    }
}

class RegressionTest {
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()
    private val established = at("2026-01-01T00:00:00-08:00[America/Los_Angeles]")
    /** Running normally right up to the fire. */
    private val alive = at("2026-09-26T03:59:00-07:00[America/Los_Angeles]")

    /**
     * The headline feature was deleting the alarm. Setting 07:00 at 01:00 worked, but the
     * next recompute latched the 04:00 occurrence SUPERSEDED while its replacement was
     * still in the future, which cleared the override. Result: no 07:00 alarm and no
     * 04:00 alarm -- no alarm at all that day.
     */
    @Test fun `an override moving the alarm LATER survives recompute`() {
        val c = FakeClock(at("2026-09-26T01:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"), lastAliveMs = established)
        val ov = Engine.setOverrideTime(st, c, "07:00")
        assertEquals(at("2026-09-26T07:00:00-07:00[America/Los_Angeles]"), ov.nextFire!!.atMs)

        c.set("2026-09-26T05:00:00-07:00[America/Los_Angeles]")   // the hourly tick
        val after = Engine.recompute(ov.state, c, "tick")
        assertNotNull(after.state.override, "the override must not be cleared before it fires")
        assertEquals(at("2026-09-26T07:00:00-07:00[America/Los_Angeles]"), after.nextFire!!.atMs)
    }

    @Test fun `a fire delivered slightly late binds to today, not tomorrow`() {
        val c = FakeClock(at("2026-09-26T04:00:00.120-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"), lastAliveMs = alive)
        val r = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1")
        assertEquals("2026-09-26", r.state.session!!.occurrenceId.localDate)
        assertTrue(r.events.none { it.type == "missed" }, "a normal morning must log no missed event")
        assertTrue(r.state.latches.none {
            it.id.localDate == "2026-09-26" && it.reason == LatchReason.MISSED })
    }

    @Test fun `a fire delivered minutes late still binds to today`() {
        val c = FakeClock(at("2026-09-26T04:07:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"), lastAliveMs = alive)
        val r = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1")
        assertEquals("2026-09-26", r.state.session!!.occurrenceId.localDate)
    }

    /** A fresh install (or a destructive migration) must not invent a fortnight of failures. */
    @Test fun `a brand new install neither rings immediately nor reports missed`() {
        val c = FakeClock(at("2026-09-26T06:00:00-07:00[America/Los_Angeles]"))
        val r = Engine.recompute(EngineState(settings = Settings(defaultAlarmTime = "04:00")), c, "init")
        assertNull(r.fireNow, "a fresh install must not ring on launch")
        assertTrue(r.state.latches.none { it.reason == LatchReason.MISSED },
            "occurrences before this install existed are not ours to miss")
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }

    /** A slept-through night must be distinguishable from a successful wake. */
    @Test fun `CAPPED latches MISSED, not FIRED`() {
        val c = FakeClock(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"), lastAliveMs = established)
        val ring = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1").state
        c.set("2026-09-26T05:00:00-07:00[America/Los_Angeles]")
        val done = Engine.endSession(ring, c, Outcome.CAPPED)
        assertEquals(LatchReason.MISSED,
            done.state.latches.first { it.id.localDate == "2026-09-26" }.reason)
    }

    /** Ten years of daily latches must not accumulate on the fire path. */
    @Test fun `latches are pruned to a bounded window`() {
        val c = FakeClock(at("2026-09-26T05:00:00-07:00[America/Los_Angeles]"))
        val old = (1..400).map {
            Latch(OccurrenceId("2025-0${(it % 9) + 1}-0${(it % 9) + 1}", OccurrenceSource.SCHEDULED),
                LatchReason.FIRED, 0)
        }
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"),
            lastAliveMs = established, latches = old)
        val r = Engine.recompute(st, c, "t")
        assertTrue(r.state.latches.size < 40, "expected pruning, got ${r.state.latches.size}")
    }
}

class RotationTest {
    /** The failure this estimator exists to prevent: a motionless phone must never snooze. */
    @Test fun `a motionless phone never reaches threshold`() {
        val acc = RotationAccumulator(thresholdDeg = 120)
        var t = 0L
        val q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        repeat(60 * 50) {                      // one minute at 50 Hz
            t += 20
            // jitter well under the deadband
            val n = doubleArrayOf(1.0, 0.00002 * (it % 3 - 1), 0.0, 0.0)
            assertFalse(acc.onRotationVector(n, t))
        }
        assertEquals(0.0, acc.degrees, 1.0)
    }

    @Test fun `a real rotation crosses the threshold`() {
        val acc = RotationAccumulator(thresholdDeg = 120)
        var t = 0L
        var fired = false
        // 200 deg/s about x for one second: 4 deg per 20 ms sample.
        repeat(50) {
            t += 20
            val ang = Math.toRadians(4.0 * (it + 1)) / 2
            val q = doubleArrayOf(Math.cos(ang), Math.sin(ang), 0.0, 0.0)
            if (acc.onRotationVector(q, t)) fired = true
        }
        assertTrue(fired, "120 deg of real rotation must trigger")
    }

    @Test fun `the accumulator decays after stillness so a snooze is one gesture`() {
        val acc = RotationAccumulator(thresholdDeg = 120)
        var t = 0L
        repeat(20) {
            t += 20
            val ang = Math.toRadians(4.0 * (it + 1)) / 2
            acc.onRotationVector(doubleArrayOf(Math.cos(ang), Math.sin(ang), 0.0, 0.0), t)
        }
        assertTrue(acc.degrees > 50)
        val still = doubleArrayOf(Math.cos(Math.toRadians(40.0)), Math.sin(Math.toRadians(40.0)), 0.0, 0.0)
        repeat(300) { t += 20; acc.onRotationVector(still, t) }
        assertEquals(0.0, acc.degrees, 0.001)
    }
}

class AuthTest {
    @Test fun `password verifies and a wrong one does not`() {
        val salt = Auth.newSalt()
        val s = Settings(passwordHash = Auth.hash("hunter2", salt), passwordSalt = salt)
        assertTrue(Auth.accepts(s, "hunter2"))
        assertFalse(Auth.accepts(s, "hunter3"))
    }

    @Test fun `declining a password leaves the gate open`() {
        assertTrue(Auth.accepts(Settings(), "anything"))
    }
}

class NagTest {
    private fun snap(nextAtMs: Long, ringing: Boolean = false, skip: Boolean = false) = Snapshot(
        stateVersion = 1, serverTimeMs = 0, bootedAtMs = 0, mode = Mode.WAITING,
        gates = Gates(0, true, true, true, true, true, true, true, true, true, true, true,
            true, true, true, true, true, true, true, true),
        armGate = null,
        nextFire = NextFire(nextAtMs, OccurrenceSource.SCHEDULED, "scheduled"),
        ring = if (ringing) RingView("r1", 0, OccurrenceSource.SCHEDULED, RingPhase.RINGING,
            0, null, 0, 0.0, 120, 0.0, false, false, "ok") else null,
        clock = ClockView(0, 0, 0, "x", 0),
        ap = ApView(null, true, 1, 0, null),
        self = DeviceView(90, true, "1", Role.CONTROLLER, "d"),
        peer = null, lastOutcome = null, appVersion = "1", settingsSchemaVersion = 1,
        tomorrow = TomorrowView(if (skip) "SKIP" else "NONE", null, null),
        nap = NapView(false, null), settings = Settings(), lastEvents = emptyList())

    /** The headline case: alive, polling fine, silently did not ring. */
    @Test fun `silent failure fires even while contact is perfect`() {
        val now = 1_000_000_000L
        val r = Nag.evaluate(snap(now - 200_000), lastOkMs = now - 1_000, nowMs = now)
        assertEquals(Nag.Reason.SILENT_FAILURE, r,
            "ANDing this with the no-contact condition made it unreachable")
    }

    @Test fun `no contact fires independently`() {
        val now = 1_000_000_000L
        val r = Nag.evaluate(snap(now + 3600_000), lastOkMs = now - 25 * 60_000, nowMs = now)
        assertEquals(Nag.Reason.NO_CONTACT, r)
    }

    @Test fun `a deliberately skipped night never nags`() {
        val now = 1_000_000_000L
        assertEquals(Nag.Reason.NONE,
            Nag.evaluate(snap(now - 200_000, skip = true), now - 1_000, now))
        assertEquals(Nag.Reason.NONE,
            Nag.evaluate(snap(now + 1000, skip = true), now - 25 * 60_000, now))
    }

    @Test fun `an actual ring session suppresses the nag`() {
        val now = 1_000_000_000L
        assertEquals(Nag.Reason.NONE,
            Nag.evaluate(snap(now - 200_000, ringing = true), now - 1_000, now))
    }

    @Test fun `a healthy future alarm does not nag`() {
        val now = 1_000_000_000L
        assertEquals(Nag.Reason.NONE, Nag.evaluate(snap(now + 3600_000), now - 1_000, now))
    }
}

class PasswordTest2 {
    @Test fun `a set password rejects the empty string and the wrong one`() {
        val ps = Auth.newSalt()
        val s = Settings(passwordHash = Auth.hash("pw", ps), passwordSalt = ps)
        assertFalse(Auth.accepts(s, ""))
        assertFalse(Auth.accepts(s, "nope"))
        assertTrue(Auth.accepts(s, "pw"))
        assertFalse(Auth.gateOpen(s))
    }

    @Test fun `no password means the gate is open, by design`() {
        assertTrue(Auth.gateOpen(Settings()))
        assertTrue(Auth.accepts(Settings(), ""))
    }

}

/**
 * A session that outlived its own cap must never absorb tomorrow's alarm.
 *
 * Reachable whenever the process dies more than maxRingMinutes into a ring and the
 * watchdog PendingIntent goes with it -- force-stop, hibernation, battery pull, an OTA
 * reboot mid-ring. Android 15+ cancels pending intents on force-stop, so this is not
 * exotic.
 */
class ZombieSessionTest {
    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun `an expired session does not swallow the next alarm`() {
        val c = FakeClock(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00", maxRingMinutes = 60),
            lastAliveMs = at("2026-09-26T03:00:00-07:00[America/Los_Angeles]"))

        val ringing = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1").state
        assertEquals("r1", ringing.session!!.ringId)

        // The process dies. Nothing reaps the session. A day passes.
        c.set("2026-09-27T04:00:00-07:00[America/Los_Angeles]")
        val next = Engine.onTrigger(ringing, c, OccurrenceSource.SCHEDULED, "r2")

        assertNotNull(next.state.session, "tomorrow must have a session")
        assertEquals("r2", next.state.session!!.ringId,
            "the stale session absorbed the trigger: the alarm rings for a fraction of a " +
            "second and stops, every morning, forever")
        assertTrue(next.events.none { it.type == "trigger_absorbed" })
        assertTrue(next.events.any { it.type == "capped" }, "r1 must be recorded as capped")
        assertTrue(next.state.latches.any { it.id.localDate == "2026-09-26" },
            "the day r1 belonged to must be latched, or it re-fires")
    }

    @Test fun `recompute closes a session that outlived its cap`() {
        val c = FakeClock(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00", maxRingMinutes = 60),
            lastAliveMs = at("2026-09-26T03:00:00-07:00[America/Los_Angeles]"))
        val ringing = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1").state

        c.set("2026-09-26T06:00:00-07:00[America/Los_Angeles]")   // two hours later
        val r = Engine.recompute(ringing, c, "tick")

        assertNull(r.state.session, "an expired session must be reaped")
        assertEquals(Outcome.CAPPED, r.state.lastOutcome?.kind)
        assertNotNull(r.nextFire)
        assertNull(r.fireNow, "a reaped session must not ring again today")
        assertTrue(r.events.none { it.type == "missed" }, "capped is not missed")
    }

    // --- what a trigger is FOR ----------------------------------------------

    @Test fun `a trigger for an occurrence that already rang is dropped`() {
        val c = FakeClock(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00", maxRingMinutes = 60),
            lastAliveMs = at("2026-09-26T03:00:00-07:00[America/Los_Angeles]"))
        val ringing = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1").state
        c.set("2026-09-26T04:05:00-07:00[America/Los_Angeles]")
        val done = Engine.endSession(ringing, c, Outcome.DISMISSED_LOCAL).state

        c.set("2026-09-26T04:06:00-07:00[America/Los_Angeles]")
        val r = Engine.onTrigger(done, c, OccurrenceSource.SCHEDULED, "r2")

        assertNull(r.state.session, "a duplicate trigger must not ring a dismissed alarm again")
        assertTrue(r.events.any { it.type == "trigger_stale" })
        assertEquals(1, r.state.latches.count { it.id.localDate == "2026-09-26" })
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }

    @Test fun `a resurrect after the cap does not ring again`() {
        val c = FakeClock(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00", maxRingMinutes = 60),
            lastAliveMs = at("2026-09-26T03:00:00-07:00[America/Los_Angeles]"))
        val ringing = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1").state

        // The ring service dies; the watchdog delivers a trigger just after the cap.
        c.set("2026-09-26T05:00:30-07:00[America/Los_Angeles]")
        val r = Engine.onTrigger(ringing, c, OccurrenceSource.SCHEDULED, "r2")

        assertNull(r.state.session, "the cap is the cap: no second ring for the same morning")
        assertTrue(r.events.any { it.type == "capped" })
        assertEquals(Outcome.CAPPED, r.state.lastOutcome?.kind)
        assertTrue(r.state.latches.any { it.id.localDate == "2026-09-26" && it.reason == LatchReason.MISSED })
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }

    @Test fun `an alarm missed while the phone was dead rings for today, not tomorrow`() {
        val c = FakeClock(at("2026-09-26T06:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"),
            lastAliveMs = at("2026-09-26T03:00:00-07:00[America/Los_Angeles]"))
        val back = Engine.recompute(st, c, "boot")
        assertEquals(OccurrenceSource.SCHEDULED, back.fireNow, "a missed alarm simply rings")

        val r = Engine.onTrigger(back.state, c, OccurrenceSource.SCHEDULED, "r1")
        assertEquals("2026-09-26", r.state.session!!.occurrenceId.localDate)

        c.set("2026-09-26T06:03:00-07:00[America/Los_Angeles]")
        val done = Engine.endSession(r.state, c, Outcome.DISMISSED_REMOTE)
        assertEquals("2026-09-26/SCHEDULED", done.state.lastOutcome!!.occurrenceId)
        assertEquals(1, done.state.latches.count { it.id.localDate == "2026-09-26" })
        assertTrue(done.events.none { it.type == "latch_discarded" })
        assertEquals(at("2026-09-27T04:00:00-07:00[America/Los_Angeles]"), done.nextFire!!.atMs)
    }

    @Test fun `a stale trigger before the alarm time does not consume today`() {
        val c = FakeClock(at("2026-09-25T04:00:00-07:00[America/Los_Angeles]"))
        val st = EngineState(settings = Settings(defaultAlarmTime = "04:00"),
            lastAliveMs = at("2026-09-25T03:00:00-07:00[America/Los_Angeles]"))
        val ringing = Engine.onTrigger(st, c, OccurrenceSource.SCHEDULED, "r1").state
        c.set("2026-09-25T04:02:00-07:00[America/Los_Angeles]")
        val done = Engine.endSession(ringing, c, Outcome.DISMISSED_LOCAL).state

        c.set("2026-09-26T03:00:00-07:00[America/Los_Angeles]")
        val r = Engine.onTrigger(done, c, OccurrenceSource.SCHEDULED, "r2")

        assertNull(r.state.session)
        assertTrue(r.state.latches.none { it.id.localDate == "2026-09-26" },
            "binding a stray ring to today would silently skip today's 04:00")
        assertEquals(at("2026-09-26T04:00:00-07:00[America/Los_Angeles]"), r.nextFire!!.atMs)
    }
}
