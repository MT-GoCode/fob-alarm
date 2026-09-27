package com.mtrinh.fobalarm.ui

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    subtitleColor: Color = Muted,
    scroll: Boolean = true,
    snapshot: Snapshot? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = Modifier
        .fillMaxSize()
        // safeDrawing covers the status bar, the navigation bar AND the display cutout.
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = 18.dp)

    Column(if (scroll) base.verticalScroll(rememberScrollState()) else base) {
        Spacer(Modifier.height(14.dp))
        title?.let {
            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
        }
        subtitle?.let {
            Text(it, fontSize = 13.sp, color = subtitleColor)
            Spacer(Modifier.height(6.dp))
        }
        content()
        if (snapshot != null) {
            Spacer(Modifier.height(24.dp))
            VersionBar(snapshot)
        }
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * Build identity, on every screen. You asked to be able to confirm what is running
 * without guessing, and after a sideload-heavy workflow that is not a nicety.
 * Shows BOTH phones, because a version mismatch between them is a real failure mode.
 */
@Composable
fun VersionBar(s: Snapshot) {
    HorizontalDivider(color = Color(0xFF151A1F))
    Spacer(Modifier.height(6.dp))
    val self = s.self
    val peer = s.peer
    val mismatch = peer != null && peer.appVersion != self.appVersion
    Column {
        Text(
            "this phone  ${self.variant.name.lowercase()} ${self.appVersion}  ·  ${self.deviceId.take(8)}",
            fontSize = 11.sp, color = Muted, fontFamily = FontFamily.Monospace)
        Text(
            if (peer == null) "other phone  never seen"
            else "other phone  ${peer.variant.name.lowercase()} ${peer.appVersion}  ·  ${peer.deviceId.take(8)}",
            fontSize = 11.sp, color = if (mismatch) Bad else Muted,
            fontFamily = FontFamily.Monospace)
        if (mismatch) {
            Text("versions differ — update the older phone", fontSize = 11.sp, color = Bad)
        }
    }
}

/**
 * A label/value row. The previous version aligned with `padEnd()`, which only works in
 * a monospaced font -- in the app's proportional font the label and value ran together,
 * which is why rows like "nearby devices granted" read as one word.
 */
@Composable
fun Fact(key: String, value: String, ok: Boolean? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(key, fontSize = 12.sp, color = Muted, modifier = Modifier.width(118.dp))
        Spacer(Modifier.width(10.dp))
        Text(value, fontSize = 12.sp, modifier = Modifier.weight(1f),
            color = when (ok) {
                true -> Good; false -> Bad; null -> MaterialTheme.colorScheme.onSurface
            })
    }
}

/** One password prompt, used by unlock, role change and anything else that gates. */
@Composable
fun PasswordDialog(
    title: String,
    body: String? = null,
    confirmText: String = "Unlock",
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                body?.let { Text(it, fontSize = 13.sp); Spacer(Modifier.height(8.dp)) }
                OutlinedTextField(secret, { secret = it; failed = false },
                    label = { Text("Password or recovery code", fontSize = 12.sp) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                if (failed) Text("Rejected", color = Bad, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onSubmit(secret)) onDismiss() else failed = true }) {
                Text(confirmText)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Section heading, used by every settings-shaped list. */
@Composable
fun Section(title: String, note: String? = null) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = Color(0xFF1A2026))
    Spacer(Modifier.height(8.dp))
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
    note?.let { Text(it, fontSize = 11.sp, color = Muted) }
    Spacer(Modifier.height(4.dp))
}
