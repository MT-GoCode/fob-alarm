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
    ) = ringVerdict(s, linkUp) { if (it == role) p else null }

    /** The production path: gather the views once, then fold them. */
    private fun ringVerdict(s: Settings, linkUp: Long = LINK_UP, peer: (Role) -> PeerReport?) =
        RingGate.fold(s, RingGate.views(s, NOW, linkUp, peer))

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
            ringVerdict(settings(sentinel = false, controller = true)) {
                if (it == Role.CONTROLLER) away else null
            }))
        // Sentinel armed, only the CONTROLLER is away -> the sentinel was never seen, so it rings.
        assertRings(verdict(settings(sentinel = true, controller = false), away,
            role = Role.CONTROLLER), "sentinel=never_seen")
    }

    @Test fun `either role being away is enough when both are armed`() {
        // Mutant: AND instead of OR. The sentinel is here; the controller has gone.
        val both = settings(sentinel = true, controller = true)
        val v = ringVerdict(both) {
            if (it == Role.SENTINEL) peer(30_000) else peer(20 * 60_000)
        }
        assertEquals("controller away 1200s", assertBlocked(v))
    }

    @Test fun `when both are armed and both are here the log records both`() {
        val both = settings(sentinel = true, controller = true)
        val v = ringVerdict(both) { peer(30_000) }
        assertRings(v, "sentinel=present:30s,controller=present:30s")
    }

    // --- what the screen is told about a block's reach --------------------

    private fun note(v: RoleVerdict, armed: Boolean) = AwayView(Role.SENTINEL, v).note(armed)

    private val everyVerdict = listOf(
        RoleVerdict.Away("sentinel away 1200s"), RoleVerdict.Present("present:3s"),
        RoleVerdict.Vetoed("power_save"), RoleVerdict.Unknown)

    @Test fun `armed reads each role's own setting and refuses the alarm's`() {
        // Mutant: `Role.ALARM -> false`. The alarm phone's own absence is not a thing, and
        // answering "not armed" for it would hide the caller's mistake instead of showing it.
        val s = settings(sentinel = true, controller = false)
        assertTrue(RingGate.armed(s, Role.SENTINEL))
        assertFalse(RingGate.armed(s, Role.CONTROLLER))
        assertFailsWith<IllegalStateException> { RingGate.armed(s, Role.ALARM) }
    }

    @Test fun `an armed block states its reach whatever the verdict`() {
        everyVerdict.forEach {
            assertNotNull(note(it, armed = true), "an armed block said nothing for $it")
        }
    }

    @Test fun `an armed block that cannot stop a ring says so`() {
        // The defect this exists for. A switch reading on while the gate cannot judge its phone
        // absent is indistinguishable from a working block, and the morning it rings is the only
        // report. Mutant: returning the blocking sentence regardless of the verdict.
        listOf(RoleVerdict.Vetoed("power_save"), RoleVerdict.Unknown).forEach {
            val n = note(it, armed = true)!!
            assertTrue(n.contains("cannot stop a ring"), "no disclosure in: $n")
            assertFalse(n.contains("would not ring"), "claims a block it has not got: $n")
        }
        assertTrue(note(RoleVerdict.Away("sentinel away 1200s"), armed = true)!!
            .contains("would not ring"))
    }

    @Test fun `only an armed Away speaks for the whole gate`() {
        // note() sees one role; fold ORs both. With both switches on, the sentinel Unknown and the
        // controller away, the gate blocks -- so a sentinel row saying "the alarm will ring" would
        // contradict the controller row directly below it, and be the wrong one.
        // An armed Away may speak of the ring: the fold returns on the first armed Away, so one
        // being away decides the outcome whatever the other says.
        // Mutant: any non-Away sentence stating a gate outcome.
        listOf(RoleVerdict.Present("present:3s"), RoleVerdict.Vetoed("power_save"), RoleVerdict.Unknown)
            .flatMap { listOf(note(it, true), note(it, false)) }.filterNotNull().forEach {
                assertFalse(it.contains("the alarm will"), "a row-local fact stated as the gate's: $it")
            }
        val both = settings(sentinel = true, controller = true)
        val v = ringVerdict(both) { if (it == Role.SENTINEL) null else peer(20 * 60_000) }
        assertEquals("controller away 1200s", assertBlocked(v),
            "the premise: an unknown sentinel does not stop the controller's block")
    }

    @Test fun `an armed block whose phone is present is not described as broken`() {
        // The same failure reversed, and the one the first attempt at these sentences shipped: a
        // block that is armed and working, every night, saying the alarm will ring. The owner
        // learns to ignore the line and then ignores it on the night it is true.
        // Mutant: Present sharing the Vetoed sentence -- `present:3s` is a healthy state.
        val n = note(RoleVerdict.Present("present:3s"), armed = true)!!
        assertFalse(n.contains("cannot stop a ring"), "a working block called itself broken: $n")
        assertTrue(n.contains("stops a scheduled alarm"), "a working block must say it works: $n")
        // Present means "silent for less than the floor", and the floor goes up to 24 h -- so it
        // must not claim the phone is here. With a 1 h limit, a phone gone 50 min is still Present.
        assertFalse(n.contains("is here"), "Present is not a sighting: $n")
    }

    @Test fun `a switch that is off speaks only when flipping it would surprise`() {
        // Mutant: the Unknown arm returning null when unarmed. Arming a block against a phone that
        // has never reported must be visible BEFORE it is armed, not after a morning.
        assertNotNull(note(RoleVerdict.Unknown, armed = false))
        assertNotNull(note(RoleVerdict.Away("sentinel away 1200s"), armed = false))
        // Nothing to correct: the switch is off and its phone is healthy.
        assertNull(note(RoleVerdict.Present("present:3s"), armed = false))
        assertNull(note(RoleVerdict.Vetoed("power_save"), armed = false))
    }

    @Test fun `the sentences that promise silence say they cover scheduled alarms only`() {
        // A nap goes straight to Ring without consulting the gate, so an unqualified "would not
        // ring" is false for the one kind of ring the owner sets by hand.
        val away = RoleVerdict.Away("sentinel away 1200s")
        listOf(note(away, true)!!, note(away, false)!!,
            note(RoleVerdict.Present("present:3s"), true)!!).forEach {
            assertTrue(it.contains("scheduled"), "a nap ignores the gate, so this promises too much: $it")
        }
    }

    @Test fun `the sentence names its role, except where naming one would misattribute`() {
        // Mutant: a hard-coded role in the sentence. Two rows differing only by subject.
        listOf(RoleVerdict.Away("controller away 1200s"), RoleVerdict.Present("present:3s"),
            RoleVerdict.Unknown).forEach {
            assertTrue(AwayView(Role.CONTROLLER, it).note(true)!!.contains("controller"),
                "the controller's row talked about something else: $it")
        }
        // Vetoed is the deliberate exception: `own_link_unstable` is THIS phone's group being
        // down, so naming the peer there blames it for our own fault.
        assertFalse(AwayView(Role.CONTROLLER, RoleVerdict.Vetoed("own_link_unstable")).note(true)!!
            .contains("controller"), "a veto of ours was blamed on the peer")
    }

    @Test fun `a never-seen peer is Unknown and a vetoed one is not merely present`() {
        // The tags the screen routes on, derived once here where the peer is looked at, so no
        // reader has to recognise the string "never_seen" or "power_save".
        assertEquals(RoleVerdict.Unknown,
            RingGate.roleVerdict(Role.SENTINEL, null, settings(), NOW, LINK_UP))
        assertTrue(RingGate.roleVerdict(Role.SENTINEL, peer(30_000), settings(), NOW, LINK_UP)
                is RoleVerdict.Present)
        // A healthy phone inside the floor is Present; an unhealthy one is Vetoed however recently
        // it spoke. Mutant: one Here for both -- the row then calls a working block broken.
        assertTrue(RingGate.roleVerdict(
            Role.SENTINEL, peer(30_000, powerSave = true), settings(), NOW, LINK_UP)
                is RoleVerdict.Vetoed)
    }

    @Test fun `the rows and the ring decision come from one evaluation`() {
        // Mutant: `views` dropping the unarmed roles. The screen must be able to describe a block
        // the owner has not armed yet, while the decision still ignores it.
        val s = settings(sentinel = true, controller = false)
        val views = RingGate.views(s, NOW, LINK_UP) { peer(20 * 60_000) }
        assertEquals(listOf(Role.SENTINEL, Role.CONTROLLER), views.map { it.role })
        // Both are away, only the sentinel is armed, and the reason names the armed one.
        assertEquals("sentinel away 1200s", assertBlocked(RingGate.fold(s, views)))
        assertEquals(RingVerdict.NotArmed,
            RingGate.fold(settings(sentinel = false, controller = false), views))
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

    // --- the gate table ---------------------------------------------------

    private fun allGatesPassing() = Gates(
        evaluatedAtMs = 1, scheduleExists = true, exactAlarm = true, foregroundService = true,
        p2pSupported = true, groupCredentialsSet = true, localNetworkPermission = true,
        notificationPolicyAccess = true, dndAllowsAlarms = true, volumeNotFixed = true,
        fullScreenIntent = true, notHibernating = true, thermalOk = true, audioPlayable = true,
        powerOk = true, vibrationEnabled = true, freeDiskOk = true, noBluetoothAudio = true,
        batteryUnrestricted = true)

    @Test fun `every key in the gate table reads a real field`() {
        // `Gates.value` used to return `true` for an unrecognised key, so a typo in the table --
        // or a field added to Gates and never listed -- turned a real check into a green row.
        // It now errors, which makes this test the proof that the table and the data class agree.
        // The wire encoding iterates the same table, so this also covers what reaches the
        // controller.
        val allPassing = allGatesPassing()
        assertTrue(GateInfo.ALL.isNotEmpty())
        GateInfo.ALL.forEach { info ->
            assertTrue(allPassing.value(info.key), "gate ${info.key} did not read its field")
        }
        assertTrue(allPassing.allPass, "all fields true must mean allPass")
    }

    /** Nothing granted, so what a role reports IS its full blocking set. */
    private fun nothingGranted() = Gates(
        evaluatedAtMs = 1, scheduleExists = false, exactAlarm = false, foregroundService = false,
        p2pSupported = false, groupCredentialsSet = false, localNetworkPermission = false,
        notificationPolicyAccess = false, dndAllowsAlarms = false, volumeNotFixed = false,
        fullScreenIntent = false, notHibernating = false, thermalOk = false, audioPlayable = false,
        powerOk = false, vibrationEnabled = false, freeDiskOk = false, noBluetoothAudio = false,
        batteryUnrestricted = false)

    @Test fun `the exact set each role reports is written out here, not read from the table`() {
        // Written by hand, in full. Deriving the expectation from `forRole` would move with any
        // mutant and assert nothing -- which is how a set that handed the sentinel a lock-screen
        // permission went unnoticed. What a role REPORTS is what the peer's gate vetoes on, so
        // these three lists are the contract, not an implementation detail.
        val none = nothingGranted()
        assertEquals(
            setOf("foregroundService", "localNetworkPermission", "batteryUnrestricted"),
            none.missingFor(Role.SENTINEL).toSet(),
            "a sentinel neither rings nor alerts; it only has to be reachable and un-throttled")
        assertEquals(
            setOf("foregroundService", "fullScreenIntent", "localNetworkPermission",
                "batteryUnrestricted"),
            none.missingFor(Role.CONTROLLER).toSet(),
            "the controller adds exactly one thing: it shows the alert over the lock screen")
        assertEquals(
            setOf("foregroundService", "exactAlarm", "fullScreenIntent", "localNetworkPermission",
                "batteryUnrestricted"),
            none.missingFor(Role.ALARM).toSet(),
            "the alarm phone answers for everything, including scheduling")
    }

    @Test fun `a sentinel without the lock-screen permission can still block a ring`() {
        // The defect: `forRole(alarmRole: Boolean)` handed the SENTINEL the CONTROLLER's set, and
        // a joiner REPORTS its missing blocking gates to the alarm phone, which vetoes the away
        // block of any peer missing one. So a phone that never rings was required to grant "show
        // over the lock screen" or its absence could never silence anything -- which is most
        // likely why the sentinel block had never been seen to fire.
        val g = allGatesPassing().copy(fullScreenIntent = false)
        assertEquals(emptyList(), g.missingFor(Role.SENTINEL),
            "a sentinel does not alert, so this must not be held against it")
        assertTrue(g.missingFor(Role.CONTROLLER).contains("fullScreenIntent"),
            "the controller DOES alert over the lock screen and still needs it")
        // The consequence, through the gate rather than asserted of the table: such a sentinel,
        // genuinely away, blocks.
        assertEquals("sentinel away 1200s",
            assertBlocked(verdict(settings(), peer(20 * 60_000, gates = g.missingFor(Role.SENTINEL)))))
    }

    @Test fun `a sentinel still needs whatever makes its silence meaningful`() {
        // NOT "fewer permissions". Each of these decides whether Android may stall its polling,
        // and a stalled sentinel goes quiet while sitting on the bedside table -- trusting that
        // silence would suppress the alarm. Vetoing an untrustworthy sentinel fails toward
        // ringing, which is the gate's whole invariant.
        val all = allGatesPassing()
        assertTrue(all.copy(foregroundService = false).missingFor(Role.SENTINEL).contains("foregroundService"))
        assertTrue(all.copy(localNetworkPermission = false).missingFor(Role.SENTINEL).contains("localNetworkPermission"))
        assertTrue(all.copy(batteryUnrestricted = false).missingFor(Role.SENTINEL).contains("batteryUnrestricted"))
        // `notHibernating` is shown in Setup but is NOT reported, so it never vetoes -- it is
        // advisory. Asserted explicitly because the first version of this test claimed the
        // opposite and went red, and because `blocking` is doing two jobs: "setup wall" and
        // "peer veto". See DEBT.
        assertFalse(all.copy(notHibernating = false).missingFor(Role.SENTINEL).contains("notHibernating"))
        // And the alarm phone answers for everything, including what it alone needs.
        assertTrue(all.copy(exactAlarm = false).missingFor(Role.ALARM).contains("exactAlarm"))
        assertEquals(emptyList(), all.copy(exactAlarm = false).missingFor(Role.SENTINEL),
            "a sentinel schedules nothing, so the alarm's own permissions are not its business")
    }

    @Test fun `an unknown gate key is an error, never a pass`() {
        // The test above only supplies keys that exist, so it cannot tell `else -> error()` from
        // `else -> true` -- every real key resolves through its own branch either way. The
        // mutation check caught that, which is what it is for. This is the test that observes the
        // else-arm: an unrecognised key must refuse to answer rather than answer "passing".
        assertFailsWith<IllegalStateException> { allGatesPassing().value("noSuchGate") }
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
