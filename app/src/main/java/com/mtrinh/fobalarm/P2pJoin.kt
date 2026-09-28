package com.mtrinh.fobalarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.UserManager
import com.mtrinh.fobalarm.service.Svc

/**
 * CONTROLLER side of the link. Binds PER SOCKET, never per process: a process-wide bind
 * would also sever this phone from the Mac, breaking its own log pull and update channel.
 * SPEC.md section 1.
 */
object P2pJoin {
    @Volatile var network: Network? = null
    /** When the current request was issued; 0 once it resolved either way. */
    @Volatile var pendingSinceMs = 0L
    /** After Android gives up or the user cancels its box, leave them alone for a while. */
    @Volatile private var cooldownUntilMs = 0L
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun join(ctx: Context, ssid: String, passphrase: String) {
        // The approval store is credential-encrypted, so gate the join on unlock.
        val um = ctx.getSystemService(UserManager::class.java)
        if (!um.isUserUnlocked) return

        // A request in flight (scanning, the system dialog, associating, DHCP) must not be
        // torn down and re-issued every tick: unregistering it dismisses the dialog and
        // drops a connection in progress. Only a request that has gone quiet is replaced.
        if (pendingSinceMs != 0L && System.currentTimeMillis() - pendingSinceMs < 90_000) return
        if (System.currentTimeMillis() < cooldownUntilMs) return
        stop()
        val spec = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(passphrase)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val c = ctx.getSystemService(ConnectivityManager::class.java)
        cm = c
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n; pendingSinceMs = 0L
                Svc.log("p2p_joined")
            }
            override fun onLost(n: Network) {
                network = null; pendingSinceMs = 0L
                Svc.log("p2p_lost")
            }
            override fun onUnavailable() {
                // Android gave up (about 30 s of scanning) or the user cancelled its box.
                // Re-asking at once would put the box straight back over the screen.
                network = null; pendingSinceMs = 0L
                cooldownUntilMs = System.currentTimeMillis() + 120_000
                Svc.log("p2p_unavailable")
            }
        }
        callback = cb
        pendingSinceMs = System.currentTimeMillis()
        runCatching { c.requestNetwork(req, cb) }
            .onFailure { pendingSinceMs = 0L; Svc.log("p2p_request_failed", "error" to it.toString()) }
    }

    fun stop() {
        runCatching { callback?.let { cm?.unregisterNetworkCallback(it) } }
        callback = null; network = null; pendingSinceMs = 0L; cooldownUntilMs = 0L
    }
}
