package com.mtrinh.fobalarm

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    @Composable
    private fun Screen() {
        var facts by remember { mutableStateOf(Probe.collect(this)) }
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) { kotlinx.coroutines.delay(1000); tick++ }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Fob Alarm — probe", fontSize = 22.sp, fontWeight = FontWeight.Bold)

            key(tick) {
                val next = DeState.nextFireAtMs
                val fmt = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())
                Text("next fire: ${if (next > 0) fmt.format(Date(next)) else "—"}   " +
                        "(in ${((next - System.currentTimeMillis()) / 1000).coerceAtLeast(0)}s)")
                Text("ringing: ${RingService.running}   ${RingService.lastAudible}")
                Text("logs: http://<this-phone>:${LogServer.PORT}/v1/logs  [${LogServer.status}]",
                    fontSize = 12.sp, color = Color.Gray)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Alarm.scheduleTestFire(this@MainActivity, 60) }) { Text("Ring in 60s") }
                Button(onClick = { Alarm.scheduleTestFire(this@MainActivity, 10) }) { Text("10s") }
                OutlinedButton(onClick = { facts = Probe.collect(this@MainActivity) }) { Text("Refresh") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")))
                }) { Text("App settings") }
                OutlinedButton(onClick = {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }) { Text("Battery") }
            }

            HorizontalDivider()
            Text("device facts", fontWeight = FontWeight.Bold)

            facts.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        when (f.verdict) { Verdict.GOOD -> "OK  "; Verdict.BAD -> "BAD "; Verdict.UNKNOWN -> "--  " },
                        color = when (f.verdict) {
                            Verdict.GOOD -> Color(0xFF7BE07B)
                            Verdict.BAD -> Color(0xFFFF6B6B)
                            Verdict.UNKNOWN -> Color.Gray
                        },
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp
                    )
                    Column {
                        Text(f.key, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(f.value, fontSize = 12.sp, color = Color.Gray,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }

            HorizontalDivider()
            Text("recent events", fontWeight = FontWeight.Bold)
            key(tick) {
                Log.recent(25).reversed().forEach { o ->
                    Text("${o.optLong("seq")}  ${o.optString("event")}  " +
                            o.keys().asSequence().filter { it !in setOf("seq","wallMs","bootNanos","event") }
                                .joinToString(" ") { "$it=${o.opt(it)}" },
                        fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}
