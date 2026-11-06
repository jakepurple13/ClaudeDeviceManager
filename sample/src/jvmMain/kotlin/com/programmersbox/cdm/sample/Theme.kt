package com.programmersbox.cdm.sample

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// The same colors as Claude Device Manager (composeApp ui/theme/Theme.kt), so the sample looks like part of it.

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF6B8CFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E3A66),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondaryContainer = Color(0xFF333A46),
    onSecondaryContainer = Color.White,
    background = Color(0xFF1B1D22),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF1B1D22),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFF8B93A1),
    surfaceContainerLowest = Color(0xFF16181C),
    surfaceContainerLow = Color(0xFF1E2026),
    surfaceContainer = Color(0xFF20232A),
    surfaceContainerHigh = Color(0xFF262A33),
    surfaceContainerHighest = Color(0xFF2C313B),
    outline = Color(0xFF444B58),
    outlineVariant = Color(0xFF2F343D),
    error = Color(0xFFFFB4B4),
    errorContainer = Color(0xFF4A2A2A),
    onErrorContainer = Color(0xFFFFB4B4),
)

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF3F5FD9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF14204F),
    secondaryContainer = Color(0xFFDDE2EC),
    onSecondaryContainer = Color(0xFF1B1D22),
    background = Color(0xFFF7F8FA),
    surface = Color(0xFFF7F8FA),
    surfaceContainerLow = Color(0xFFF0F2F5),
    surfaceContainer = Color(0xFFEBEDF1),
    surfaceContainerHigh = Color(0xFFFFFFFF),
)
