package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.mtrinh.fobalarm.core.Snapshot

/**
 * The single page container every screen uses.
 *
 * It exists for one concrete reason: nothing in this app handled window insets, so on a
 * phone with a camera cutout the first line of every screen was drawn underneath it.
 * Handling that once, here, is the difference between fixing it and fixing it fifteen
 * times and missing some.
 */
@Composable
fun Page(
    title: String? = null,
    subtitle: String? = null,
    /** Shown as a version line at the bottom, so you can always tell which build is running. */
    snapshot: Snapshot? = null,
    /** False when hosted in a Scaffold, which has already applied them. */
    applyInsets: Boolean = false,
    /** A refresh button beside the title, for screens that show the other phone's state. */
    onReload: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier
        .fillMaxSize()
        .then(if (applyInsets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
        .padding(horizontal = S.page)
        .verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(S.md))
        title?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, fontSize = T.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (onReload != null) IconButton(onClick = onReload) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            Spacer(Modifier.height(S.xs))
        }
        subtitle?.let {
            Text(it, fontSize = T.label, color = Muted)
            Spacer(Modifier.height(S.xs))
        }
        content()
        if (snapshot != null) {
            Spacer(Modifier.height(S.lg))
            VersionBar(snapshot)
        }
        Spacer(Modifier.height(S.lg))
    }
}

/** Which build is running, and whether the other phone runs the same one. */
@Composable
fun VersionBar(s: Snapshot) {
    val peer = s.peer
    val mismatch = peer != null && peer.appVersion != s.self.appVersion
    HorizontalDivider(color = Color(0xFF151A1F))
    Spacer(Modifier.height(S.xs))
    Text("Version ${s.self.appVersion}", fontSize = T.caption, color = Muted)
    if (mismatch) Text("The other phone has ${peer!!.appVersion}. Update the older one.",
        fontSize = T.caption, color = Bad)
}

/** One password prompt, used by unlock, role change and anything else that gates. */
@Composable
fun PasswordDialog(
    title: String,
    body: String? = null,
    confirmText: String = "Unlock",
    /** Reports the real result asynchronously; the dialog stays open on failure. */
    onSubmit: (String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                body?.let { Text(it, fontSize = T.label); Spacer(Modifier.height(S.sm)) }
                OutlinedTextField(secret, { secret = it; failed = false },
                    label = { Text("Password", fontSize = T.caption) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (failed) Text("Wrong password", color = Bad, fontSize = T.caption)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && secret.isNotBlank(),
                onClick = {
                    busy = true
                    onSubmit(secret) { ok ->
                        busy = false
                        if (ok) onDismiss() else failed = true
                    }
                }) { Text(if (busy) "Checking…" else confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Section heading, used by every settings-shaped list. */
@Composable
fun Section(title: String, note: String? = null) {
    Spacer(Modifier.height(S.md))
    HorizontalDivider(color = Color(0xFF1A2026))
    Spacer(Modifier.height(S.sm))
    Text(title, fontSize = T.label, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
    note?.let { Text(it, fontSize = T.caption, color = Muted) }
    Spacer(Modifier.height(S.xs))
}
