package com.programmersbox.cdm

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.di.appModules
import com.programmersbox.cdm.install.InstallLifecycle
import com.programmersbox.cdm.server.ServerController
import com.programmersbox.cdm.service.DeviceManagerService
import com.programmersbox.cdm.update.UpdateController
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
    // Checks GitHub releases now and every 6 h; a no-op when run from Gradle. Installing exits through the
    // shutdown hook above, so the integration is removed before the new version starts.
    koin.get<UpdateController>().start()
    applyDevStartRoute(koin.get<Navigator>())
    val chrome = windowChrome()
    application {
        AppWindow(
            onCloseRequest = {
                shutdown()
                appScope.cancel()
                exitApplication()
            },
            title = APP_TITLE,
            chrome = chrome,
            state = rememberWindowState(size = DpSize(1100.dp, 720.dp)),
            titleBar = { TitleBarStatus(APP_TITLE) },
        ) {
            LaunchedEffect(Unit) { window.minimumSize = Dimension(800, 560) }
            AppNavigation()
        }
    }
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
