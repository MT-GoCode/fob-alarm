package com.mtrinh.fobalarm.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The full-screen ring UI. It must turn the screen on BY ITSELF, from locked and from
 * fully asleep -- neither phone keeps its screen lit while idle, so it can no longer
 * inherit an already-awake screen. SPEC.md section 6.
 *
 * Hosted here so :ui owns every screen; the app module supplies the content.
 */
open class RingActivity : ComponentActivity() {

    companion object {
        /** Set by the app module at startup so this class stays free of service deps. */
        @Volatile var content: (@androidx.compose.runtime.Composable () -> Unit)? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // KEEP_SCREEN_ON applies ONLY during a ring session.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        setContent { FobTheme { content?.invoke() } }
    }

    /** The box is the lock: back must never dismiss. */
    @Deprecated("Deliberate: back must not dismiss the alarm")
    override fun onBackPressed() { /* no-op */ }
}
