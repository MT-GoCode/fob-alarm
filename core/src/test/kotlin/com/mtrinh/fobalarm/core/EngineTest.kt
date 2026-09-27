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

    private fun fresh(iso: String = "2026-09-26T01:00:00-07:00[America/Los_Angeles]") =
        FakeClock(at(iso)) to EngineState(settings = Settings(defaultAlarmTime = "04:00"))

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

    @Test fun `snooze-tomorrow accumulates instead of re-basing`() {
        val (c, s0) = fresh("2026-09-25T23:00:00-07:00[America/Los_Angeles]")
        val base = at("2026-09-26T04:00:00-07:00[America/Los_Angeles]")
        val once = Engine.shiftOverride(s0, c, 30)
        assertEquals(base + 30 * 60_000L, once.state.override!!.fireAtMs)
        val twice = Engine.shiftOverride(once.state, c, 30)
        assertEquals(base + 60 * 60_000L, twice.state.override!!.fireAtMs,
            "two +30m taps must be +1h, not +30m")
    }

    // --- DST --------------------------------------------------------------

    @Test fun `spring forward into a non-existent local time still fires`() {
        // US DST 2027-03-14: 02:00 -> 03:00. An 02:30 alarm does not exist that day.
        val c = FakeClock(at("2027-03-13T12:00:00-08:00[America/Los_Angeles]"))
        val s = EngineState(settings = Settings(defaultAlarmTime = "02:30"))
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

    @Test fun `forward jump inside the wake window rings instead of latching silently`() {
        // Phone comes up with a wrong clock, NITZ corrects it to 04:20.
        val c = FakeClock(at("2026-09-26T04:20:00-07:00[America/Los_Angeles]"))
        val s = EngineState(settings = Settings(defaultAlarmTime = "04:00", missedGraceMinutes = 15))
        val r = Engine.recompute(s, c, "clock_jump")
        assertEquals(OccurrenceSource.SCHEDULED, r.fireNow, "must ring, not latch silently")
        assertFalse(r.chirpMissed)
    }

    @Test fun `forward jump past the wake window chirps audibly`() {
        val c = FakeClock(at("2026-09-26T15:00:00-07:00[America/Los_Angeles]"))
        val s = EngineState(settings = Settings(defaultAlarmTime = "04:00"))
        val r = Engine.recompute(s, c, "clock_jump")
        assertNull(r.fireNow)
        assertTrue(r.chirpMissed, "a banner alone would be a silent failure")
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

    @Test fun `the recovery code also opens the gate`() {
        val ps = Auth.newSalt(); val rs = Auth.newSalt()
        val code = Auth.newRecoveryCode()
        val s = Settings(
            passwordHash = Auth.hash("pw", ps), passwordSalt = ps,
            recoveryHash = Auth.hash(code, rs), recoverySalt = rs)
        assertTrue(Auth.accepts(s, code))
    }

    @Test fun `declining a password leaves the gate open`() {
        assertTrue(Auth.accepts(Settings(), "anything"))
    }
}
