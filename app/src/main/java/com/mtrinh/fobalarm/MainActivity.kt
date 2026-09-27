package com.mtrinh.fobalarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
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
import kotlinx.coroutines.launch
import com.mtrinh.fobalarm.core.*
import com.mtrinh.fobalarm.data.*
import com.mtrinh.fobalarm.service.*
import com.mtrinh.fobalarm.ui.*
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var app: AppState
    private val perms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { refreshGates() }

    private fun refreshGates() {
        GateEval.invalidateSlowChecks()
        GateEval.refresh(this, Svc.settings, Svc.lastNextFire != null)
        // Re-evaluating the gates is not enough: the screen renders the SNAPSHOT, which
        // only re-polls every 20s while idle. Pull a fresh one so a permission you just
        // granted turns green now instead of when you happen to navigate.
        if (::app.isInitialized) {
            lifecycleScope.launch {
                repeat(4) { kotlinx.coroutines.delay(300); app.refreshNow() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshGates()
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyRingtone(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Boot.ensure(this)
        requestRuntimePermissions()

        setContent {
            FobTheme {
                // KEEP_SCREEN_ON only while a ring is live -- never at idle.
                val ringing = runCatching { (::app.isInitialized) && app.snapshot?.ring != null }
                    .getOrDefault(false)
                LaunchedEffect(ringing) {
                    if (ringing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                var role by remember { mutableStateOf(Svc.settings.role) }
                Surface(Modifier.fillMaxSize()) {
                    when {
                        Crash.pending != null -> CrashScreen()
                        role == null -> RolePicker { chosen -> Svc.setRole(chosen); role = chosen }
                        // CONTROLLER INIT: its own thinner gates -- nearby-devices
                        // permission, credentials entered, AP reachable once. Without
                        // this a fresh controller can never join and would sit on
                        // "connecting" forever with nowhere to type the credentials.
                        role == Role.CONTROLLER && Svc.settings.passphrase.isNullOrBlank() ->
                            ControllerSetup { ssid, pass ->
                                runCatching {
                                    Svc.patchSettings(-1, Svc.settings.copy(ssid = ssid, passphrase = pass),
                                        java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.CONTROLLER)
                                }
                                P2pJoin.join(this@MainActivity, ssid, pass)
                                recreate()
                            }
                        else -> {
                            val client = remember(role) { buildClient(role!!) }
                            app = remember(client) {
                                AppState(
                                    client, lifecycleScope,
                                    // Controller keeps its own copy: the alarm phone is
                                    // the sole source of truth and it will eventually die.
                                    onExport = if (role == Role.CONTROLLER) ({ b ->
                                        runCatching {
                                            File(filesDir, "peer-export.json").writeText(
                                                com.mtrinh.fobalarm.data.Wire.backupToJson(b).toString())
                                        }
                                    }) else null,
                                ).also { it.startPolling() }
                            }
                            // A ring on the other phone must WAKE this one. Otherwise at
                            // 04:00 you walk into a dark, locked phone and the only
                            // control the product promises is two interactions away.
                            if (role == Role.CONTROLLER) {
                                LaunchedEffect(app.snapshot?.ring?.ringId) {
                                    if (app.snapshot?.ring != null) RemoteRingAlert.raise(this@MainActivity)
                                    else RemoteRingAlert.clear(this@MainActivity)
                                }
                            }
                            RootScreen(
                                app = app,
                                isAlarmRole = role == Role.ALARM,
                                deviceSettings = if (role == Role.ALARM) ({ DeviceSettings() }) else null,
                                onFixGate = { fix(it) },
                                onRepair = {
                                    runCatching {
                                        Svc.patchSettings(-1, Svc.settings.copy(passphrase = null),
                                            java.util.UUID.randomUUID().toString(),
                                            Svc.unlockToken, Actor.CONTROLLER)
                                    }
                                    recreate()
                                },
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

    /**
     * Runtime permissions are REQUESTED so Android shows its own dialog. Only the
     * special-access items (exact alarms, full-screen intent, hibernation, DND policy)
     * have no request API and genuinely require a Settings page -- and each of those
     * deep-links to the exact page, never to the generic app-info screen.
     */
    private fun fix(gate: String) {
        val ok = runCatching {
            when (gate) {
                // --- real runtime permissions: Android prompts ---
                "foregroundService" -> {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        perms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else openNotificationSettings()
                }
                "localNetworkPermission" -> perms.launch(arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION))

                // --- special access: no request API exists, so deep-link precisely ---
                "exactAlarm" -> startActivity(Intent(
                    ASettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName")))
                "fullScreenIntent" -> startActivity(Intent(
                    ASettings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:$packageName")))
                "notificationPolicyAccess" -> startActivity(
                    Intent(ASettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                "notHibernating" -> startActivity(
                    IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName))
                // --- device settings we cannot change for you ---
                "dndAllowsAlarms" -> startActivity(Intent("android.settings.ZEN_MODE_SETTINGS"))
                "volumeNotFixed", "vibrationEnabled" -> startActivity(Intent(ASettings.ACTION_SOUND_SETTINGS))
                "noBluetoothAudio" -> startActivity(Intent(ASettings.ACTION_BLUETOOTH_SETTINGS))

                "audioPlayable" -> pickAudio.launch(arrayOf("audio/*"))
                else -> return
            }
            true
        }.getOrDefault(false)

        // A deep link can be unsupported on a given OEM build. Say so instead of
        // silently doing nothing or dumping the user on a generic page.
        if (!ok) {
            Toast.makeText(this,
                "This phone has no direct page for that — open Settings and search for it",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(ASettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(ASettings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** Copied at PICK time: no READ_MEDIA at 04:00, no SAF grant to lose, no file that can vanish. */
    private fun copyRingtone(uri: Uri) {
        // Patch FIRST. ringtoneUri is a gated setting, and writing the bytes before the
        // gate ran meant a near-silent file went live regardless of the answer.
        val allowed = runCatching {
            Svc.patchSettings(-1, Svc.settings.copy(ringtoneUri = uri.toString()),
                java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.ALARM)
        }
        if (allowed.isFailure) {
            Svc.log("ringtone_rejected", "error" to allowed.exceptionOrNull().toString())
            Toast.makeText(this, "Unlock settings first to change the ringtone",
                Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                File(filesDir, "ringtone.bin").outputStream().use { input.copyTo(it) }
            }
        }.onFailure {
            Svc.log("ringtone_copy_failed", "error" to it.toString())
            Toast.makeText(this, "Could not read that file", Toast.LENGTH_LONG).show()
        }
    }

    // ---- role-conditional UI ------------------------------------------------

    @Composable
    private fun DeviceSettings() {
        var ssid by remember { mutableStateOf(Svc.settings.ssid ?: "DIRECT-fa-alarm") }
        var pass by remember { mutableStateOf(Svc.settings.passphrase ?: "") }
        var confirm by remember { mutableStateOf(false) }
        var pw by remember { mutableStateOf("") }
        var recovery by remember { mutableStateOf<String?>(null) }
        var removing by remember { mutableStateOf(false) }

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
        val hasPw = Svc.settings.passwordHash != null
        Text(
            if (hasPw) "Set. Gates the settings that can silence tomorrow. Never gates dismiss."
            else "Not set — every setting is editable by anyone holding this phone.",
            fontSize = 11.sp, color = if (hasPw) Good else Muted)

        var current by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        if (hasPw) {
            OutlinedTextField(current, { current = it; err = null },
                label = { Text("Current password or recovery code", fontSize = 12.sp) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(pw, { pw = it; err = null },
            label = { Text(if (hasPw) "New password" else "Set a password", fontSize = 12.sp) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        err?.let { Text(it, color = Bad, fontSize = 12.sp) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    runCatching { Svc.setPassword(pw, current.ifBlank { null }) }
                        .onSuccess { recovery = it.second; pw = ""; current = ""; err = null }
                        .onFailure { err = "Current password is wrong" }
                },
                enabled = pw.length >= 4 && (!hasPw || current.isNotBlank())
            ) { Text(if (hasPw) "Change" else "Set password", fontSize = 12.sp) }

            if (hasPw) {
                OutlinedButton(onClick = { removing = true }) { Text("Remove", fontSize = 12.sp) }
            }
        }

        if (removing) {
            AlertDialog(
                onDismissRequest = { removing = false },
                title = { Text("Remove the password?") },
                text = {
                    Text("Every setting that can silence tomorrow — the alarm time, the " +
                         "volume, the ringtone, the snooze length — becomes editable by " +
                         "anyone holding this phone, including you at 4 AM.", fontSize = 13.sp)
                },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { Svc.setPassword(null, current.ifBlank { null }) }
                            .onSuccess { recovery = null; current = ""; err = null }
                            .onFailure { err = "Current password is wrong" }
                        removing = false
                    }, enabled = current.isNotBlank()) { Text("Remove") }
                },
                dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep it") } })
        }

        recovery?.let { code ->
            AlertDialog(
                onDismissRequest = { },     // must not be dismissible by a stray tap
                title = { Text("Write this down now") },
                text = {
                    Column {
                        Text("Recovery code — shown exactly once:", fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(code, fontSize = 22.sp, fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        Text("Put it on the printed runbook with the spare key to the box. " +
                             "It is the only way back in if you forget the password — " +
                             "without it, recovery means a factory reset and losing " +
                             "every setting, the pairing and the history.",
                            fontSize = 11.sp, color = Muted)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { recovery = null }) { Text("I wrote it down") }
                })
        }

        Spacer(Modifier.height(8.dp))
        RoleSwitcher()

        if (BuildConfig.DEV_CHANNEL) {
            Spacer(Modifier.height(8.dp))
            DevChannel()
        }
    }

    /**
     * Flipping the alarm phone to CONTROLLER is a one-tap total silencer where every log
     * entry looks legal. So: typed confirmation rather than a toggle, rejected outright
     * while a session is open, clears the pairing, and writes an event.
     */
    @Composable
    private fun RoleSwitcher() {
        var asking by remember { mutableStateOf(false) }
        var typed by remember { mutableStateOf("") }
        var rolePw by remember { mutableStateOf("") }
        var roleErr by remember { mutableStateOf<String?>(null) }
        Text("Role", fontSize = 12.sp, color = Muted)
        Text("currently ${Svc.settings.role?.name ?: "unset"}", fontSize = 12.sp)
        OutlinedButton(onClick = { asking = true }) { Text("Change role", fontSize = 12.sp) }
        if (asking) {
            val target = if (Svc.settings.role == Role.ALARM) Role.CONTROLLER else Role.ALARM
            AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text("Change role to ${target.name}?") },
                text = {
                    Column {
                        Text("This clears the pairing. Switching the alarm phone to " +
                             "CONTROLLER stops it ringing entirely.", fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Type ${target.name} to confirm:", fontSize = 12.sp, color = Muted)
                        OutlinedTextField(typed, { typed = it }, singleLine = true)
                        if (Svc.settings.passwordHash != null) {
                            OutlinedTextField(rolePw, { rolePw = it; roleErr = null },
                                label = { Text("Password", fontSize = 12.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true)
                        }
                        roleErr?.let { Text(it, color = Bad, fontSize = 12.sp) }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = typed.trim().uppercase() == target.name && Svc.session == null &&
                                (Svc.settings.passwordHash == null || rolePw.isNotBlank()),
                        onClick = {
                            if (Svc.settings.passwordHash != null &&
                                !com.mtrinh.fobalarm.core.Auth.accepts(Svc.settings, rolePw)) {
                                roleErr = "Wrong password"
                                return@TextButton
                            }
                            P2pJoin.stop()
                            Group.stop(this@MainActivity)
                            Svc.setRole(target)
                            asking = false; typed = ""; rolePw = ""
                            recreate()
                        }) { Text("Change") }
                },
                dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } })
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
        var askPw by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { askPw = true }) { Text("Enable 60m", fontSize = 12.sp) }
            OutlinedButton(onClick = { Updater.disableDevMode() }) { Text("Off", fontSize = 12.sp) }
        }
        if (askPw) {
            var secret by remember { mutableStateOf("") }
            var bad by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { askPw = false },
                title = { Text("Open the dev channel?") },
                text = {
                    Column {
                        Text("The phone will listen for builds for 60 minutes.",
                            fontSize = 12.sp)
                        OutlinedTextField(secret, { secret = it; bad = false },
                            label = { Text("Password", fontSize = 12.sp) },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        if (bad) Text("Rejected", color = Bad, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (Updater.enableDevMode(secret)) {
                            Updater.discover(this@MainActivity); askPw = false
                        } else bad = true
                    }) { Text("Enable") }
                },
                dismissButton = { TextButton(onClick = { askPw = false }) { Text("Cancel") } })
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

    /** Controller-side pairing. Must match the alarm phone's group byte for byte. */
    @Composable
    private fun ControllerSetup(onSet: (String, String) -> Unit) {
        var ssid by remember { mutableStateOf("DIRECT-fa-alarm") }
        var pass by remember { mutableStateOf("") }
        val hasPerm = checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pair with the alarm phone", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Enter exactly the group name and passphrase set on the alarm phone.",
                fontSize = 12.sp, color = Muted)
            Fact("nearby devices", if (hasPerm) "granted" else "REQUIRED", hasPerm)
            if (!hasPerm) {
                Button(onClick = { requestRuntimePermissions() }) { Text("Grant") }
            }
            OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onSet(ssid.trim(), pass) },
                enabled = hasPerm && pass.length in 8..63 && ssid.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Join group") }
            Text("Status: ${P2pJoin.status}", fontSize = 11.sp, color = Muted)
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = { Svc.setRole(Role.ALARM); recreate() }) {
                Text("This is actually the alarm phone", fontSize = 12.sp)
            }
        }
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
