package com.mtrinh.fobalarm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed palette, dynamic color OFF: the two phones must look identical and must not
 * shift with wallpaper. Near-black surfaces so the screen is unobtrusive at 3 AM.
 * SPEC.md section 6.
 */
private val scheme = darkColorScheme(
    primary = Color(0xFF8FD6FF),
    onPrimary = Color(0xFF00344B),
    secondary = Color(0xFFB7C9D6),
    background = Color(0xFF07090B),
    onBackground = Color(0xFFE3E6E8),
    surface = Color(0xFF0D1114),
    onSurface = Color(0xFFE3E6E8),
    surfaceVariant = Color(0xFF1A2026),
    onSurfaceVariant = Color(0xFFB9C2CA),
    error = Color(0xFFFF6B6B),
)

val Good = Color(0xFF7BE07B)
val Bad = Color(0xFFFF6B6B)
val Muted = Color(0xFF8A949C)

@Composable
fun FobTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = FobTypography, content = content)
