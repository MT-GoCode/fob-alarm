package com.mtrinh.fobalarm

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.WindowManager
import android.provider.Settings as ASettings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
        // The controller renders the ALARM phone's snapshot, so its own permission
        // state has to be supplied separately or its rows describe the wrong device.
        if (::app.isInitialized) {
            lifecycleScope.launch {
                // The evaluation runs on a worker; poll briefly for the fresh result
                // rather than reading the cache we just invalidated.
                repeat(8) {
                    kotlinx.coroutines.delay(250)
                    app.localGates = GateEval.current(
                        this@MainActivity, Svc.settings, Svc.lastNextFire != null)
                }
            }
        }
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
        // Draw edge to edge; Page() then insets every screen once, centrally.
        enableEdgeToEdge()
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
                if (hibernationHelp) {
                    AlertDialog(
                        onDismissRequest = { hibernationHelp = false },
                        title = { Text("Find this toggle") },
                        text = {
                            Column {
                                Text("On the page that just opened, scroll to:", fontSize = T.label)
                                Spacer(Modifier.height(S.sm))
                                Text("\"Pause app activity if unused\"", fontSize = T.body,
                                    fontWeight = FontWeight.Bold)
                                Text("(some phones call it \"Remove permissions if app is " +
                                     "unused\" or \"Manage app if unused\")",
                                    fontSize = T.caption, color = Muted)
                                Spacer(Modifier.height(S.sm))
                                Text("Turn it OFF, then come back here.", fontSize = T.label)
                                Spacer(Modifier.height(S.sm))
                                Text("Why: Android force-stops apps you have not opened in " +
                                     "a few months. This phone lives in a box and is never " +
                                     "opened, so without this the alarm eventually stops " +
                                     "firing with no warning.",
                                    fontSize = T.caption, color = Muted)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { hibernationHelp = false }) { Text("Got it") }
                        })
                }
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
                                    Svc.patchSettings(-1,
                                        Svc.settings.copy(ssid = ssid, passphrase = pass),
                                        java.util.UUID.randomUUID().toString(),
                                        Svc.unlockToken, Actor.CONTROLLER)
                                }.onSuccess {
                                    P2pJoin.join(this@MainActivity, ssid, pass)
                                    recreate()
                                }.onFailure {
                                    Toast.makeText(this@MainActivity,
                                        "Could not save: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        else -> {
                            val client = remember(role) { buildClient(role!!) }
                            app = remember(client) {
                                AppState(
                                    client, lifecycleScope,
                                    isLocal = role == Role.ALARM,
                                    // Controller keeps its own copy: the alarm phone is
                                    // the sole source of truth and it will eventually die.
                                    onExport = if (role == Role.CONTROLLER) ({ b ->
                                        runCatching {
                                            File(filesDir, "peer-export.json").writeText(
                                                com.mtrinh.fobalarm.data.Wire.backupToJson(b).toString())
                                        }
                                    }) else null,
                                ).also {
                                    it.startPolling()
                                    if (role == Role.ALARM) it.onStopTest = { Svc.stopTest() }
                                    it.localGates = GateEval.current(
                                        this@MainActivity, Svc.settings, Svc.lastNextFire != null)
                                }
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
                                    }.onFailure {
                                        Toast.makeText(this@MainActivity,
                                            "Could not reset: ${it.message}", Toast.LENGTH_LONG).show()
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
            // Report our own failing gates so the alarm phone can show them.
            localBlockers = {
                GateEval.current(this, Svc.settings, Svc.lastNextFire != null)
                    .failing().filter { com.mtrinh.fobalarm.core.GateInfo.of(it)?.blocking == true }
            },
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
                // Android has no dedicated page for this on most builds -- the intent
                // lands on App info, where the toggle is several scrolls down under a
                // name that varies by OEM. Say exactly what to look for.
                "notHibernating" -> {
                    hibernationHelp = true
                    startActivity(IntentCompat.createManageUnusedAppRestrictionsIntent(
                        this, packageName))
                }
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

    /** Shown on return, because the destination page does not explain itself. */
    private var hibernationHelp by mutableStateOf(false)

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
        // Driven by observable snapshot state, not by Svc globals: reading those meant
        // the subtree did not recompose after an unlock or a ringtone change.
        val snap = if (::app.isInitialized) app.snapshot else null
        val locked = (snap?.settings?.hasPassword ?: true) && app.token == null
        var ssid by remember(snap?.settings?.ssid) {
            mutableStateOf(snap?.settings?.ssid ?: "DIRECT-fa-alarm")
        }
        var pass by remember(snap?.settings?.passphrase) {
            mutableStateOf(snap?.settings?.passphrase ?: "")
        }
        var confirm by remember { mutableStateOf(false) }
        var pw by remember { mutableStateOf("") }

        Section("Pairing", "Both phones must use the same name and passphrase.")
        Spacer(Modifier.height(S.sm))
        OutlinedTextField(ssid, { ssid = it }, label = { Text("Name (starts DIRECT-)", fontSize = T.caption) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(S.sm))
        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (8-63)", fontSize = T.caption) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(S.sm))
        Button(onClick = { confirm = true },
            enabled = !locked && pass.length in 8..63 && ssid.isNotBlank()) {
            Text(if (locked) "Unlock to change" else "Apply credentials")
        }

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

        Spacer(Modifier.height(S.sm))
        Section("Ringtone", "A built-in tone is used unless you choose a file.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (snap?.settings?.ringtoneUri != null) "Your file" else "Built-in tone",
                fontSize = T.body, modifier = Modifier.weight(1f))
            TextButton(enabled = !locked,
                onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text("Choose") }
            if (snap?.settings?.ringtoneUri != null) {
                TextButton(
                    enabled = !locked,
                    onClick = {
                        runCatching {
                            Svc.patchSettings(-1, Svc.settings.copy(ringtoneUri = null),
                                java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.ALARM)
                        }.onFailure {
                            Toast.makeText(this@MainActivity, "Unlock settings first",
                                Toast.LENGTH_SHORT).show()
                        }
                        refreshGates()
                    }) { Text("Use built-in") }
            }
        }

        Section("Password", "Optional. Locks the settings that could stop the alarm.")
        var current by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        val hasPw = Svc.settings.hasPassword

        if (hasPw) {
            OutlinedTextField(current, { current = it; err = null },
                label = { Text("Current password", fontSize = T.caption) },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(S.sm))
        }
        OutlinedTextField(pw, { pw = it; err = null },
            label = { Text(if (hasPw) "New password" else "Set a password", fontSize = T.caption) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            modifier = Modifier.fillMaxWidth())
        err?.let { Text(it, color = Bad, fontSize = T.caption) }
        Spacer(Modifier.height(S.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    runCatching { Svc.setPassword(pw, current.ifBlank { null }) }
                        .onSuccess { pw = ""; current = ""; err = null }
                        .onFailure { err = "Current password is wrong" }
                },
                enabled = pw.length >= 4 && (!hasPw || current.isNotBlank())
            ) { Text(if (hasPw) "Change" else "Set", fontSize = T.label) }
            if (hasPw) {
                OutlinedButton(
                    onClick = {
                        runCatching { Svc.setPassword(null, current.ifBlank { null }) }
                            .onSuccess { current = ""; err = null }
                            .onFailure { err = "Current password is wrong" }
                    },
                    enabled = current.isNotBlank()
                ) { Text("Remove", fontSize = T.label) }
            }
        }

        Spacer(Modifier.height(S.sm))
        RoleSwitcher()

        if (BuildConfig.DEV_CHANNEL) {
            Spacer(Modifier.height(S.sm))
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
        Section("Role")
        Text("currently ${Svc.settings.role?.name ?: "unset"}", fontSize = T.caption)
        OutlinedButton(onClick = { asking = true }) { Text("Change role", fontSize = T.caption) }
        if (asking) {
            val target = if (Svc.settings.role == Role.ALARM) Role.CONTROLLER else Role.ALARM
            AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text("Change role to ${target.name}?") },
                text = {
                    Column {
                        Text("This clears the pairing. Switching the alarm phone to " +
                             "CONTROLLER stops it ringing entirely.", fontSize = T.caption)
                        Spacer(Modifier.height(S.sm))
                        Text("Type ${target.name} to confirm:", fontSize = T.caption, color = Muted)
                        OutlinedTextField(typed, { typed = it }, singleLine = true)
                        if (Svc.settings.passwordHash != null) {
                            OutlinedTextField(rolePw, { rolePw = it; roleErr = null },
                                label = { Text("Password", fontSize = T.caption) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true)
                        }
                        roleErr?.let { Text(it, color = Bad, fontSize = T.caption) }
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
        Section("Dev channel", "Lets this phone fetch new builds from the Mac.")
        Text(if (Updater.devModeOn)
                "On, closes in " + Fmt.duration(Updater.devModeUntilMs - System.currentTimeMillis())
             else "Off", fontSize = T.label, color = if (Updater.devModeOn) Good else Muted)
        var askPw by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { askPw = true }) { Text("Enable 60m", fontSize = T.caption) }
            OutlinedButton(onClick = { Updater.disableDevMode() }) { Text("Off", fontSize = T.caption) }
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
                            fontSize = T.caption)
                        OutlinedTextField(secret, { secret = it; bad = false },
                            label = { Text("Password", fontSize = T.caption) },
                            visualTransformation = PasswordVisualTransformation(), singleLine = true)
                        if (bad) Text("Rejected", color = Bad, fontSize = T.caption)
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
            label = { Text("Mac IP (primary)", fontSize = T.caption) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { Updater.check(this@MainActivity) { msg = it } },
            enabled = Updater.devModeOn) { Text("Check for update", fontSize = T.caption) }
        Text(msg, fontSize = T.caption, color = Muted)
        Text("Last checked ${Fmt.age(Updater.lastCheckAtMs)}", fontSize = T.caption, color = Muted)
    }

    /** Controller-side pairing. Must match the alarm phone's group byte for byte. */
    @Composable
    private fun ControllerSetup(onSet: (String, String) -> Unit) {
        var ssid by remember { mutableStateOf("DIRECT-fa-alarm") }
        var pass by remember { mutableStateOf("") }
        val hasPerm = checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        Page(
            title = "Pair with the alarm phone",
            subtitle = "Enter the same group name and passphrase set on the alarm phone.",
            applyInsets = true,
        ) {
            Spacer(Modifier.height(S.sm))
            Fact("nearby devices", if (hasPerm) "granted" else "required", hasPerm)
            if (!hasPerm) {
                Spacer(Modifier.height(S.xs))
                Button(onClick = { requestRuntimePermissions() }) { Text("Grant") }
            }
            Spacer(Modifier.height(S.sm))
            OutlinedTextField(ssid, { ssid = it }, label = { Text("SSID", fontSize = T.caption) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase", fontSize = T.caption) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(S.sm))
            Button(onClick = { onSet(ssid.trim(), pass) },
                enabled = hasPerm && pass.length in 8..63 && ssid.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Join group") }
            Fact("status", P2pJoin.status)
            Spacer(Modifier.height(S.md))
            OutlinedButton(onClick = { Svc.setRole(Role.ALARM); recreate() }) {
                Text("This is actually the alarm phone", fontSize = T.caption)
            }
        }
    }

    @Composable
    private fun RolePicker(onPick: (Role) -> Unit) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Which phone is this?", fontSize = T.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(S.lg))
            Button(onClick = { onPick(Role.ALARM) }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("ALARM — lives in the box", fontSize = T.button)
            }
            Spacer(Modifier.height(S.sm))
            Button(onClick = { onPick(Role.CONTROLLER) }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("CONTROLLER — the other room", fontSize = T.button)
            }
            Spacer(Modifier.height(S.md))
            Spacer(Modifier.height(S.sm))
            Text("${BuildConfig.VARIANT.lowercase()} ${BuildConfig.VERSION_NAME}",
                fontSize = T.caption, color = Muted,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }

    @Composable
    private fun CrashScreen() {
        Page(title = "The app crashed", applyInsets = true) {
            Text("${BuildConfig.VARIANT.lowercase()} ${BuildConfig.VERSION_NAME}",
                fontSize = T.caption, color = Muted)
            Spacer(Modifier.height(S.sm))
            Text(Crash.pending ?: "", fontSize = T.caption, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(S.md))
            Button(onClick = { Crash.clear(this@MainActivity); recreate() }) { Text("Dismiss") }
        }
    }
}
