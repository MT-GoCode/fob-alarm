package com.mtrinh.fobalarm.service

import com.mtrinh.fobalarm.core.Role
import com.mtrinh.fobalarm.core.Snapshot
import com.mtrinh.fobalarm.core.missingFor
import com.mtrinh.fobalarm.data.HttpStateClient

/**
 * How a phone that is not the alarm reaches the alarm phone: the Wi-Fi Direct group owner first,
 * the alarm phone's ordinary LAN address as the fallback.
 *
 * The decision this hides is "which path", and it is hidden ONCE. It used to be wired in three
 * places -- both watchers and the UI's client -- and they had already diverged: the UI's copy was
 * built with no `fallbackHostProvider` at all, so with the group down the controller's screen and
 * every action on it failed while the background watcher carried on over Wi-Fi.
 *
 * Deliberately NOT a base class for the watchers. The sentinel must never inherit the controller's
 * authority -- no alert, no dismiss, no unlock -- so only the transport is shared and the two
 * behaviours stay entirely separate.
 */
object PeerLink {

    /**
     * The alarm phone's ordinary-network address, learned from its own snapshot while either path
     * was working.
     *
     * One owner. A phone is a controller or a sentinel, never both, so the two copies this
     * replaces were two owners of one fact, on the path that decides whether the alarm rings.
     */
    @Volatile var alarmLanIp: String? = null; private set

    /** `peer` is the alarm phone from this phone's point of view -- `withSelf` swaps them. */
    fun learn(s: Snapshot) {
        s.peer?.lanIp?.let { alarmLanIp = it }
    }

    fun client(role: Role, extra: () -> Map<String, String> = { emptyMap() }) = HttpStateClient(
        hostProvider = { P2pJoinBridge.ownerAddress() },
        fallbackHostProvider = { alarmLanIp },
        selfProvider = { Svc.selfDevice() },
        // Our own failing gates, so the alarm phone can show them -- and so it refuses to trust
        // this phone's absence while any of them is missing.
        localBlockers = {
            GateEval.current(Svc.app, Svc.settings, Svc.lastNextFire != null).missingFor(alarmRole = false)
        },
        extraParams = { mapOf("role" to role.name) + extra() },
    )

    /** What a SENTINEL adds to its poll beyond its role. */
    fun sentinelExtras(): Map<String, String> =
        // Battery saver throttles background work at ANY charge level once the owner turns it on
        // by hand, which the low-battery check would not catch.
        mapOf("powerSave" to Power.saveMode(Svc.app).toString())
}
