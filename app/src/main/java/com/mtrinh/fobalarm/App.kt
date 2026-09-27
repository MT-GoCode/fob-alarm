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

        // Up first and cheap, so that if anything below fails the trace is still
        // readable over http://<phone>:8766/v1/logs -- there is no logcat here.
        Crash.reportPending(this)
        Boot.ensure(this)
        Fmt.use24h = android.text.format.DateFormat.is24HourFormat(this)
        wireRingScreen()

        // Owns the group / join loop and the control server, and keeps the process at
        // foreground-service importance so Wi-Fi Direct is not evicted.
        P2pJoinBridge.networkProvider = { P2pJoin.network }
        P2pJoinBridge.joiner = { ctx, ssid, pass -> P2pJoin.join(ctx, ssid, pass) }
        LinkService.start(this)

        // Everything that touches disk, sensors, audio or a service binding goes to a
        // worker. Application.onCreate is on the main thread and blocking it is an ANR.
        Thread({
            runCatching { ForceStopDetector.check(this) }
            runCatching { Audio.ensureBundled(this) }
            runCatching { ClockObserver.poll() }
            runCatching { GateEval.refresh(this, Svc.settings, Svc.lastNextFire != null) }
            Svc.log("app_start", "version" to BuildConfig.VERSION_NAME,
                "variant" to BuildConfig.VARIANT)
        }, "fobalarm-init").start()

    }

    /**
     * The ring screen is the SAME shared renderer, fed by a local client. RingActivity
     * only ever appears on the alarm phone.
     */
    private fun wireRingScreen() {
        RingActivity.content = { activity ->
            // Its own scope, cancelled with the activity: an Application-scoped poll
            // would accumulate a forever-loop on every recreation.
            val scope = rememberCoroutineScope()
            val app = remember {
                AppState(LocalStateClient(Svc), scope, isLocal = true).also {
                    it.startPolling()
                    it.onStopTest = { Svc.stopTest() }
                }
            }
            val snap = app.snapshot
            // Finish ONLY when the ring is genuinely over -- an open session or a live
            // test. Never render the tabbed screen here: a snapshot flicker used to drop
            // the user into Settings mid-ring.
            // Observe the test window through the snapshot; Svc.testActive is a plain
            // getter and never triggers recomposition, so the screen used to linger.
            val ringing = snap == null || snap.ring != null ||
                    (snap.testUntilMs > 0 && System.currentTimeMillis() < snap.testUntilMs)
            LaunchedEffect(ringing) { if (!ringing) activity.finish() }
            // Feed live sensor values so the instrument works during a TEST, which has
            // no engine session and therefore no RingView.
            LaunchedEffect(Unit) {
                while (true) {
                    app.testRotationDeg = RingService.rotationDeg
                    app.testQuaternion = RingService.quaternion
                    delay(80)
                }
            }
            Surface(Modifier.fillMaxSize()) {
                snap?.let { RingOnlyScreen(app, it) }
            }
        }
    }

}
