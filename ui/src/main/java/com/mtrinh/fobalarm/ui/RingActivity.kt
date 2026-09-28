package com.mtrinh.fobalarm.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The full-screen ring UI. It turns the screen on by itself, from locked and from fully
 * asleep -- neither phone keeps its screen lit while idle, so it cannot inherit an
 * already-awake screen.
 */
open class RingActivity : ComponentActivity() {

    companion object {
        /** Set by the app module at startup so this class stays free of service deps. */
        @Volatile var content: (@Composable (RingActivity) -> Unit)? = null
        /** Told whether this screen is showing, so the ring notification can stay out of its way. */
        @Volatile var onVisible: ((Boolean) -> Unit)? = null
    }

    override fun onStart() { super.onStart(); onVisible?.invoke(true) }
    override fun onStop() { onVisible?.invoke(false); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // KEEP_SCREEN_ON applies ONLY during a ring session.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        // The box is the lock: back must never dismiss. The deprecated onBackPressed
        // override is not reliably invoked under predictive back at targetSdk 36.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* deliberately nothing */ }
        })

        setContent {
            FobTheme {
                val c = content
                // Never a black screen with no control: if the content was never wired,
                // the ring must still be stoppable from here.
                if (c != null) c(this) else FallbackDismiss {
                    // Dismiss the alarm, not just this window.
                    sendBroadcast(android.content.Intent("com.mtrinh.fobalarm.DISMISS")
                        .setClassName(packageName, "com.mtrinh.fobalarm.service.AlarmReceiver"))
                    finish()
                }
            }
        }
    }
}

/** Last-resort stop button, shown only if the ring UI was never supplied. */
@Composable
private fun FallbackDismiss(onStop: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Alarm ringing", fontSize = T.title)
        Spacer(Modifier.height(S.lg))
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth().height(160.dp)) {
            Text("PRESS TO DISMISS", fontSize = T.headline)
        }
    }
}
