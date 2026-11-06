package com.programmersbox.cdm.notify

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.ui.RecordingManager
import com.programmersbox.cdm.ui.nav.Navigator
import com.programmersbox.cdm.ui.testActions
import com.programmersbox.cdm.ui.window.MainWindowHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

class NucleusNotificationSenderTest {
    private val noActions = object : NoticeActions {
        override fun open(target: NoticeTarget?) = Unit
        override fun release(held: HeldDevice) = Unit
    }

    @Test
    fun unbundledProcessFallsBackToTheTray() {
        // A test JVM is not a packaged .app, so Nucleus reports unavailable and the fallback gets the notification.
        val sent = mutableListOf<SystemNotice>()
        val sender = NucleusNotificationSender(fallback = { sent += it }, actions = noActions)
        sender.requestPermission()
        val notice = SystemNotice("Pixel 8 grabbed", "my-app now holds Pixel 8.")
        sender.send(notice)
        assertEquals(listOf(notice), sent)
    }

    @Test
    fun releaseOnlyFreesTheDeviceFromTheHolderNamedInTheNotification() {
        val pixel = DeviceKey("HW1")
        val a = HolderId("a")
        val b = HolderId("b")
        val manager = RecordingManager(MutableStateFlow(AllocatorSnapshot(emptyList(), mapOf(pixel to b), emptyList(), emptyMap())))
        val actions = AppNoticeActions(Navigator(), MainWindowHolder(), testActions(manager, CoroutineScope(Dispatchers.Unconfined)))

        actions.release(HeldDevice(pixel, a))
        assertEquals(emptyList(), manager.releases)

        actions.release(HeldDevice(pixel, b))
        assertEquals(listOf(pixel to false), manager.releases)
    }
}
