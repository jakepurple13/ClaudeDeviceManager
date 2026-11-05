package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.AllocDevice
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.devices.DeviceDetailViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceDetailViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun dontUseWhileHeldAsks() = runBlocking {
        val key = DeviceKey("P1")
        val h = HolderId("a41f")
        val t = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val snap = AllocatorSnapshot(
            devices = listOf(AllocDevice(key, "Pixel 8", Category.PHONE, false, listOf("s1"))),
            holds = mapOf(key to h),
            queue = emptyList(),
            statuses = mapOf(h to HolderStatus.Holding(key, t, t, editing = false)),
        )
        val manager = RecordingManager(MutableStateFlow(snap))
        val vm = DeviceDetailViewModel(key, testActions(manager), testSources(manager))

        vm.onDontUseToggled(true)

        withTimeout(5_000) { vm.uiState.first { it.confirmDontUse } }
        assertTrue(manager.dontUseCalls.isEmpty(), "no service call before the user picks")

        vm.onConfirmDontUse(releaseNow = true)

        assertEquals(listOf(Triple(key, true, true)), manager.dontUseCalls)
        withTimeout(5_000) { vm.uiState.first { !it.confirmDontUse } }
        Unit
    }
}
