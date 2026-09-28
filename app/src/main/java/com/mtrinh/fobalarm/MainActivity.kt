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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    /** Bumped whenever permissions may have changed, so screens without an AppState still react. */
    private var permTick by mutableIntStateOf(0)

    private fun refreshGates() {
        permTick++
        LinkService.nudge()          // a grant may be what the group or the join was waiting for
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
        if (::app.isInitialized) app.reload()
    }
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { copyRingtone(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw edge to edge; Page() then insets every screen once, centrally.
        enableEdgeToEdge()
        Boot.ensure(this)

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
                        role == null -> RolePicker { chosen ->
                            Svc.setRole(chosen); role = chosen; refreshGates()
                            // The group cannot be created without it, and nobody reads the
                            // Recommended row before bedtime.
                            if (chosen == Role.ALARM) perms.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES,
                                Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                        // CONTROLLER INIT: its own thinner gates -- nearby-devices
                        // permission, credentials entered, AP reachable once. Without
                        // this a fresh controller can never join and would sit on
                        // "connecting" forever with nowhere to type the credentials.
                        // Also while Nearby devices is missing: the join needs it, and the
                        // pairing screen is where it is asked for.
                        role == Role.CONTROLLER && (Svc.settings.passphrase.isNullOrBlank() ||
                            (permTick >= 0 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED)) ->
                            ControllerSetup { ssid, pass ->
                                runCatching {
                                    Svc.patchSettings(-1,
                                        Svc.settings.copy(ssid = ssid, passphrase = pass),
                                        java.util.UUID.randomUUID().toString(),
                                        Svc.unlockToken, Actor.CONTROLLER)
                                }.onSuccess {
                                    P2pJoin.stop()      // drop any request for the old credentials
                                    recreate()          // the link service issues the new one
                                }.onFailure {
                                    Toast.makeText(this@MainActivity, "Not saved. Try again.", Toast.LENGTH_LONG).show()
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
                                    it.localGates = GateEval.current(
                                        this@MainActivity, Svc.settings, Svc.lastNextFire != null)
                                }
                            }
                            RootScreen(
                                app = app,
                                isAlarmRole = role == Role.ALARM,
                                pairing = { Pairing(role!!) },
                                deviceSettings = if (role == Role.ALARM) ({ DeviceSettings() }) else null,
                                roleSwitcher = { RoleSwitcher() },
                                onFixGate = { fix(it) },
                                onRepair = {
                                    runCatching {
                                        Svc.patchSettings(-1, Svc.settings.copy(passphrase = null),
                                            java.util.UUID.randomUUID().toString(),
                                            Svc.unlockToken, Actor.CONTROLLER)
                                    }.onSuccess { P2pJoin.stop(); recreate() }.onFailure {
                                        Toast.makeText(this@MainActivity, "Not saved. Try again.", Toast.LENGTH_LONG).show()
                                    }
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
            hostProvider = { P2pJoin.ownerAddress },
            selfProvider = { Svc.selfDevice() },
            // Report our own failing gates so the alarm phone can show them.
            localBlockers = {
                GateEval.current(this, Svc.settings, Svc.lastNextFire != null).missingFor(alarmRole = false)
            },
        )
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
                "This phone has no direct page for that. Open Settings and search for it.",
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

    /**
     * Pairing. On the alarm phone: a name and passphrase to invent, shown large once set,
     * with the instruction to type them on the other phone. On the controller: what it is
     * paired to, and a way to pair again. Same words on both phones.
     */
    @Composable
    private fun Pairing(role: Role) {
        val snap = if (::app.isInitialized) app.snapshot else null
        val set = !snap?.settings?.passphrase.isNullOrBlank()
        if (role == Role.CONTROLLER) {
            Section("Pairing")
            Text(if (set) "Paired to ${snap?.settings?.ssid ?: ""}" else "Not paired",
                fontSize = T.body, color = if (set) Good else Bad)
            Spacer(Modifier.height(S.sm))
            OutlinedButton(onClick = {
                runCatching {
                    Svc.patchSettings(-1, Svc.settings.copy(passphrase = null),
                        java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.CONTROLLER)
                }.onSuccess { P2pJoin.stop(); recreate() }.onFailure {
                    Toast.makeText(this@MainActivity, "Not saved. Try again.", Toast.LENGTH_LONG).show()
                }
            }) { Text("Pair again") }
            return
        }

        val locked = (snap?.settings?.hasPassword ?: true) && !app.unlocked
        var name by remember(snap?.settings?.ssid) { mutableStateOf(snap?.settings?.ssid ?: Settings.DEFAULT_SSID) }
        var pass by remember(snap?.settings?.passphrase) { mutableStateOf(snap?.settings?.passphrase ?: "") }
        var editing by remember { mutableStateOf(!set) }
        var confirm by remember { mutableStateOf(false) }
        var showUnlock by remember { mutableStateOf(false) }

        Section("Pair the other phone")
        if (showUnlock) {
            PasswordDialog(title = "Unlock settings",
                onSubmit = { secret, result -> app.unlock(secret, result) },
                onDismiss = { showUnlock = false })
        }

        if (set && !editing) {
            Text("The other phone connects with these. Both phones start with the same ones.", fontSize = T.label, color = Muted)
            Spacer(Modifier.height(S.xs))
            Text(snap?.settings?.ssid ?: "", fontSize = T.headline,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(snap?.settings?.passphrase ?: "", fontSize = T.headline,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Spacer(Modifier.height(S.sm))
            OutlinedButton(onClick = { if (locked) showUnlock = true else editing = true }) {
                Text(if (locked) "Unlock to change" else "Change")
            }
            return
        }

        OutlinedTextField(name, { name = it }, label = { Text("Name", fontSize = T.caption) },
            supportingText = { Text("Must start with DIRECT-", fontSize = T.caption) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(S.sm))
        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase", fontSize = T.caption) },
            supportingText = { Text("8 to 63 characters", fontSize = T.caption) },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(S.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(S.sm)) {
            Button(onClick = {
                    if (locked) showUnlock = true
                    else if (set) confirm = true
                    else applyPairing(name, pass) { editing = false }
                },
                enabled = locked || (pass.length in 8..63 && name.startsWith("DIRECT-"))) {
                Text(if (locked) "Unlock to save" else "Save")
            }
            if (set) OutlinedButton(onClick = { editing = false }) { Text("Cancel") }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Change the pairing?") },
                text = { Text("The other phone will disconnect until you type the new " +
                        "name and passphrase into it.", fontSize = T.label) },
                confirmButton = {
                    TextButton(onClick = { confirm = false; applyPairing(name, pass) { editing = false } }) { Text("Change") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } })
        }
    }

    private fun applyPairing(name: String, pass: String, onDone: () -> Unit) {
        runCatching {
            Svc.patchSettings(-1, Svc.settings.copy(ssid = name.trim(), passphrase = pass),
                java.util.UUID.randomUUID().toString(), Svc.unlockToken, Actor.ALARM)
        }.onSuccess { onDone(); refreshGates() }
            .onFailure {
                Toast.makeText(this, "Not saved. " + (if (it is com.mtrinh.fobalarm.data.ClientError.Forbidden)
                    "Unlock settings first." else "Check the name and passphrase."), Toast.LENGTH_LONG).show()
            }
    }

    @Composable
    private fun DeviceSettings() {
        // Driven by observable snapshot state, not by Svc globals: reading those meant
        // the subtree did not recompose after an unlock or a ringtone change.
        val snap = if (::app.isInitialized) app.snapshot else null
        val locked = (snap?.settings?.hasPassword ?: true) && !app.unlocked
        var pw by remember { mutableStateOf("") }

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
        OutlinedButton(onClick = { asking = true }) {
            Text(if (Svc.settings.role == Role.ALARM) "Make this the controller" else "Make this the alarm phone")
        }
        if (asking) {
            val target = if (Svc.settings.role == Role.ALARM) Role.CONTROLLER else Role.ALARM
            AlertDialog(
                onDismissRequest = { asking = false },
                title = { Text(if (target == Role.ALARM) "Make this the alarm phone?" else "Make this the controller?") },
                text = {
                    Column {
                        Text(if (target == Role.CONTROLLER)
                                "This phone will stop ringing entirely. The pairing is cleared."
                             else "This phone will ring at the alarm time. The pairing is cleared.",
                            fontSize = T.label)
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

    /** Controller-side pairing. Must match the alarm phone's group byte for byte. */
    @Composable
    private fun ControllerSetup(onSet: (String, String) -> Unit) {
        var ssid by remember { mutableStateOf(Settings.DEFAULT_SSID) }
        var pass by remember { mutableStateOf(Settings.DEFAULT_PASSPHRASE) }
        val hasPerm = remember(permTick) {
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        var asked by remember { mutableIntStateOf(0) }
        Page(
            title = "Pair with the alarm phone",
            subtitle = "Both phones start with these. Change them only if the alarm phone's were changed.",
            applyInsets = true,
        ) {
            if (!hasPerm) {
                Spacer(Modifier.height(S.sm))
                Text("Nearby devices permission is needed to connect.", fontSize = T.label, color = Bad)
                Spacer(Modifier.height(S.xs))
                // After two denials Android stops showing the dialog; send them to
                // the page that can still grant it instead of a button that does nothing.
                val exhausted = asked >= 2
                Button(onClick = {
                    if (exhausted) startActivity(Intent(ASettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                    else { asked++; perms.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES,
                        Manifest.permission.ACCESS_FINE_LOCATION)) }
                }) { Text(if (exhausted) "Open settings" else "Allow") }
            }
            Spacer(Modifier.height(S.sm))
            OutlinedTextField(ssid, { ssid = it }, label = { Text("Name", fontSize = T.caption) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(S.sm))
            OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase", fontSize = T.caption) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(S.md))
            Button(onClick = { onSet(ssid.trim(), pass) },
                enabled = hasPerm && pass.length in 8..63 && ssid.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            Spacer(Modifier.height(S.lg))
            TextButton(onClick = { Svc.setRole(Role.ALARM); recreate() }) {
                Text("This is actually the alarm phone")
            }
        }
    }

    @Composable
    private fun RolePicker(onPick: (Role) -> Unit) {
        var chosen by remember { mutableStateOf<Role?>(null) }
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(S.page),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Which phone is this?", fontSize = T.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(S.lg))
            Button(onClick = { chosen = Role.ALARM }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("The alarm phone, in the box", fontSize = T.button)
            }
            Spacer(Modifier.height(S.sm))
            Button(onClick = { chosen = Role.CONTROLLER }, modifier = Modifier.fillMaxWidth().height(80.dp)) {
                Text("The controller, in the other room", fontSize = T.button)
            }
        }
        chosen?.let { r ->
            AlertDialog(
                onDismissRequest = { chosen = null },
                title = { Text(if (r == Role.ALARM) "This phone will ring" else "This phone will not ring") },
                text = { Text(if (r == Role.ALARM) "It lives in the box and rings at the alarm time."
                              else "It watches the alarm phone and can stop it.", fontSize = T.label) },
                confirmButton = { TextButton(onClick = { onPick(r); chosen = null }) { Text("Yes") } },
                dismissButton = { TextButton(onClick = { chosen = null }) { Text("No") } })
        }
    }
}
