package com.mtrinh.fobalarm.core

import kotlin.test.*

/**
 * The ring gate, which is the only logic in this app that can decide NOT to wake the owner.
 *
 * Every branch must fail toward ringing, so each test below names the mutant it catches: revert
 * that one condition and this test goes red. A test here that no mutant can turn red is worthless.
 */
class GateTest {

    /** `elapsedRealtime`, arbitrary but far from 0 so a missing offset cannot pass by accident. */
    private val NOW = 10_000_000L

    /** This phone's own P2P group came up long before anything else in these tests. */
    private val LINK_UP = 1_000_000L

    private fun peer(
        silentMs: Long,
        battery: Int = 80,
        plugged: Boolean = false,
        powerSave: Boolean = false,
        reported: Boolean = true,
        gates: List<String> = emptyList(),
        id: String = "peer-1",
    ) = PeerReport(id, NOW - silentMs, battery, plugged, powerSave, reported, gates)

    private fun settings(sentinel: Boolean = true, controller: Boolean = false, awaySec: Int = 600) =
        Settings(blockRingIfSentinelAway = sentinel, blockRingIfControllerAway = controller,
            awayAfterSeconds = awaySec)

    /** [p] occupies [role]'s slot; the other role has never been seen. */
    private fun verdict(
        s: Settings, p: PeerReport?, role: Role = Role.SENTINEL, linkUp: Long = LINK_UP,
    ) = RingGate.verdict(s, NOW, linkUp) { if (it == role) p else null }

    private fun assertRings(v: RingVerdict, expectWhy: String? = null) {
        assertTrue(v is RingVerdict.Allowed, "must ring, got $v")
        if (expectWhy != null) assertEquals(expectWhy, v.why)
    }

    private fun assertBlocked(v: RingVerdict): String {
        assertTrue(v is RingVerdict.Blocked, "must block, got $v")
        return v.reason
    }

    // --- the feature does something at all --------------------------------

    @Test fun `a sentinel silent past the floor blocks, and the reason names the role`() {
        val reason = assertBlocked(verdict(settings(), peer(silentMs = 20 * 60_000)))
        assertEquals("sentinel away 1200s", reason)
    }

    @Test fun `with both settings off nothing can block`() {
        assertEquals(RingVerdict.NotArmed,
            verdict(settings(sentinel = false, controller = false), peer(20 * 60_000)))
    }

    // --- fail-open branches: each of these must RING ----------------------

    @Test fun `a peer never seen rings`() {
        // Mutant: `p == null` returning Away. A cold start must ring, not block.
        assertRings(verdict(settings(), null), "sentinel=never_seen")
    }

    @Test fun `an unreported battery rings rather than blocks`() {
        // Mutant: `in 0..19` instead of `< 20` -- -1 escapes the range and reads as healthy.
        assertRings(verdict(settings(), peer(20 * 60_000, battery = -1)), "sentinel=low_battery:-1")
    }

    @Test fun `battery 19 unplugged rings and 20 unplugged blocks`() {
        assertRings(verdict(settings(), peer(20 * 60_000, battery = 19)))
        assertBlocked(verdict(settings(), peer(20 * 60_000, battery = 20)))
    }

    @Test fun `a plugged phone blocks however low its battery`() {
        // Mutant: dropping `!p.plugged` -- a charging phone at 5% is not about to die.
        assertBlocked(verdict(settings(), peer(20 * 60_000, battery = 5, plugged = true)))
    }

    @Test fun `a peer that never reported power save rings`() {
        // Mutant: trusting a missing field. An older build sends nothing, and absence of bad news
        // is not good news.
        assertRings(verdict(settings(), peer(20 * 60_000, reported = false)),
            "sentinel=no_power_report")
    }

    @Test fun `a peer in power save rings`() {
        // Mutant: dropping the powerSave veto. Its polling is throttled, so silence means nothing.
        assertRings(verdict(settings(), peer(20 * 60_000, powerSave = true)), "sentinel=power_save")
    }

    @Test fun `a peer with failing gates rings`() {
        assertRings(verdict(settings(), peer(20 * 60_000, gates = listOf("battery", "notif"))),
            "sentinel=gates:battery|notif")
    }

    @Test fun `a peer inside the floor rings`() {
        assertRings(verdict(settings(awaySec = 600), peer(9 * 60_000)), "sentinel=present:540s")
    }

    @Test fun `this phone's own link being down blocks nothing`() {
        // Mutant: dropping the listenerUpSinceElapsed guard. An outage on THIS phone would then
        // read as the owner having left -- silence caused by our own fault.
        assertRings(verdict(settings(), peer(20 * 60_000), linkUp = 0L), "sentinel=own_link_unstable")
    }

    @Test fun `a link that came up after the peer's last sighting blocks nothing`() {
        // The group restarted since we last heard the peer, so its silence is unexplained.
        // Mutant: `>=` for `>`, or comparing against NOW instead of the peer's sighting.
        assertRings(verdict(settings(), peer(20 * 60_000), linkUp = NOW - 60_000),
            "sentinel=own_link_unstable")
    }

