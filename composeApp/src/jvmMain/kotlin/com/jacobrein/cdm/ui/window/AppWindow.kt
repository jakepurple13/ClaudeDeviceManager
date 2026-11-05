package com.jacobrein.cdm.ui.window

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.jacobrein.cdm.ui.theme.AppTheme
import kotlin.math.roundToInt
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.intui.standalone.theme.darkThemeDefinition
import org.jetbrains.jewel.intui.standalone.theme.default
import org.jetbrains.jewel.intui.standalone.theme.lightThemeDefinition
import org.jetbrains.jewel.intui.window.decoratedWindow
import org.jetbrains.jewel.intui.window.styling.dark
import org.jetbrains.jewel.intui.window.styling.defaults
import org.jetbrains.jewel.intui.window.styling.light
import org.jetbrains.jewel.intui.window.styling.lightWithLightHeader
import org.jetbrains.jewel.ui.ComponentStyling
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.TitleBarScope
import org.jetbrains.jewel.window.styling.DecoratedWindowColors
import org.jetbrains.jewel.window.styling.DecoratedWindowStyle
import org.jetbrains.jewel.window.styling.TitleBarColors
import org.jetbrains.jewel.window.styling.TitleBarMetrics
import org.jetbrains.jewel.window.styling.TitleBarStyle
import org.jetbrains.jewel.window.utils.clientRegion

/** What a title-bar slot can use: the window's [chrome], and [interactive] for clickable parts of the bar. */
interface AppTitleBarScope {
    val chrome: WindowChrome

    /**
     * Marks this element as clickable inside the custom title bar; the rest of the bar drags the window.
     * [key] must be unique within the window. No-op with the system title bar.
     */
    fun Modifier.interactive(key: String): Modifier
}

private object CustomTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.Custom
    override fun Modifier.interactive(key: String): Modifier = clientRegion(key)
}

private object SystemTitleBarScope : AppTitleBarScope {
    override val chrome get() = WindowChrome.System
    override fun Modifier.interactive(key: String): Modifier = this
}

/**
 * The main window. [WindowChrome.Custom] is a Jewel decorated window whose title bar is painted from the Material
 * scheme (so it reads as one surface with the nav rail) and hosts [titleBar] after the macOS traffic lights.
 * [WindowChrome.System] is a plain [Window] with the OS title bar and [titleBar] as a strip above [content].
 * Only pass [WindowChrome.Custom] on JetBrains Runtime 25+ (see [windowChrome]).
 */
@Composable
fun AppWindow(
    onCloseRequest: () -> Unit,
    title: String,
    chrome: WindowChrome,
    state: WindowState = rememberWindowState(),
    titleBarHeight: Dp = 38.dp,
    titleBar: @Composable AppTitleBarScope.() -> Unit,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    when (chrome) {
        WindowChrome.System -> Window(onCloseRequest = onCloseRequest, state = state, title = title) {
            AppTheme(dark) {
                Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalContentColor provides barColors(MaterialTheme.colorScheme).content) {
                        SystemTitleBarScope.titleBar()
                    }
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                }
            }
        }
        WindowChrome.Custom -> AppTheme(dark) {
            val bar = barColors(MaterialTheme.colorScheme)
            IntUiTheme(
                theme = if (dark) JewelTheme.darkThemeDefinition() else JewelTheme.lightThemeDefinition(),
                styling = ComponentStyling.default().decoratedWindow(
                    windowStyle = decoratedWindowStyle(bar, dark),
                    titleBarStyle = titleBarStyle(bar, dark, titleBarHeight),
                ),
            ) {
                // Jewel's theme replaces LocalIndication and friends; give the window Material's locals back.
                AppTheme(dark) {
                    DecoratedWindow(onCloseRequest = onCloseRequest, state = state, title = title) {
                        TitleBar { _ ->
                            BoundedSlot {
                                CompositionLocalProvider(LocalContentColor provides bar.content) {
                                    CustomTitleBarScope.titleBar()
                                }
                            }
                        }
                        content()
                    }
                }
            }
        }
    }
}

/**
 * The title-bar slot, at most as wide as the room between the window controls. Jewel measures the bar's children
 * against its full width and only then adds the macOS traffic-light inset, so a full-width child would run past
 * the right edge. Jewel keeps the insets private: they are read back from where it places the slot (start) and a
 * zero-width end marker (end), and the slot is narrowed by them from the next layout on.
 */
@Composable
private fun TitleBarScope.BoundedSlot(content: @Composable () -> Unit) {
    var startInset by remember { mutableIntStateOf(0) }
    var endInset by remember { mutableIntStateOf(0) }
    Row(
        Modifier
            .align(Alignment.Start)
            .onPlaced { startInset = it.positionInParent().x.roundToInt() }
            .layout { measurable, constraints ->
                val maxWidth = (constraints.maxWidth - startInset - endInset).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = maxWidth))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
    Box(
        Modifier.align(Alignment.End).onPlaced {
            val barWidth = it.parentLayoutCoordinates?.size?.width ?: return@onPlaced
            endInset = barWidth - it.positionInParent().x.roundToInt()
        },
    )
}

/** Frame border (Jewel only draws one on Linux; macOS keeps the native frame). */
private fun decoratedWindowStyle(bar: BarColors, dark: Boolean): DecoratedWindowStyle {
    val colors = DecoratedWindowColors(border = bar.border, borderInactive = bar.border)
    return if (dark) DecoratedWindowStyle.dark(colors) else DecoratedWindowStyle.light(colors)
}

@Composable
private fun titleBarStyle(bar: BarColors, dark: Boolean, height: Dp): TitleBarStyle {
    val hover = bar.content.copy(alpha = 0.08f)
    val pressed = bar.content.copy(alpha = 0.12f)
    val colors = TitleBarColors(
        background = bar.background,
        inactiveBackground = bar.inactiveBackground,
        content = bar.content,
        // Jewel draws a 1dp line under the bar; same colour as the bar so it reads as one surface with the rail.
        border = bar.background,
        fullscreenControlButtonsBackground = bar.background,
        titlePaneButtonHoveredBackground = hover,
        titlePaneButtonPressedBackground = pressed,
        titlePaneCloseButtonHoveredBackground = hover,
        titlePaneCloseButtonPressedBackground = pressed,
        iconButtonHoveredBackground = hover,
        iconButtonPressedBackground = pressed,
        dropdownHoveredBackground = hover,
        dropdownPressedBackground = pressed,
    )
    val metrics = TitleBarMetrics.defaults(height = height)
    return if (dark) TitleBarStyle.dark(colors, metrics) else TitleBarStyle.lightWithLightHeader(colors, metrics)
}
