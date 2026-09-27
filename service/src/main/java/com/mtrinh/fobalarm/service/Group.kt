package com.mtrinh.fobalarm.service

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import com.mtrinh.fobalarm.core.Settings

/**
 * Wi-Fi Direct autonomous group owner with FIXED credentials.
 *
 * NOT local-only hotspot: a non-system app cannot set the SSID or passphrase on LOHS --
 * the call succeeds, onStarted() fires, the gate goes green, and the AP is
 * AndroidShare_<random> that the controller can never find. Worst failure shape available.
 * setNetworkName/setPassphrase on WifiP2pConfig are plain public since API 29 and are
 * genuinely honoured. SPEC.md section 1.
 */
object Group {
    @Volatile var running = false
    @Volatile var lastStartedAtMs = 0L
    @Volatile var lastError: String? = null
    @Volatile var ownerAddress: String? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null

    fun start(ctx: Context, s: Settings) {
        val ssid = s.ssid ?: return
        val pass = s.passphrase ?: return
        if (running) return
        runCatching {
            val m = manager ?: ctx.getSystemService(WifiP2pManager::class.java) ?: return
            manager = m
            // Initialize ONCE: a fresh channel per retry leaked a binder object every
            // few seconds on a phone that could not form a group.
            val ch = channel ?: m.initialize(ctx, Looper.getMainLooper(), null)
            channel = ch
            val cfg = WifiP2pConfig.Builder()
                .setNetworkName(if (ssid.startsWith("DIRECT-")) ssid else "DIRECT-fa-$ssid")
                .setPassphrase(pass)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .build()
            m.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    running = true; lastError = null
                    lastStartedAtMs = System.currentTimeMillis()
                    Svc.log("ap_start", "ssid" to ssid)
                    refresh(ctx)
                }
                override fun onFailure(reason: Int) {
                    running = false
                    lastError = "createGroup failed ($reason)"
                    Svc.log("ap_error", "reason" to reason.toString())
                }
            })
        }.onFailure {
            lastError = it.toString()
            Svc.log("ap_error", "error" to it.toString())
        }
    }

    fun refresh(ctx: Context) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.requestGroupInfo(ch) { g: WifiP2pGroup? ->
                reportedClients = g?.clientList?.size ?: 0
                running = g != null
                ownerAddress = "192.168.49.1"
            }
        }
    }

    @Volatile private var reportedClients = 0

    /**
     * A peer that polled us in the last minute IS connected, whatever the P2P client
     * list says -- it lags, and it was reporting "no clients" while the other phone was
     * actively talking to us.
     */
    val clientCount: Int
        get() {
            val seen = Svc.peerDevice?.lastSeenMs ?: 0
            val peerLive = seen > 0 && System.currentTimeMillis() - seen < 60_000
            return maxOf(reportedClients, if (peerLive) 1 else 0)
        }

    fun stop(ctx: Context) {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching {
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { running = false; Svc.log("ap_stop") }
                override fun onFailure(reason: Int) { Svc.log("ap_error", "stop" to reason.toString()) }
            })
        }
    }

    /** Credentials changed: tear down and restart. The controller must re-enter them. */
    fun restart(ctx: Context, s: Settings) {
        stop(ctx)
        Thread.sleep(1500)
        running = false
        start(ctx, s)
    }
}
