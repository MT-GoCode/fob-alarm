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

/** Whether one role's phone is genuinely away. [why] and [reason] are both for the decision log. */
sealed interface RoleVerdict {
    data class Away(val reason: String) : RoleVerdict
    data class Here(val why: String) : RoleVerdict
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

    fun armedRoles(s: Settings): List<Role> = buildList {
        if (s.blockRingIfSentinelAway) add(Role.SENTINEL)
        if (s.blockRingIfControllerAway) add(Role.CONTROLLER)
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
        if (p == null) return RoleVerdict.Here("never_seen")
        if (p.missingGates.isNotEmpty()) return RoleVerdict.Here("gates:${p.missingGates.joinToString("|")}")
        if (p.powerSave) return RoleVerdict.Here("power_save")
        // Absent means the peer predates the field. Unknown trustworthiness is not trustworthy,
        // and "never blocks" is the safe way to be wrong.
        if (!p.reportedPowerSave) return RoleVerdict.Here("no_power_report")
        // `< 20`, not `in 0..19`: an unreported battery is -1, and that must veto too.
        if (!p.plugged && p.batteryPct < 20) return RoleVerdict.Here("low_battery:${p.batteryPct}")
        val absentMs = nowElapsed - p.lastSeenElapsed
        if (absentMs < awayFloorMs(s)) return RoleVerdict.Here("present:${absentMs / 1000}s")
        // This phone's OWN link being down must never read as the other phone having left.
        if (linkUpSinceElapsed == 0L || linkUpSinceElapsed > p.lastSeenElapsed)
            return RoleVerdict.Here("own_link_unstable")
        return RoleVerdict.Away("${role.name.lowercase()} away ${absentMs / 1000}s")
    }

    /**
     * OR over the armed roles: either one genuinely away blocks. The whys of the roles that did
     * not block are kept, so a morning that rang still says why.
     */
    fun verdict(
        s: Settings, nowElapsed: Long, linkUpSinceElapsed: Long, peer: (Role) -> PeerReport?,
    ): RingVerdict {
        val armed = armedRoles(s)
        if (armed.isEmpty()) return RingVerdict.NotArmed
        val whys = mutableListOf<String>()
        for (role in armed) {
            when (val v = roleVerdict(role, peer(role), s, nowElapsed, linkUpSinceElapsed)) {
                is RoleVerdict.Away -> return RingVerdict.Blocked(v.reason)
                is RoleVerdict.Here -> whys += "${role.name.lowercase()}=${v.why}"
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
