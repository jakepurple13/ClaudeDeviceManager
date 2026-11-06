package com.programmersbox.cdm.di

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.devices.IosTracker
import com.programmersbox.cdm.ui.lifecycle.LifecycleTiming
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlinx.coroutines.flow.Flow
import org.koin.dsl.module
import org.koin.test.verify.verify

class KoinGraphTest {
    @Test
    fun verifyGraph() {
        // One module including all: uiModule's definitions depend on singles declared in the core module.
        // DeviceKey / HolderId are the detail view models' parametersOf arguments. LifecycleController is built
        // from flows and lambdas passed by hand in its definition; Flow: the Notifier's events and settings.
        module { includes(appModules()) }
            .verify(
                extraTypes = listOf(
                    Path::class, Set::class, DeviceKey::class, HolderId::class,
                    StateFlow::class, Flow::class, Function0::class, Function1::class, LifecycleTiming::class,
                    IosTracker::class, // null off macOS, so built inline in the DeviceSource definition
                ),
            )
    }
}
