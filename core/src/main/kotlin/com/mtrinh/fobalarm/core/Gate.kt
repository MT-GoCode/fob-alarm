package com.mtrinh.fobalarm.core

/**
 * What a peer last told us about itself, recorded by the phone it polled.
 *
 * [lastSeenElapsed] is `SystemClock.elapsedRealtime`, never wall clock. A forward clock correction
 * is exactly what produces the `fireNow` path, and a wall-clock age would make a peer that polled
 * three seconds ago look absent for the length of the jump.
 */
data class PeerReport(
    val deviceId: String,
    val lastSeenElapsed: Long,
    val batteryPct: Int,
    val plugged: Boolean,
    val powerSave: Boolean,
    /** False when the peer sent no `powerSave` at all -- an older build, so unknown. */
    val reportedPowerSave: Boolean,
    val missingGates: List<String> = emptyList(),
    val appVersion: String = "?",
)

/**
 * Whether one role's phone is genuinely away, and if not, why not. [why] is the decision log's
 * token and the row's parenthetical.
 *
 * Four variants, not two, because they differ in what the owner can do:
 * - [Away] blocks, if its setting is armed.
 * - [Present] does not block now, and will once the phone has been gone past the floor. An armed
 *   block in this state is working.
 * - [Vetoed] cannot block even if the phone leaves: this app refuses to read silence as absence
 *   when the peer was untrustworthy or our own link was down. An armed block in this state does
 *   nothing.
 * - [Unknown] has no phone to judge at all.
 *
 * All but [Away] ring, so the gate treats them alike -- the distinction exists so that the row
 * which arms the block routes on a tag instead of recognising a `why` string, and so that a
 * working block is never described in the same words as an inert one.
 */
sealed interface RoleVerdict {
    val why: String
    data class Away(override val why: String) : RoleVerdict
    data class Present(override val why: String) : RoleVerdict
    data class Vetoed(override val why: String) : RoleVerdict
    data object Unknown : RoleVerdict { override val why = "never_seen" }
}

/**
 * One role's verdict as the snapshot carries it, and the sentence the row that arms its block
 * shows underneath the switch.
 *
 * The gate's own verdict is carried here rather than re-derived from the snapshot's presence
 * fields, so the row cannot disagree with the decision it describes.
 */
data class AwayView(val role: Role, val verdict: RoleVerdict) {

    /**
     * What the row says, or null when the switch is off and its state is the one the switch
     * already implies.
     *
     * An armed switch always gets a sentence, and the sentence says whether THIS block can stop a
     * ring -- an armed block that cannot fire is otherwise indistinguishable from one that can.
     *
     * Row-local, deliberately: [RingGate.fold] ORs the armed roles, so one role being unable to
     * block says nothing about whether the alarm rings. Only an armed [RoleVerdict.Away] may speak
     * of the ring itself, because that one does decide it.
     *
     * Hedged twice over as well: the gate decides at fire time and a phone can return or leave in
     * between, so nothing here promises what a future morning will do; and a nap goes straight to
     * [FireAction.Ring] without consulting the gate, so any sentence that promises silence says it
     * covers scheduled alarms only.
     */
    fun note(armed: Boolean): String? {
        val who = role.name.lowercase()
        return when (val v = verdict) {
            // The one sentence allowed to speak for the whole gate: an armed role that is Away
            // blocks whatever the other role says, because the fold returns on the first of them.
            is RoleVerdict.Away ->
                if (armed) "If a scheduled alarm fired now it would not ring: the $who is away."
                else "The $who is away now, so switching this on would stop a scheduled alarm ringing while it stays away."
            // Presence is in memory only, so this is honestly "since this app started" and never
            // "ever": a restart reads never-seen for a sentinel that has run for months.
            RoleVerdict.Unknown ->
                if (armed) "On, but no $who has reported since this app started, so this cannot stop a ring."
                else "No $who has reported since this app started, so this could not stop a ring."
            is RoleVerdict.Present ->
                if (armed) "On and waiting: this stops a scheduled alarm once the $who has been gone past the away limit."
                else null
            // Neutral subject on purpose: `own_link_unstable` is THIS phone's group being down,
            // not the peer's fault, and the row must not misattribute it.
            is RoleVerdict.Vetoed ->
                if (armed) "On, but this cannot stop a ring right now (${v.why})."
                else null
        }
    }
}

/** Whether a ring may happen. Three states, so the log site needs no string comparison. */
sealed interface RingVerdict {
    data class Blocked(val reason: String) : RingVerdict
    /** Allowed, with what each armed role said -- so a morning that rang still records why. */
    data class Allowed(val why: String) : RingVerdict
    /** Neither block setting is on, so there is no decision to record. */
    data object NotArmed : RingVerdict
}

/** What to do with a fire trigger. */
sealed interface FireAction {
    /** A session is already open; this trigger belongs to that ring. */
    data object Absorb : FireAction
    data object Ring : FireAction
    data class Suppress(val reason: String) : FireAction
}

/**
 * Whether the alarm may ring, and who may hold a role. Pure, so every branch is a unit test:
 * the caller supplies `elapsedRealtime` and the P2P listener's uptime rather than this reading
 * Android.
 *
 * **Every branch fails toward ringing.** A phone must be positively known-absent AND have been
 * trustworthy when last heard from. Anything unexplained rings.
 */
object RingGate {

    /** How recently a peer must have spoken to count as present. */
    const val PRESENT_WINDOW_MS = 120_000L

