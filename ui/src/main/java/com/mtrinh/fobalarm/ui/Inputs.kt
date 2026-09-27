package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Right widget for the right job. Every one of these is bounded at the widget, so an
 * out-of-range value cannot be typed, sent, clamped server-side and reported back as
 * something you did not choose.
 */

// ---------------------------------------------------------------------------
// Time: a real clock face. Previously a free-text field where "0530" failed
// validation and surfaced as a raw HTTP 500 string at the bottom of the page --
// on the single most important control in the app.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSetting(
    label: String,
    value: String,                 // "HH:mm"
    help: String? = null,
    onSet: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val (h, m) = remember(value) {
        runCatching { value.split(":").map { it.toInt() } }.getOrDefault(listOf(4, 0))
            .let { (it.getOrElse(0) { 4 }) to (it.getOrElse(1) { 0 }) }
    }

    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            help?.let { Text(it, fontSize = 11.sp, color = Muted) }
        }
        TextButton(onClick = { open = true }) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    if (open) {
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onSet("%02d:%02d".format(state.hour, state.minute))
                    open = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------
// Discrete durations: the values anyone would actually pick, as one tap each.
// A 5..600 slider made 30s and 600s the same gesture, and neither landable.
// ---------------------------------------------------------------------------

@Composable
fun ChoiceSetting(
    label: String,
    help: String? = null,
    options: List<Pair<String, Int>>,
    value: Int,
    onSet: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(label, fontSize = 14.sp)
        help?.let { Text(it, fontSize = 11.sp, color = Muted) }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (text, v) ->
                // Selected is still tappable: for actions like "nap 20m" the selected
                // value is exactly the one you want to press again.
                if (v == value) {
                    Button(onClick = { onSet(v) },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(36.dp)) { Text(text, fontSize = 12.sp) }
                } else {
                    OutlinedButton(onClick = { onSet(v) },
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(36.dp)) { Text(text, fontSize = 12.sp) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Continuous bounded values: a slider that snaps to meaningful steps and
// commits on release, so there is no separate "Set" button to forget.
// ---------------------------------------------------------------------------

@Composable
fun SliderSetting(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    suffix: String,
    help: String? = null,
    onSet: (Int) -> Unit,
) {
    var live by remember { mutableIntStateOf(value) }
    // Snap back whenever the authoritative value changes -- including a failed save,
    // where it never changed at all.
    LaunchedEffect(value) { live = value }
    val steps = ((max - min) / step - 1).coerceAtLeast(0)
    Column(Modifier.padding(vertical = 8.dp)) {
        Row {
            Text(label, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("$live$suffix", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
        }
        help?.let { Text(it, fontSize = 11.sp, color = Muted) }
        Slider(
            value = live.toFloat(),
            onValueChange = { live = (Math.round(it / step) * step).coerceIn(min, max) },
            onValueChangeFinished = { if (live != value) onSet(live) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
        )
    }
}

/** Standalone clock face, for one-off choices that are not a stored setting. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    title: String,
    initial: String,
    onCancel: () -> Unit,
    onSet: (String) -> Unit,
) {
    val (h, m) = remember(initial) {
        runCatching { initial.split(":").map { it.toInt() } }.getOrDefault(listOf(7, 0))
            .let { (it.getOrElse(0) { 7 }) to (it.getOrElse(1) { 0 }) }
    }
    val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onSet("%02d:%02d".format(state.hour, state.minute)) }) {
                Text("Set")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Tap target with no ripple, for overlays that only exist to explain themselves. */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
        indication = null, onClick = onClick)
