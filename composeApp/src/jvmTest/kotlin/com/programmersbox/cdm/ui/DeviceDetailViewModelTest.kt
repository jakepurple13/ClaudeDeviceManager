package com.programmersbox.cdm.ui

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.devices.TrackedDevice
import com.programmersbox.cdm.ui.devices.DeviceDetailViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
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

    @Test
    fun detailKindLabel() = runBlocking {
        val sim = DeviceKey("UDID-1")
        val pixel = DeviceKey("P1")
        val snap = AllocatorSnapshot(
            devices = listOf(
                AllocDevice(sim, "iPhone 16 · iOS 18.2", Category.PHONE, false, listOf("UDID-1"), Platform.IOS, virtual = true),
                AllocDevice(pixel, "Pixel 8", Category.PHONE, false, listOf("s1")),
            ),
            holds = emptyMap(), queue = emptyList(), statuses = emptyMap(),
        )
        val tracked = listOf(
            TrackedDevice(sim, listOf("UDID-1"), "iPhone 16", "18.2", "simulator", Category.PHONE, Platform.IOS, virtual = true),
            TrackedDevice(pixel, listOf("s1"), "Pixel 8", "15", "usb", Category.PHONE),
        )
        val manager = RecordingManager(MutableStateFlow(snap), tracked = MutableStateFlow(tracked))
        val iosVm = DeviceDetailViewModel(sim, testActions(manager), testSources(manager))
        val androidVm = DeviceDetailViewModel(pixel, testActions(manager), testSources(manager))

        val ios = withTimeout(5_000) { iosVm.uiState.first { it.loaded } }
        val android = withTimeout(5_000) { androidVm.uiState.first { it.loaded } }

        assertEquals(listOf("Platform" to "iOS", "Kind" to "Simulator", "Runtime" to "iOS 18.2", "UDID" to "UDID-1"), ios.info.take(4))
        assertEquals(listOf("Platform" to "Android", "Kind" to "Real", "Runtime" to "Android 15", "Serial" to "P1"), android.info.take(4))
        iosVm.closeForTest()
        androidVm.closeForTest()
    }

    /** Final whole-branch review: the holder card times the hold of this device, not the holder's first hold. */
    @Test
    fun holderCardUsesThisDevicesHold() = runBlocking {
        val sim = DeviceKey("UDID-1")
        val pixel = DeviceKey("P1")
        val h = HolderId("a41f")
        val t = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val android = HolderStatus.Holding(pixel, t - 2.minutes, t - 1.minutes, editing = false)
        val ios = HolderStatus.Holding(sim, t - 20.minutes, t - 7.minutes, editing = false, platform = Platform.IOS)
        val snap = AllocatorSnapshot(
            devices = listOf(
                AllocDevice(pixel, "Pixel 8", Category.PHONE, false, listOf("s1")),
                AllocDevice(sim, "iPhone 16", Category.PHONE, false, listOf("UDID-1"), Platform.IOS, virtual = true),
            ),
            holds = mapOf(pixel to h, sim to h), queue = emptyList(),
            statuses = mapOf(h to android), holdings = mapOf(h to listOf(android, ios)),
        )
        val manager = RecordingManager(MutableStateFlow(snap))
        val vm = DeviceDetailViewModel(sim, testActions(manager), testSources(manager))
        val state = withTimeout(5_000) { vm.uiState.first { it.loaded } }
        assertEquals("held 20m · last device command 7m ago · releases if idle 8m more", state.holder?.timing)
        assertEquals("idle 7m", state.row?.timerLabel)
        vm.closeForTest()
    }
}
