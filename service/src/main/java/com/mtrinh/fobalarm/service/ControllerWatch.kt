package com.mtrinh.fobalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mtrinh.fobalarm.core.Snapshot
import com.mtrinh.fobalarm.core.missingFor
import com.mtrinh.fobalarm.data.HttpStateClient
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * The CONTROLLER's watch on the alarm phone, owned by the foreground service.
 *
 * Remote dismiss used to depend on MainActivity being alive, not crashed, composing,
 * and polling -- nine separate things, several of them UI-lifecycle-scoped, for the one
 * control the owner will use half asleep. Now the service polls, the service raises the
 * full-screen alert, and the alert's DISMISS action is handled by the service. No
 * Activity has to exist for the alarm to be stoppable from this phone.
 */
object ControllerWatch {
    private const val CHANNEL = "remote_ring"
    private const val NOTIF_ID = 43
    const val ACTION_REMOTE_DISMISS = "com.mtrinh.fobalarm.REMOTE_DISMISS"

    @Volatile var lastSnapshot: Snapshot? = null; private set
    @Volatile var lastOkMs = 0L; private set
    @Volatile private var alertShownFor: String? = null

    private val client by lazy {
        HttpStateClient(
            hostProvider = { P2pJoinBridge.ownerAddress() },
            selfProvider = { Svc.selfDevice() },
            localBlockers = {
                GateEval.current(Svc.app, Svc.settings, Svc.lastNextFire != null).missingFor(alarmRole = false)
            },
        )
    }

    @Volatile private var polling = false

    /** One poll, one at a time. Called from the LinkService tick; never from the UI. */
    fun poll(ctx: Context) {
        if (polling) return
        polling = true
        try { pollOnce(ctx) } finally { polling = false }
    }

    private fun pollOnce(ctx: Context) {
        val r = runBlocking { client.snapshot() }
        r.onSuccess { s ->
            lastSnapshot = s
            lastOkMs = System.currentTimeMillis()
            val ringId = s.ring?.ringId
            if (ringId != null && ringId != alertShownFor) {
                alertShownFor = ringId
                raise(ctx, ringId)
            } else if (ringId == null && alertShownFor != null) {
                alertShownFor = null
                clear(ctx)
            }
        }
    }

    /** True while the alarm phone is ringing, as far as this phone last heard. */
    val ringing: Boolean get() = lastSnapshot?.ring != null

    /** The DISMISS action from the lock-screen notification. Idempotent by content. */
    fun dismissNow(ctx: Context) {
        // After a process restart we may know nothing yet; find out before giving up.
        if (lastSnapshot == null) runCatching { pollOnce(ctx) }
        val ringId = lastSnapshot?.ring?.ringId ?: run {
            Svc.log("remote_dismiss_no_ring", "reason" to if (lastSnapshot == null) "unreachable" else "not_ringing")
            return
        }
        val requestId = UUID.randomUUID().toString()         // same id: idempotent retries
        var r = runBlocking { client.dismiss(ringId, requestId) }
        var tries = 0
        while (r.exceptionOrNull() is com.mtrinh.fobalarm.data.ClientError.Transport && tries++ < 2) {
            Thread.sleep(1500)
            r = runBlocking { client.dismiss(ringId, requestId) }
        }
        r.onSuccess { s ->
            lastSnapshot = s; lastOkMs = System.currentTimeMillis()
            alertShownFor = null
            clear(ctx)
            Svc.log("remote_dismiss_from_notification", "ringId" to ringId)
        }.onFailure {
            Svc.log("remote_dismiss_failed", "error" to (it.message ?: "?"))
            // Leave the alert up: the user still needs a way to try again.
        }
    }

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Remote alarm ringing",
            NotificationManager.IMPORTANCE_HIGH).apply {
            // Silent here: the noise is the point of the OTHER phone.
            setSound(null, null)
            enableVibration(true)
        })
    }

    private fun raise(ctx: Context, ringId: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0,
            Intent().setClassName(ctx.packageName, "com.mtrinh.fobalarm.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(ctx, 8,
            Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_REMOTE_DISMISS)
                .setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Alarm is ringing")
            .setContentText("Press to dismiss remote alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(null, "DISMISS", dismiss).build())
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
        Svc.log("remote_ring_alert", "ringId" to ringId)
    }

    private fun clear(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }
}
