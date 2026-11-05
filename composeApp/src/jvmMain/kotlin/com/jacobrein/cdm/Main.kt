package com.jacobrein.cdm

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.di.appModules
import com.jacobrein.cdm.server.CdmServer
import com.jacobrein.cdm.service.DeviceManager
import com.jacobrein.cdm.service.DeviceManagerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    val koin = startKoin { modules(appModules()) }.koin
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Ruling L: start() first, then accept hooks.
    (koin.get<DeviceManager>() as DeviceManagerService).start(appScope)
    val port = runBlocking { koin.get<SettingsRepository>().current().port }
    val server = CdmServer(koin.get<DeviceManager>(), port)
    runBlocking { server.start() }
    application {
        Window(
            onCloseRequest = {
                server.stop()
                appScope.cancel()
                exitApplication()
            },
            title = "Claude Device Manager",
        ) {}
    }
}
