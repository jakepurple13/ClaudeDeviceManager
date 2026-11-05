package com.jacobrein.cdm

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jacobrein.cdm.di.appModules
import org.koin.core.context.startKoin

fun main() {
    startKoin { modules(appModules()) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Claude Device Manager") {}
    }
}
