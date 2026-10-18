package com.mtrinh.fobalarm.service

import com.mtrinh.fobalarm.core.Role

/**
 * The ALARM phone's second presence channel for the sentinel: the ordinary network.
 *
 * Wi-Fi Direct membership is fragile in ways that have nothing to do with where the owner is --
 * Android's 10 s client-group idle timeout, the 45 s rejoin guard, this phone tearing its own group
 * down and taking up to 120 s to rebuild it. Every one of those reads as "the sentinel left" if
 * P2P is the only channel. Home Wi-Fi is unaffected by all of them, so checking both means a
 * sentinel that is home but momentarily unjoined is still seen.
 *
 * **Pull, never push.** This phone fetches the sentinel's read-only `:8766/v1/state`; the sentinel
 * cannot assert its own presence over the LAN. That direction is the whole security argument: a
 * push endpoint reachable from the home network would let anything on that network suppress an
 * alarm, which is strictly worse than letting it dismiss one.
 *
 * The address comes from the sentinel's own P2P poll (`lanIp`), so this only works for a phone that
 * has been seen at least once since the alarm phone started. That is fine: with no sighting at all
 * the gate reads "never seen" and rings.
 */
object SentinelLan {

    private const val PORT = 8766
    @Volatile private var lastTriedElapsed = 0L

    /** Minimum spacing between LAN probes. The P2P poll is the fast path; this is the backstop. */
    private const val MIN_INTERVAL_MS = 60_000L

    fun refresh() {
        val p = Svc.sentinelPresence ?: return
        val ip = p.lanIp ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        // Only worth asking once the P2P heartbeat has gone quiet, and never faster than a minute.
        if (now - p.lastSeenElapsed < 60_000) return
        if (lastTriedElapsed != 0L && now - lastTriedElapsed < MIN_INTERVAL_MS) return
        lastTriedElapsed = now

        val reached = runCatching {
            val url = java.net.URL("http://$ip:$PORT/v1/health")
            (url.openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 3000; readTimeout = 3000; requestMethod = "GET"
                try {
                    // Any answer at all proves the device is on the network. 503 means it is
                    // reachable but unhealthy, which is still present.
                    responseCode in 200..599
                } finally { disconnect() }
            }
        }.getOrDefault(false)

        if (reached) {
            // Refresh the timestamp only. Health stays as last REPORTED over P2P -- this channel
            // says "the device is here", not "the device is well".
            Svc.notePresence(Role.SENTINEL, p.copy(lastSeenElapsed = now))
            Svc.log("sentinel_seen_on_lan", "ip" to ip,
                "p2pSilentSeconds" to ((now - p.lastSeenElapsed) / 1000).toString())
        } else {
            Svc.log("sentinel_lan_unreachable", "ip" to ip)
        }
    }
}
