package com.mtrinh.fobalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * CONTROLLER side. The alarm phone is ringing in the other room; this phone has to put
 * the dismiss button in front of the user without them unlocking anything. Same
 * mechanism the alarm phone uses for its own ring screen.
 */
object RemoteRingAlert {
    private const val CHANNEL = "remote_ring"
    private const val ID = 43

    private fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Remote alarm ringing",
            NotificationManager.IMPORTANCE_HIGH).apply {
            // Silent on this phone: the noise is the point of the OTHER phone. This one
            // only has to light up and show the button.
            setSound(null, null)
            enableVibration(true)
        })
    }

    fun raise(ctx: Context) {
        ensureChannel(ctx)
        val full = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Alarm is ringing")
            .setContentText("Press to dismiss remote alarm")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(full, true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, n) }
    }

    fun clear(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID) }
    }
}
