package com.mtrinh.fobalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.os.UserManager
import com.mtrinh.fobalarm.service.Svc

/**
 * CONTROLLER side of the link: a Wi-Fi Direct CLIENT of the alarm phone's group, joined
 * by name and passphrase. No system dialog, no approval keyed on a radio address, and the
 * phone stays on home Wi-Fi at the same time. The earlier approach asked Android for the
 * group as if it were an ordinary Wi-Fi network; that put a "searching for device" box
 * over the screen for minutes and needed a tap every time the group's address changed.
 */
object P2pJoin {
    /** The alarm phone's address on the group while joined; null otherwise. */
    @Volatile var ownerAddress: String? = null
    val joined: Boolean get() = ownerAddress != null
    @Volatile private var pendingSinceMs = 0L

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var listening = false

    fun join(ctx: Context, ssid: String, passphrase: String) {
        val um = ctx.getSystemService(UserManager::class.java)
        if (!um.isUserUnlocked) return
        if (joined) return
        if (pendingSinceMs != 0L && System.currentTimeMillis() - pendingSinceMs < 45_000) return
        runCatching {
            val app = ctx.applicationContext
            val m = manager ?: app.getSystemService(WifiP2pManager::class.java) ?: return
            manager = m
            val ch = channel ?: m.initialize(app, Looper.getMainLooper(), null)
            channel = ch
            if (!listening) {
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        // A failed attempt ends here too; do not wait out the guard for it.
                        val ni = i.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (ni != null && !ni.isConnectedOrConnecting) pendingSinceMs = 0L
                        readConnection {}
                    }
                }, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
                listening = true
            }
            pendingSinceMs = System.currentTimeMillis()
            // The group may already be up from before this process (the framework keeps it
            // while any app holds a channel); connecting again then fails every time.
            readConnection { formed ->
                if (formed) { pendingSinceMs = 0L; return@readConnection }
                val cfg = WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(passphrase)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
                m.connect(ch, cfg, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Svc.log("p2p_connect_requested") }
                    override fun onFailure(reason: Int) {
                        pendingSinceMs = 0L
                        Svc.log("p2p_connect_failed", "reason" to reason.toString())
                    }
                })
            }
        }.onFailure { pendingSinceMs = 0L; Svc.log("p2p_request_failed", "error" to it.toString()) }
    }

    private fun readConnection(then: (formed: Boolean) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestConnectionInfo(ch) { info ->
                val addr = info?.takeIf { it.groupFormed && !it.isGroupOwner }?.groupOwnerAddress?.hostAddress
                val was = ownerAddress
                ownerAddress = addr
                if (addr != null) { pendingSinceMs = 0L; if (was == null) Svc.log("p2p_joined") }
                else if (was != null) Svc.log("p2p_lost")
                then(addr != null)
            }
        }.onFailure { then(false) }
    }

    /** Leave the group, so the next join uses new credentials. */
    fun stop() {
        val m = manager; val ch = channel
        if (m != null && ch != null) runCatching { m.removeGroup(ch, null) }
        ownerAddress = null; pendingSinceMs = 0L
    }
}
