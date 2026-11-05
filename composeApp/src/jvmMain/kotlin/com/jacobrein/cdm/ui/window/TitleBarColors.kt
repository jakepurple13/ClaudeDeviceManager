package com.jacobrein.cdm.ui.window

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/** Title bar colours, taken from the Material 3 scheme so the bar and the nav rail read as one surface. */
data class BarColors(val background: Color, val inactiveBackground: Color, val content: Color, val border: Color)

fun barColors(scheme: ColorScheme): BarColors = BarColors(
    background = scheme.surfaceContainer,
    inactiveBackground = scheme.surfaceContainerLow,
    content = scheme.onSurface,
    border = scheme.outlineVariant,
)
