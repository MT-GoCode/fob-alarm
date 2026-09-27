package com.mtrinh.fobalarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as ASettings
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.data.*
import com.mtrinh.fobalarm.service.*
import com.mtrinh.fobalarm.ui.*
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var app: AppState
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyRingtone(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Boot.ensure(this)
        requestRuntimePermissions()

        setContent {
            FobTheme {
                var role by remember { mutableStateOf(Svc.settings.role) }
                Surface(Modifier.fillMaxSize()) {
                    when {
                        Crash.pending != null -> CrashScreen()
                        role == null -> RolePicker { chosen -> Svc.setRole(chosen); role = chosen }
                        else -> {
                            val client = remember(role) { buildClient(role!!) }
                            app = remember(client) { AppState(client, lifecycleScope).also { it.startPolling() } }
                            RootScreen(
                                app = app,
                                isAlarmRole = role == Role.ALARM,
                                instrument = if (role == Role.ALARM) ({ Instrument() }) else null,
                                deviceSettings = if (role == Role.ALARM) ({ DeviceSettings() }) else null,
                                onFixGate = { fix(it) },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Role selects a CLIENT, not a screen. Everything visual downstream is shared. */
    private fun buildClient(role: Role): StateClient = when (role) {
        Role.ALARM -> LocalStateClient(Svc)
        Role.CONTROLLER -> HttpStateClient(
            hostProvider = { Group.ownerAddress ?: "192.168.49.1" },
            networkProvider = { P2pJoin.network },
            selfProvider = { Svc.selfDevice() },
        )
    }

    private fun requestRuntimePermissions() {
        val want = mutableListOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        runCatching { want.add("android.permission.ACCESS_LOCAL_NETWORK") }
        perms.launch(want.toTypedArray())
    }

    /** Each INIT row deep-links to the page that actually fixes it. */
    private fun fix(gate: String) {
        runCatching {
            when (gate) {
                "notHibernating" -> startActivityForResult(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName), 99)
                "exactAlarm" -> startActivity(Intent(ASettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                "fullScreenIntent" -> startActivity(Intent(ASettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
                "notificationPolicyAccess" -> startActivity(Intent(ASettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "dndAllowsAlarms" -> startActivity(Intent(ASettings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
                "volumeNotFixed", "vibrationEnabled" -> startActivity(Intent(ASettings.ACTION_SOUND_SETTINGS))
                "powerOk" -> startActivity(Intent(ASettings.ACTION_BATTERY_SAVER_SETTINGS))
                "audioPlayable" -> pickAudio.launch(arrayOf("audio/*"))
                "localNetworkPermission" -> requestRuntimePermissions()
                "groupCredentialsSet" -> { /* handled inline in DeviceSettings */ }
                else -> startActivity(Intent(ASettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }
    }

    /** Copied at PICK time: no READ_MEDIA at 04:00, no SAF grant to lose, no file that can vanish. */
    private fun copyRingtone(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                File(filesDir, "ringtone.bin").outputStream().use { input.copyTo(it) }
            }
            Svc.patchSettings(-1, Svc.settings.copy(ringtoneUri = uri.toString()),
                java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.ALARM)
        }.onFailure { Svc.log("ringtone_copy_failed", "error" to it.toString()) }
    }

    // ---- role-conditional UI ------------------------------------------------

    @Composable
    private fun Instrument() {
        var deg by remember { mutableDoubleStateOf(0.0) }
        var stale by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            while (true) {
                deg = RingService.rotationDeg
                stale = RingService.rvStale
                kotlinx.coroutines.delay(60)
            }
        }
        RotationInstrument(deg, Svc.settings.snoozeThresholdDegrees, null, stale, Modifier.fillMaxSize())
    }

    @Composable
    private fun DeviceSettings() {
        var ssid by remember { mutableStateOf(Svc.settings.ssid ?: "DIRECT-fa-alarm") }
        var pass by remember { mutableStateOf(Svc.settings.passphrase ?: "") }
        var confirm by remember { mutableStateOf(false) }
        var pw by remember { mutableStateOf("") }
        var recovery by remember { mutableStateOf<String?>(null) }

        Text("Group credentials", fontSize = 12.sp, color = Muted)
        OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID (DIRECT-xy…)", fontSize = 12.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (8–63)", fontSize = 12.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { confirm = true },
            enabled = pass.length in 8..63 && ssid.isNotBlank()) { Text("Apply credentials") }

        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Change group credentials?") },
                text = { Text("This will disconnect the controller. You must re-enter the same " +
                        "values there before the two phones can talk again.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        runCatching {
                            Svc.patchSettings(-1, Svc.settings.copy(ssid = ssid, passphrase = pass),
                                java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.ALARM)
                        }
                    }) { Text("Change") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
        }

        Spacer(Modifier.height(8.dp))
        Text("Ringtone", fontSize = 12.sp, color = Muted)
        OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) {
            Text(if (Svc.settings.ringtoneUri != null) "Change audio file" else "Pick audio file", fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))
        Text("Password", fontSize = 12.sp, color = Muted)
        Text("Optional. Gates the settings that can silence tomorrow. Never gates dismiss.",
            fontSize = 11.sp, color = Muted)
        OutlinedTextField(pw, { pw = it }, label = { Text("New password", fontSize = 12.sp) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { recovery = Svc.setPassword(pw).second; pw = "" }, enabled = pw.length >= 4) {
                Text("Set password", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { Svc.setPassword(null); recovery = null }) {
                Text("Remove", fontSize = 12.sp)
            }
        }
        recovery?.let { code ->
            AlertDialog(
                onDismissRequest = { recovery = null },
                title = { Text("Write this down now") },
                text = {
                    Column {
                        Text("Recovery code — shown exactly once:", fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(code, fontSize = 20.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("Put it on the printed runbook with the spare key to the box. " +
                             "Without it, a forgotten password means a factory reset.",
                            fontSize = 11.sp, color = Muted)
                    }
                },
                confirmButton = { TextButton(onClick = { recovery = null }) { Text("I wrote it down") } })
        }

        if (BuildConfig.DEV_CHANNEL) {
            Spacer(Modifier.height(8.dp))
            DevChannel()
        }
    }

    @Composable
    private fun DevChannel() {
        var ip by remember { mutableStateOf(Updater.host ?: "") }
        var msg by remember { mutableStateOf(Updater.status) }
        Text("Dev channel", fontSize = 12.sp, color = Muted)
        Text(if (Updater.devModeOn) "ON — expires in " +
                Fmt.duration(Updater.devModeUntilMs - System.currentTimeMillis())
             else "OFF — the phone does not listen for builds", fontSize = 11.sp,
            color = if (Updater.devModeOn) Good else Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Updater.enableDevMode(); Updater.discover(this@MainActivity) }) {
                Text("Enable 60m", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { Updater.disableDevMode() }) { Text("Off", fontSize = 12.sp) }
        }
        OutlinedTextField(ip, { ip = it; Updater.useHost(it) },
            label = { Text("Mac IP (primary)", fontSize = 12.sp) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { Updater.check(this@MainActivity) { msg = it } },
            enabled = Updater.devModeOn) { Text("Check for update", fontSize = 12.sp) }
        Text(msg, fontSize = 11.sp, color = Muted)
        Text("last check ${Fmt.age(Updater.lastCheckAtMs)} · remote vc ${Updater.remoteVersionCode}",
            fontSize = 10.sp, color = Muted)
        Text("logs: http://<this phone>:8766/v1/logs  [${Server.logStatus}]", fontSize = 10.sp, color = Muted)
    }

    @Composable
    private fun RolePicker(onPick: (Role) -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Which phone is this?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { onPick(Role.ALARM) }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("ALARM — lives in the box", fontSize = 16.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onPick(Role.CONTROLLER) }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("CONTROLLER — the other room", fontSize = 16.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("Changeable later in settings, behind the password.",
                fontSize = 12.sp, color = Muted)
        }
    }

    @Composable
    private fun CrashScreen() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("The app crashed", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Bad)
            Text("There is no logcat on this phone, so the trace is kept here.",
                fontSize = 12.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Text(Crash.pending ?: "", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { Crash.clear(this@MainActivity); recreate() }) { Text("Dismiss") }
        }
    }
}
