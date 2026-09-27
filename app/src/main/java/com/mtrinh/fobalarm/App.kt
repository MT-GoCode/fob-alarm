package com.mtrinh.fobalarm

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mtrinh.fobalarm.core.Role
import com.mtrinh.fobalarm.core.Variant
import com.mtrinh.fobalarm.data.LocalStateClient
import com.mtrinh.fobalarm.service.*
import com.mtrinh.fobalarm.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // With no logcat, an uncaught exception is otherwise invisible.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                Crash.write(this, t.name, e)
                Svc.log("crash", "thread" to t.name, "error" to e.toString())
            }
            prev?.uncaughtException(t, e)
        }

        Boot.configure(BuildConfig.VERSION_NAME,
            if (BuildConfig.VARIANT == "DEV") Variant.DEV else Variant.LIVE)
        Boot.ensure(this)

        ForceStopDetector.check(this)
        Crash.reportPending(this)
        Audio.ensureBundled(this)
        ClockObserver.poll()
        Server.start(this)
        Svc.log("app_start", "version" to BuildConfig.VERSION_NAME, "variant" to BuildConfig.VARIANT)

        wireRingScreen()
        startRoleLoop()
    }

    /**
     * The ring screen is the SAME shared renderer, fed by a local client. RingActivity
     * only ever appears on the alarm phone.
     */
    private fun wireRingScreen() {
        RingActivity.content = {
            val app = remember { AppState(LocalStateClient(Svc), scope).also { it.startPolling() } }
            Surface(Modifier.fillMaxSize()) {
                RootScreen(app = app, isAlarmRole = true, instrument = { RingInstrument() })
            }
        }
    }

    /** Role-specific background work: host the group, or keep joining it. */
    private fun startRoleLoop() {
        scope.launch {
            while (true) {
                val s = Svc.settings
                when (s.role) {
                    Role.ALARM -> {
                        if (s.ssid != null && s.passphrase != null && !Group.running) {
                            Group.start(this@App, s)
                        }
                        Group.refresh(this@App)
                    }
                    Role.CONTROLLER -> {
                        // The specifier request dies after a ~30s / 3-scan cliff and does
                        // NOT resume scanning, so re-request rather than wait.
                        if (s.ssid != null && s.passphrase != null && P2pJoin.network == null) {
                            P2pJoin.join(this@App, s.ssid!!, s.passphrase!!)
                        }
                    }
                    null -> {}
                }
                delay(20_000)
            }
        }
    }
}

@Composable
private fun RingInstrument() {
    var deg by remember { mutableDoubleStateOf(0.0) }
    var stale by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            deg = RingService.rotationDeg
            stale = RingService.rvStale
            delay(60)
        }
    }
    RotationInstrument(deg, Svc.settings.snoozeThresholdDegrees, null, stale, Modifier.fillMaxSize())
}
