package com.mtrinh.fobalarm

import android.app.Application
import android.app.ApplicationStartInfo
import android.app.ActivityManager
import android.os.Build

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DeState.init(this)
        Log.init(this)

        // Persist crashes: with no logcat, an uncaught exception is otherwise invisible.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { Log.e("crash", "thread" to t.name, "error" to e.toString(),
                "stack" to e.stackTraceToString().take(4000)) }
            prev?.uncaughtException(t, e)
        }

        Log.e("app_start")
        detectForceStop()
        Alarm.recompute(this, "app_start")
        LogServer.start(this)
    }

    /** Hibernation and force-stop both cancel every PendingIntent. Make it visible. */
    private fun detectForceStop() {
        if (Build.VERSION.SDK_INT < 35) return
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            val infos = am.getHistoricalProcessStartReasons(1)
            val i: ApplicationStartInfo = infos.firstOrNull() ?: return
            if (i.wasForceStopped()) Log.e("force_stopped_detected")
        }
    }
}
