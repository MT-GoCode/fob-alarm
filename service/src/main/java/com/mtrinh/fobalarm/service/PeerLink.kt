package com.mtrinh.fobalarm.service

import com.mtrinh.fobalarm.core.Role
import com.mtrinh.fobalarm.core.Snapshot
import com.mtrinh.fobalarm.core.missingFor
import com.mtrinh.fobalarm.data.HttpStateClient

/**
 * How a phone that is not the alarm reaches the alarm phone: the Wi-Fi Direct group owner first,
 * the alarm phone's ordinary LAN address as the fallback.
 *
 * The decision this hides is "which path", and it is hidden ONCE -- for both watchers and the
 * UI's client. Wired per caller, one of them can be built without a fallback, and then the screen
 * and its actions fail during a P2P outage while the background watcher carries on.
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
     * ONE owner: a phone is a controller or a sentinel, never both, so a copy per watcher
     * would be two owners of one fact on the path that decides whether the alarm rings.
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
