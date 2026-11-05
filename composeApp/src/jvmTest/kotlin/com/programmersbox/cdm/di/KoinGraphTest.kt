package com.programmersbox.cdm.di

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import java.nio.file.Path
import kotlin.test.Test
import org.koin.dsl.module
import org.koin.test.verify.verify

class KoinGraphTest {
    @Test
    fun verifyGraph() {
        // One module including all: uiModule's definitions depend on singles declared in the core module.
        // DeviceKey / HolderId are the detail view models' parametersOf arguments.
        module { includes(appModules()) }
            .verify(extraTypes = listOf(Path::class, Set::class, DeviceKey::class, HolderId::class))
    }
}
