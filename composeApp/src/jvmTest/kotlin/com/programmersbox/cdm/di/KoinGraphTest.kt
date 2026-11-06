package com.programmersbox.cdm.di

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import org.koin.dsl.module
import org.koin.test.verify.verify

class KoinGraphTest {
    @Test
    fun verifyGraph() {
        // One module including all: uiModule's definitions depend on singles declared in the core module.
        // DeviceKey / HolderId are the detail view models' parametersOf arguments.
        // Flow: the Notifier's events and settings, built inline from other singles.
        module { includes(appModules()) }
            .verify(extraTypes = listOf(Path::class, Set::class, DeviceKey::class, HolderId::class, Flow::class))
    }
}
