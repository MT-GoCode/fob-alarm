package com.mtrinh.fobalarm.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.mtrinh.fobalarm.core.Role

/**
 * The long-lived foreground service SPEC.md section 1 requires, and which did not exist.
 *
 * Two hard platform reasons it must be a foreground service, not an Application-scoped
 * coroutine:
 *
 *  - `WifiNetworkFactory.acceptRequest` rejects a `WifiNetworkSpecifier` request unless
 *    the app's importance is at least IMPORTANCE_FOREGROUND_SERVICE. Backgrounded, the
 *    controller's `requestNetwork` simply gets `onUnavailable` forever.
 *  - Once backgrounded the app drops to PRIORITY_BG, at which point
 *    `HalDeviceManager.allowedToDelete` lets almost anything evict the P2P interface.
 *
 * Without it the group, the HTTP control server and the join loop all die whenever the
 * process is reclaimed -- i.e. remote dismiss stops working at exactly the times nobody
 * is looking at the phone.
 */
class LinkService : Service() {

    companion object {
        const val CHANNEL = "link"
        const val NOTIF_ID = 44
        @Volatile var alive = false
        @Volatile private var instance: LinkService? = null
        /** Run the tick now with the backoff reset: something it was waiting for just changed. */
        fun nudge() {
            val s = instance ?: return
            s.handler.post { s.backoffMs = 5_000L; s.handler.removeCallbacks(s.tick); s.handler.post(s.tick) }
        }

        fun start(ctx: Context) {
            if (Svc.settings.role == null) return
            runCatching { ctx.startForegroundService(Intent(ctx, LinkService::class.java)) }
                .onFailure {
                    // From a cold background start Android refuses; the boot receiver,
                    // the activity or the next hourly tick starts it instead.
                    Svc.log(if (it is android.app.ForegroundServiceStartNotAllowedException)
                        "link_service_deferred" else "link_service_start_failed", "error" to it.toString())
                }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LinkService::class.java)) }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Boot.ensure(this)
        runCatching {
            registerReceiver(p2pReceiver, android.content.IntentFilter(
                android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED)
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Link",
                NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
        }
    }

    private val p2pReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Svc.log("p2p_connection_changed")
            Group.refresh(c)
        }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ControllerWatch.ACTION_REMOTE_DISMISS) {
            // From the notification. Here, not in the receiver: a flaky link can need
            // more than a broadcast's 10 s. Hold the CPU for it if nothing else does.
            if (wakeLock == null) getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:dismiss").acquire(60_000)
            Thread { ControllerWatch.dismissNow(this) }.start()
            return START_STICKY
        }
        if (Svc.settings.role == Role.CONTROLLER && wakeLock == null) {
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "fobalarm:watch")
                .also { it.acquire() }
        } else if (Svc.settings.role != Role.CONTROLLER && wakeLock != null) {
            runCatching { wakeLock?.release() }; wakeLock = null
        }
        runCatching { startForeground(NOTIF_ID, notification()) }
            .onFailure { Svc.log("link_fgs_failed", "error" to it.toString()) }
        alive = true
        instance = this
        Server.start(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    /** Owns the group (alarm) or the join loop (controller). Backoff with jitter. */
    private var backoffMs = 5_000L
    private var waitingLogged = false
    private val tick = object : Runnable {
        override fun run() {
            val s = Svc.settings
            when (s.role) {
                Role.ALARM -> {
                    // The sync window owns the group while it runs.
                    if (SyncWindow.running) { handler.postDelayed(this, 5_000); return }
                    if (!s.ssid.isNullOrBlank() && !s.passphrase.isNullOrBlank()) {
                        if (!Group.running) {
                            Group.start(this@LinkService, s)
                            backoffMs = (backoffMs * 2).coerceAtMost(120_000)
                        } else backoffMs = 5_000L
                        Group.refresh(this@LinkService)
                        Health.probe(this@LinkService)
                    }
                }
                Role.CONTROLLER -> {
                    if (!s.ssid.isNullOrBlank() && !s.passphrase.isNullOrBlank() &&
                        !P2pJoinBridge.joined()) {
                        if (P2pJoinBridge.allowed()) P2pJoinBridge.join(this@LinkService, s.ssid!!, s.passphrase!!)
                        else if (!waitingLogged) { waitingLogged = true; Svc.log("p2p_waiting_permission") }
                        // 15s, not 120s: a human is standing in front of this phone
                        // waiting for the dismiss button to work.
                        backoffMs = (backoffMs * 2).coerceAtMost(15_000)
                    } else {
                        // Linked: watch the alarm phone from the SERVICE, so the alert and
                        // its dismiss work with no Activity alive at all.
                        Thread { ControllerWatch.poll(this@LinkService) }.start()
                        backoffMs = if (ControllerWatch.ringing) 2_000L else 5_000L
                    }
                }
                null -> {}
            }
            // Jitter, so two phones retrying never lock into the same cadence.
            handler.postDelayed(this, backoffMs + (Math.random() * 2000).toLong())
        }
    }

    override fun onDestroy() {
        runCatching { wakeLock?.release() }; wakeLock = null
        runCatching { unregisterReceiver(p2pReceiver) }
        alive = false
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle(if (Svc.settings.role == Role.ALARM) "Alarm armed" else "Watching alarm phone")
        .setContentText(Svc.lastNextFire?.let {
            "next " + java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(it.atMs))
        } ?: "no alarm scheduled")
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setOngoing(true)
        .build()
}

/** Lets :service drive the controller's join without depending on :app. */
object P2pJoinBridge {
    @Volatile var ownerProvider: () -> String? = { null }
    @Volatile var joiner: (Context, String, String) -> Unit = { _, _, _ -> }
    /** Whether this phone may join at all (Nearby devices granted). Supplied by the app. */
    @Volatile var allowed: () -> Boolean = { true }
    fun ownerAddress() = ownerProvider()
    fun joined() = ownerProvider() != null
    fun join(ctx: Context, ssid: String, pass: String) = joiner(ctx, ssid, pass)
}
