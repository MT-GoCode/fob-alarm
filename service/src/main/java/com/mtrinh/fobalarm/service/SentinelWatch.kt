package com.mtrinh.fobalarm.service

import com.mtrinh.fobalarm.core.Role
import com.mtrinh.fobalarm.core.Snapshot
import kotlinx.coroutines.runBlocking

/**
 * The SENTINEL's side of the link: a presence beacon, nothing more.
 *
 * It polls the alarm phone for one reason only -- so the alarm phone learns this phone is in
 * range. Being joined to the Wi-Fi Direct group IS the proximity proof; the poll is how that fact
 * gets reported. The poll carries this phone's own health (gates, battery, power-save), because the
 * alarm phone refuses to block a ring on the word of a sentinel that was not trustworthy when last
 * heard from.
 *
 * Deliberately NOT ControllerWatch: no full-screen alert, no DISMISS action, no authority. A
 * sentinel that could dismiss would be a controller with extra steps.
 */
object SentinelWatch {

    @Volatile var lastSnapshot: Snapshot? = null; private set
    @Volatile var lastOkMs = 0L; private set
    @Volatile var lastError: String? = null; private set

    private val client by lazy { PeerLink.client(Role.SENTINEL, PeerLink::sentinelExtras) }

    @Volatile private var polling = false

    /** One poll, one at a time. Called from the LinkService tick; never from the UI. */
    fun poll() {
        if (polling) return
        polling = true
        try { pollOnce() } finally { polling = false }
    }

    private fun pollOnce() {
        val r = runBlocking { client.snapshot() }
        r.onSuccess { s ->
            val was = lastOkMs
            lastSnapshot = s
            lastOkMs = System.currentTimeMillis()
            lastError = null
            PeerLink.learn(s)
            // Logged on THIS phone too, so a gap can be seen from either end. The alarm phone's
            // peer_gap is the measurement that sets the absence floor; this is the corroboration.
            if (was > 0L) {
                val gap = (lastOkMs - was) / 1000
                if (gap >= 60) Svc.log("sentinel_gap", "seconds" to gap.toString())
            }
        }.onFailure {
            lastError = it.message ?: it.toString()
            Svc.log("sentinel_poll_failed", "error" to (lastError ?: "?"))
        }
    }

    /** True while this phone has reached the alarm phone recently, as far as it knows. */
    val linked: Boolean get() = lastOkMs > 0L && System.currentTimeMillis() - lastOkMs < 120_000
}
