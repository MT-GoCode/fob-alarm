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
    @Volatile var status: String = "idle"
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun join(ctx: Context, ssid: String, passphrase: String) {
        // The approval store is credential-encrypted, so gate the join on unlock.
        val um = ctx.getSystemService(UserManager::class.java)
        if (!um.isUserUnlocked) { status = "waiting for unlock"; return }

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
                network = n; status = "connected"
                Svc.log("p2p_joined")
            }
            override fun onLost(n: Network) {
                network = null; status = "lost"
                Svc.log("p2p_lost")
            }
            override fun onUnavailable() {
                // ~30s / 3-scan cliff after which the request dies and does NOT resume
                // scanning, so the poll loop must re-request rather than wait.
                network = null; status = "unavailable"
                Svc.log("p2p_unavailable")
            }
        }
        callback = cb
        runCatching { c.requestNetwork(req, cb) }
            .onFailure { status = "request failed: ${it.message}" }
    }

    fun stop() {
        runCatching { callback?.let { cm?.unregisterNetworkCallback(it) } }
        callback = null; network = null
    }
}