    @Test fun `a link up exactly at the peer's last sighting still blocks`() {
        // The boundary the `>` is about. Equal means the group was already up when we last heard
        // the peer, so its silence since is the peer's, not ours. A review found my earlier test
        // could not tell `>` from `>=` -- both rang -- while claiming to cover this.
        val p = peer(silentMs = 20 * 60_000)
        assertBlocked(verdict(settings(), p, linkUp = p.lastSeenElapsed))
        assertRings(verdict(settings(), p, linkUp = p.lastSeenElapsed + 1), "sentinel=own_link_unstable")
    }

    // --- the floor ---------------------------------------------------------

    @Test fun `the away floor is never shorter than the presence window`() {
        // Mutant: honouring awayAfterSeconds literally. A 0 s floor would read a phone in the
        // owner's pocket as gone, because a peer only reports every 30 s.
        assertEquals(RingGate.PRESENT_WINDOW_MS, RingGate.awayFloorMs(settings(awaySec = 0)))
        assertEquals(RingGate.PRESENT_WINDOW_MS, RingGate.awayFloorMs(settings(awaySec = 119)))
        assertEquals(10 * 60_000L, RingGate.awayFloorMs(settings(awaySec = 600)))
        assertEquals(24 * 3600_000L, RingGate.awayFloorMs(settings(awaySec = 24 * 3600)))
    }

    @Test fun `with a zero setting the floor still holds at two minutes`() {
        assertRings(verdict(settings(awaySec = 0), peer(119_000)), "sentinel=present:119s")
        assertBlocked(verdict(settings(awaySec = 0), peer(121_000)))
    }

    // --- OR over the two roles --------------------------------------------

    @Test fun `each setting blocks only on its own role`() {
        val away = peer(20 * 60_000)
        // Controller armed, controller away -> blocks, and says controller.
        assertEquals("controller away 1200s", assertBlocked(
            RingGate.verdict(settings(sentinel = false, controller = true), NOW, LINK_UP) {
                if (it == Role.CONTROLLER) away else null
            }))
        // Sentinel armed, only the CONTROLLER is away -> the sentinel was never seen, so it rings.
        assertRings(verdict(settings(sentinel = true, controller = false), away,
            role = Role.CONTROLLER), "sentinel=never_seen")
    }

    @Test fun `either role being away is enough when both are armed`() {
        // Mutant: AND instead of OR. The sentinel is here; the controller has gone.
        val both = settings(sentinel = true, controller = true)
        val v = RingGate.verdict(both, NOW, LINK_UP) {
            if (it == Role.SENTINEL) peer(30_000) else peer(20 * 60_000)
        }
        assertEquals("controller away 1200s", assertBlocked(v))
    }

    @Test fun `when both are armed and both are here the log records both`() {
        val both = settings(sentinel = true, controller = true)
        val v = RingGate.verdict(both, NOW, LINK_UP) { peer(30_000) }
        assertRings(v, "sentinel=present:30s,controller=present:30s")
    }

    // --- role slots -------------------------------------------------------

    @Test fun `an empty slot is free and the holder keeps its own slot`() {
        assertTrue(RingGate.roleAvailable(null, "anyone", NOW))
        assertTrue(RingGate.roleAvailable(peer(1_000, id = "me"), "me", NOW))
    }

    @Test fun `a second phone is refused while the holder is present and admitted once it is not`() {
        // Mutant: dropping the silence check -- a reinstall mints a new deviceId and would be
        // locked out of its own role forever.
        assertFalse(RingGate.roleAvailable(peer(119_000, id = "old"), "new", NOW))
        assertTrue(RingGate.roleAvailable(peer(120_000, id = "old"), "new", NOW))
    }

    // --- the fire routing -------------------------------------------------

    @Test fun `an open session absorbs the trigger without consulting the gate`() {
        // Mutant: gating a trigger that will be absorbed -- that would stop a ring already in
        // progress, which is a mid-ring shutdown and an explicit non-goal.
        var asked = false
        val action = RingGate.fireAction(sessionOpen = true, OccurrenceSource.SCHEDULED) {
            asked = true; "sentinel away 1200s"
        }
        assertEquals(FireAction.Absorb, action)
        assertFalse(asked, "the gate must not even be consulted while a session is open")
    }

    @Test fun `a nap never consults the gate`() {
        // Round 31's defect: the gate was consulted first, so a nap logged `ring_blocked` and then
        // rang anyway -- history claiming a block that never happened.
        var asked = false
        val action = RingGate.fireAction(sessionOpen = false, OccurrenceSource.NAP) {
            asked = true; "sentinel away 1200s"
        }
        assertEquals(FireAction.Ring, action)
        assertFalse(asked, "a nap is set by someone standing there; the gate is irrelevant")
    }

    @Test fun `a scheduled trigger routes on the gate`() {
        assertEquals(FireAction.Ring,
            RingGate.fireAction(false, OccurrenceSource.SCHEDULED) { null })
        assertEquals(FireAction.Suppress("sentinel away 1200s"),
            RingGate.fireAction(false, OccurrenceSource.SCHEDULED) { "sentinel away 1200s" })
    }

    @Test fun `an override trigger routes on the gate too`() {
        // A moved alarm is still an alarm: it must be gated like a scheduled one, not exempt
        // like a nap.
        assertEquals(FireAction.Suppress("controller away 900s"),
            RingGate.fireAction(false, OccurrenceSource.TOMORROW_OVERRIDE) { "controller away 900s" })
    }
}