    /**
     * The owner's `awayAfterSeconds`, floored at [PRESENT_WINDOW_MS].
     *
     * The floor is not a preference and is not negotiable from the UI: a peer reports every 30 s,
     * so anything shorter than this app's own definition of "present" would read a phone in the
     * owner's pocket as having left and silence the alarm. Setting 0 therefore means "as soon as
     * absence is detectable at all", which is two minutes, not zero.
     */
    fun awayFloorMs(s: Settings): Long = maxOf(s.awayAfterSeconds * 1000L, PRESENT_WINDOW_MS)

    /** The roles whose absence can block a ring, in the order the OR below considers them. */
    val BLOCKABLE = listOf(Role.SENTINEL, Role.CONTROLLER)

    fun armed(s: Settings, role: Role): Boolean = when (role) {
        Role.SENTINEL -> s.blockRingIfSentinelAway
        Role.CONTROLLER -> s.blockRingIfControllerAway
        Role.ALARM -> error("the alarm phone cannot block on its own absence")
    }

    /**
     * Whether [deviceId] may act in a role whose slot [holder] currently occupies.
     *
     * **Not a security boundary and not trying to be.** `deviceId` is self-asserted. What it stops
     * is the owner, half asleep, turning their pocket phone into a controller and dismissing from
     * bed instead of walking to the box.
     *
     * A holder only blocks a claimant while it is actually present, so reinstalling a phone -- which
     * mints a new deviceId -- cannot lock the role out forever.
     */
    fun roleAvailable(holder: PeerReport?, deviceId: String, nowElapsed: Long): Boolean = when {
        holder == null -> true
        holder.deviceId == deviceId -> true
        else -> nowElapsed - holder.lastSeenElapsed >= PRESENT_WINDOW_MS
    }

    fun roleVerdict(
        role: Role, p: PeerReport?, s: Settings, nowElapsed: Long, linkUpSinceElapsed: Long,
    ): RoleVerdict {
        if (p == null) return RoleVerdict.Unknown
        if (p.missingGates.isNotEmpty()) return RoleVerdict.Vetoed("gates:${p.missingGates.joinToString("|")}")
        if (p.powerSave) return RoleVerdict.Vetoed("power_save")
        // Absent means the peer predates the field. Unknown trustworthiness is not trustworthy,
        // and "never blocks" is the safe way to be wrong.
        if (!p.reportedPowerSave) return RoleVerdict.Vetoed("no_power_report")
        // `< 20`, not `in 0..19`: an unreported battery is -1, and that must veto too.
        if (!p.plugged && p.batteryPct < 20) return RoleVerdict.Vetoed("low_battery:${p.batteryPct}")
        val absentMs = nowElapsed - p.lastSeenElapsed
        if (absentMs < awayFloorMs(s)) return RoleVerdict.Present("present:${absentMs / 1000}s")
        // This phone's OWN link being down must never read as the other phone having left.
        if (linkUpSinceElapsed == 0L || linkUpSinceElapsed > p.lastSeenElapsed)
            return RoleVerdict.Vetoed("own_link_unstable")
        return RoleVerdict.Away("${role.name.lowercase()} away ${absentMs / 1000}s")
    }

    /**
     * Every blockable role's verdict, armed or not. The ONE place the gate's inputs are gathered:
     * both the ring decision and the rows that arm it fold over this list, so the screen cannot be
     * reading a different clock, link or peer slot than the decision it describes.
     */
    fun views(
        s: Settings, nowElapsed: Long, linkUpSinceElapsed: Long, peer: (Role) -> PeerReport?,
    ): List<AwayView> =
        BLOCKABLE.map { AwayView(it, roleVerdict(it, peer(it), s, nowElapsed, linkUpSinceElapsed)) }

    /**
     * OR over the armed roles: either one genuinely away blocks. The whys of the roles that did
     * not block are kept, so a morning that rang still says why.
     *
     * Takes the views rather than gathering them, so the caller that needs both the decision and
     * the rows evaluates the gate's inputs exactly once.
     */
    fun fold(s: Settings, views: List<AwayView>): RingVerdict {
        val armed = views.filter { armed(s, it.role) }
        if (armed.isEmpty()) return RingVerdict.NotArmed
        val whys = mutableListOf<String>()
        for (v in armed) {
            // Exhaustive, so a new verdict variant is a compile error here rather than a silent
            // fall-through into "does not block".
            when (val rv = v.verdict) {
                is RoleVerdict.Away -> return RingVerdict.Blocked(rv.why)
                is RoleVerdict.Present, is RoleVerdict.Vetoed, RoleVerdict.Unknown ->
                    whys += "${v.role.name.lowercase()}=${rv.why}"
            }
        }
        return RingVerdict.Allowed(whys.joinToString(","))
    }

    /**
     * The one routing decision for a fire trigger. [blockReason] is a function, not a value,
     * because a nap must not consult the gate at all: calling it logs a decision, and a nap that
     * logged `ring_blocked` and then rang anyway was exactly the round 31 defect.
     */
    fun fireAction(
        sessionOpen: Boolean, trigger: OccurrenceSource, blockReason: () -> String?,
    ): FireAction = when {
        sessionOpen -> FireAction.Absorb
        trigger == OccurrenceSource.NAP -> FireAction.Ring
        else -> blockReason()?.let { FireAction.Suppress(it) } ?: FireAction.Ring
    }
}
