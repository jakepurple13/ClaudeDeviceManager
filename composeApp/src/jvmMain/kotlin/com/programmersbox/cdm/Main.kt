package com.programmersbox.cdm

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import com.programmersbox.cdm.notify.Notifier
import java.awt.Frame
import kotlinx.coroutines.flow.map
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.di.appModules
import com.programmersbox.cdm.install.InstallLifecycle
import com.programmersbox.cdm.server.ServerController
import com.programmersbox.cdm.service.DeviceManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.programmersbox.cdm.ui.nav.AppNavigation
import com.programmersbox.cdm.ui.nav.Navigator
import com.programmersbox.cdm.ui.nav.applyDevStartRoute
import com.programmersbox.cdm.ui.window.AppIcon
import com.programmersbox.cdm.ui.window.AppWindow
import com.programmersbox.cdm.ui.window.TitleBarStatus
import com.programmersbox.cdm.ui.window.windowChrome
import java.awt.Desktop
import java.awt.Dimension
import java.awt.desktop.QuitStrategy
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import com.programmersbox.cdm.di.APP_SCOPE

private const val APP_TITLE = "Claude Device Manager"

fun main() {
    val koin = startKoin { modules(appModules()) }.koin
    val appScope = koin.get<CoroutineScope>(named(APP_SCOPE))
    // Ruling L: start() first, then accept hooks.
    koin.get<DeviceManagerService>().start(appScope)
    koin.get<Notifier>().start(appScope)
    val port = runBlocking { koin.get<SettingsRepository>().current().port }
    // Port in use, second instance, ...: the controller keeps the UI up and puts the error in ServerStatus.
    // A successful bind installs the Claude Code integration (InstallLifecycle is the controller's listener);
    // a failed one installs nothing until Retry or a port change succeeds.
    val server = koin.get<ServerController>()
    val integration = koin.get<InstallLifecycle>()
    // Exit order: remove the integration first (so Claude stops reaching out), then stop the server (port file).
    // Both steps are idempotent and synchronous, so the shutdown hook can't deadlock on coroutines.
    val shutdown = {
        integration.onExit()
        server.stop()
    }
    // SIGTERM (and any System.exit) skips the window-close path.
    Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
    routeQuitThroughWindowClose()
    AppIcon.applyToDock()
    runBlocking { server.start(port) }
    applyDevStartRoute(koin.get<Navigator>())
    val chrome = windowChrome()
    val trayState = koin.get<TrayState>()
    val notificationsOn = koin.get<SettingsRepository>().settings.map { it.notifications.any }
    application {
        var mainWindow by remember { mutableStateOf<ComposeWindow?>(null) }
        val quit = {
            shutdown()
            appScope.cancel()
            exitApplication()
        }
        // Compose only shows notifications while a Tray exists, so the menu-bar icon is there only while one is on.
        val showTray by notificationsOn.collectAsState(initial = false)
        val trayIcon = AppIcon.painter
        if (showTray && trayIcon != null) {
            Tray(
                icon = trayIcon,
                state = trayState,
                tooltip = APP_TITLE,
                onAction = { mainWindow?.bringToFront() },
            ) {
                Item("Show $APP_TITLE", onClick = { mainWindow?.bringToFront() })
                Separator()
                Item("Quit", onClick = quit)
            }
        }
        AppWindow(
            onCloseRequest = quit,
            title = APP_TITLE,
            chrome = chrome,
            state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
            titleBar = { TitleBarStatus(APP_TITLE) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(800, 560)
                mainWindow = window
            }
            AppNavigation()
        }
    }
}

/** The tray's "Show" action: un-minimizes the window and raises it. */
private fun ComposeWindow.bringToFront() {
    if (extendedState and Frame.ICONIFIED != 0) extendedState = extendedState and Frame.ICONIFIED.inv()
    isVisible = true
    toFront()
    requestFocus()
}

/**
 * Cmd+Q closes the windows (onCloseRequest: uninstall, stop, exitApplication) instead of AWT's default System.exit.
 * Where that isn't supported, System.exit still runs the shutdown hook, which does the same cleanup.
 */
private fun routeQuitThroughWindowClose() {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
            Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS)
        }
    }
}
