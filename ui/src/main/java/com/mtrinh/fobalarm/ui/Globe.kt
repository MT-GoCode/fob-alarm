package com.mtrinh.fobalarm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.lerp
import kotlin.math.*

/**
 * The ONLY custom-drawn thing in the app: a projected wireframe globe with a marker at
 * the current orientation and a fading breadcrumb of recent orientations, so you watch
 * the path you have traced.
 *
 * Legibility beats ornament at 4 AM: the deg/threshold NUMERAL is the primary element,
 * large and centred; the globe is secondary and sits behind it. SPEC.md section 7.
 */
@Composable
fun RotationInstrument(
    degrees: Double,
    threshold: Int,
    quaternion: DoubleArray?,
    snoozed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val trail = remember { mutableStateListOf<Offset>() }
    val progress = (degrees / threshold).coerceIn(0.0, 1.0)
    val crossed = degrees >= threshold

    // Colour tracks progress continuously, so the widget is informative the whole way
    // rather than only at the end: grey while still, blue as it accumulates, green once
    // the threshold is crossed.
    val live = MaterialTheme.colorScheme.primary
    val active = when {
        snoozed -> Good
        crossed -> Good
        progress > 0.02 -> lerp(Muted, live, (progress * 1.4).coerceAtMost(1.0).toFloat())
        else -> Muted
    }

    val marker = remember(quaternion) {
        quaternion?.let { q ->
            val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
            Offset((2 * (x * z + w * y)).toFloat(), (2 * (y * z - w * x)).toFloat())
        }
    }
    LaunchedEffect(marker) {
        marker?.let {
            trail.add(it)
            while (trail.size > 48) trail.removeAt(0)
        }
    }
    // Clear the traced path whenever the count resets, so the globe agrees with the number.
    LaunchedEffect(degrees < 1.0) { if (degrees < 1.0) trail.clear() }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f * 0.82f
            val c = Offset(size.width / 2f, size.height / 2f)
            val wire = Color(0xFF243039)

            for (i in 1..4) {
                val lat = (i / 5f) * (PI / 2).toFloat()
                val ry = r * sin(lat); val rx = r * cos(lat)
                for (sgn in listOf(1f, -1f)) {
                    drawOval(color = wire,
                        topLeft = Offset(c.x - rx, c.y + sgn * ry - r * 0.12f),
                        size = androidx.compose.ui.geometry.Size(rx * 2, r * 0.24f),
                        style = Stroke(width = 1f))
                }
            }
            for (i in 0 until 6) {
                val a = i * PI.toFloat() / 6f
                val rx = abs(r * cos(a))
                drawOval(color = wire, topLeft = Offset(c.x - rx, c.y - r),
                    size = androidx.compose.ui.geometry.Size(rx * 2, r * 2),
                    style = Stroke(width = 1f))
            }
            drawCircle(color = wire, radius = r, center = c, style = Stroke(width = 1.5f))

            // How far round the threshold you are.
            drawArc(
                color = active,
                startAngle = -90f,
                sweepAngle = (360 * progress).toFloat(),
                useCenter = false,
                topLeft = Offset(c.x - r * 1.1f, c.y - r * 1.1f),
                size = androidx.compose.ui.geometry.Size(r * 2.2f, r * 2.2f),
                style = Stroke(width = 9f),
            )

            // The path actually traced, fading behind the marker.
            if (trail.size > 1) {
                val path = Path()
                trail.forEachIndexed { i, p ->
                    val px = c.x + p.x * r; val py = c.y + p.y * r
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, color = active.copy(alpha = 0.45f), style = Stroke(width = 2f))
            }
            marker?.let {
                drawCircle(color = active, radius = 8f,
                    center = Offset(c.x + it.x * r, c.y + it.y * r))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${degrees.toInt()}", fontSize = T.title, fontWeight = FontWeight.Bold,
                // Always legible: `active` is for the arc and the trail, not the numeral.
                color = if (crossed || snoozed) Good else MaterialTheme.colorScheme.onSurface)
            Text("of $threshold", fontSize = T.caption, color = Muted)
        }
    }
}

