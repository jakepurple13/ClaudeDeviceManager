package com.jacobrein.cdm

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.di.appModules
import com.jacobrein.cdm.server.CdmServer
import com.jacobrein.cdm.server.ServerStatus
import com.jacobrein.cdm.service.DeviceManager
import com.jacobrein.cdm.service.DeviceManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import com.jacobrein.cdm.ui.nav.AppNavigation
import com.jacobrein.cdm.ui.window.AppWindow
import com.jacobrein.cdm.ui.window.TitleBarStatus
import com.jacobrein.cdm.ui.window.windowChrome
import java.awt.Dimension
import org.koin.core.context.startKoin

private const val APP_TITLE = "Claude Device Manager"

fun main() {
    val koin = startKoin { modules(appModules()) }.koin
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Ruling L: start() first, then accept hooks.
    koin.get<DeviceManagerService>().start(appScope)
    val port = runBlocking { koin.get<SettingsRepository>().current().port }
    val server = CdmServer(koin.get<DeviceManager>(), port)
    try {
        runBlocking { server.start() }
    } catch (e: Throwable) {
        // Port in use, second instance, ...: keep the UI up and surface the error.
        System.err.println("cdm: server failed to start on port $port: $e")
        koin.get<ServerStatus>().fail("Could not listen on 127.0.0.1:$port: ${e.message ?: e}")
    }
    // Cmd+Q / SIGTERM skip the window-close path; stop() is idempotent and deletes the port file.
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
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
