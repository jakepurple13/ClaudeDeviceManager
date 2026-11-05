package com.jacobrein.cdm

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.di.appModules
import com.jacobrein.cdm.server.ServerController
import com.jacobrein.cdm.service.DeviceManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.jacobrein.cdm.ui.nav.AppNavigation
import com.jacobrein.cdm.ui.nav.Navigator
import com.jacobrein.cdm.ui.nav.applyDevStartRoute
import com.jacobrein.cdm.ui.window.AppWindow
import com.jacobrein.cdm.ui.window.TitleBarStatus
import com.jacobrein.cdm.ui.window.windowChrome
import java.awt.Dimension
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import com.jacobrein.cdm.di.APP_SCOPE

private const val APP_TITLE = "Claude Device Manager"

fun main() {
    val koin = startKoin { modules(appModules()) }.koin
    val appScope = koin.get<CoroutineScope>(named(APP_SCOPE))
    // Ruling L: start() first, then accept hooks.
    koin.get<DeviceManagerService>().start(appScope)
    val port = runBlocking { koin.get<SettingsRepository>().current().port }
    // Port in use, second instance, ...: the controller keeps the UI up and puts the error in ServerStatus.
    val server = koin.get<ServerController>()
    runBlocking { server.start(port) }
    // Cmd+Q / SIGTERM skip the window-close path; stop() is idempotent and deletes the port file.
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    applyDevStartRoute(koin.get<Navigator>())
    val chrome = windowChrome()
    application {
        AppWindow(
            onCloseRequest = {
                server.stop()
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
