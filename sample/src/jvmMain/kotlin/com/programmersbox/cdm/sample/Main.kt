package com.programmersbox.cdm.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.defaults
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarMetrics
import org.jetbrains.jewel.window.styling.TitleBarStyle

/**
 * A minimal app that shares devices through Claude Device Manager.
 *
 * Start it while CDM is open: ./gradlew :sample:run
 * The holder ID defaults to "cdm-sample:main". To try two copies waiting for one device,
 * give the second its own: ./gradlew :sample:run --args="cdm-sample:second"
 */
fun main(args: Array<String>) = application {
    val holder = args.firstOrNull() ?: "cdm-sample:main"
    val scope = rememberCoroutineScope()
    val state = remember { SampleState(holder, scope) }
    LaunchedEffect(Unit) { state.connect() }

    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors

    // The window and its title bar come from Jewel; everything inside is plain Material 3.
    IntUiTheme(
        theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
        styling = ComponentStyling.default().decoratedWindow(titleBarStyle = titleBarStyle(colors, dark)),
    ) {
        DecoratedWindow(
            // Give the device back before quitting, so the next app in line gets it right away.
            onCloseRequest = {
                state.releaseNow()
                exitApplication()
            },
            title = "CDM Sample Client",
            state = rememberWindowState(width = 1040.dp, height = 720.dp),
        ) {
            MaterialTheme(colorScheme = colors) {
                TitleBar { ConnectionBar(state) }
                SampleScreen(state)
            }
        }
    }
}

/** Paints the title bar like Claude Device Manager's own. */
@Composable
private fun titleBarStyle(colors: ColorScheme, dark: Boolean): TitleBarStyle {
    val bar = colors.surfaceContainer
    val hover = colors.onSurface.copy(alpha = 0.08f)
    val barColors = TitleBarColors(
        background = bar,
        inactiveBackground = colors.surfaceContainerLow,
        content = colors.onSurface,
        border = colors.outlineVariant,
        fullscreenControlButtonsBackground = bar,
        titlePaneButtonHoveredBackground = hover,
        titlePaneButtonPressedBackground = hover,
        titlePaneCloseButtonHoveredBackground = hover,
        titlePaneCloseButtonPressedBackground = hover,
        iconButtonHoveredBackground = hover,
        iconButtonPressedBackground = hover,
        dropdownHoveredBackground = hover,
        dropdownPressedBackground = hover,
    )
    val metrics = TitleBarMetrics.defaults(height = 40.dp)
    return if (dark) TitleBarStyle.dark(barColors, metrics) else TitleBarStyle.lightWithLightHeader(barColors, metrics)
}
