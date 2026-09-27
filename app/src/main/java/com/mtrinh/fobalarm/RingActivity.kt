package com.mtrinh.fobalarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.*

class RingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = 1f }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var tick by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); tick++ } }

                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        key(tick) {
                            Text(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                                fontSize = 64.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(RingService.lastAudible, fontSize = 12.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.height(48.dp))
                        Button(
                            onClick = { dismiss() },
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        ) { Text("PRESS TO DISMISS", fontSize = 22.sp) }
                    }
                }
            }
        }
    }

    private fun dismiss() {
        Log.e("dismiss", "actor" to "LOCAL")
        // Ask the service to stop; it owns the audio and the session.
        startService(Intent(this, RingService::class.java).setAction("STOP"))
        stopService(Intent(this, RingService::class.java))
        DeState.closeSession()
        Alarm.cancelWatchdog(this)
        Alarm.recompute(this, "dismiss_local")
        finish()
    }

    override fun onBackPressed() { /* the box is the lock; back must not dismiss */ }
}
