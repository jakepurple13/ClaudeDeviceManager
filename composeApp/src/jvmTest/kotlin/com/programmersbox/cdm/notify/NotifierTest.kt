package com.programmersbox.cdm.notify

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorEvent
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.ReleaseReason
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.data.NotificationSettings
import com.programmersbox.cdm.service.HolderInfo
import com.programmersbox.cdm.ui.RecordingManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class NotifierTest {
    private val t0 = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val pixel = DeviceKey("HW1")
    private val app = HolderId("3f2a9b1c77", HolderId.MAIN)
    private val sub = HolderId("9c41aa00ff", "agent-1")
    private val unknown = HolderId("deadbeefcafe", HolderId.MAIN)

    private val manager = RecordingManager(
        snapshot = MutableStateFlow(
            AllocatorSnapshot(
                devices = listOf(AllocDevice(pixel, "Pixel 8", Category.PHONE, dontUse = false, connectedSerials = listOf("S1"))),
                holds = emptyMap(),
                queue = listOf(app, sub),
                statuses = emptyMap(),
            ),
        ),
        holderInfo = MutableStateFlow(
            mapOf(
                app to HolderInfo(app, "/Users/me/code/gabb-watch-app", null, null, t0, false),
                sub to HolderInfo(sub, "/Users/me/code/launcher-tests", "general", null, t0, false),
            ),
        ),
    )
    // A buffer so tryEmit succeeds; Unconfined runs the collector inline, so each emit is handled before it returns.
    private val events = MutableSharedFlow<AllocatorEvent>(extraBufferCapacity = 16)
    private val settings = MutableStateFlow(NotificationSettings())
    private val sent = mutableListOf<SystemNotice>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val notifier = Notifier(events, settings, manager) { sent += it }
        .also { it.start(scope) }

    @AfterTest fun tearDown() = scope.cancel()

    private fun emit(e: AllocatorEvent) = check(events.tryEmit(e)) { "no collector" }

    @Test
    fun grabAndReleaseNameTheProjectAndDevice() {
        emit(AllocatorEvent.Granted(t0, app, pixel))
        emit(AllocatorEvent.Released(t0, app, pixel, ReleaseReason.IDLE_TIMEOUT))
        assertEquals(
            listOf(
                SystemNotice(
                    "Pixel 8 grabbed",
                    "gabb-watch-app now holds Pixel 8.",
                    open = NoticeTarget.Device(pixel),
                    release = HeldDevice(pixel, app),
                ),
                SystemNotice("Pixel 8 released", "gabb-watch-app was idle too long.", open = NoticeTarget.Device(pixel)),
            ),
            sent,
        )
    }

    @Test
    fun queuedShowsPositionAndQueueStartReplacesGrab() {
        emit(AllocatorEvent.Queued(t0, sub, Wants(setOf(Category.PHONE))))
        emit(AllocatorEvent.Granted(t0 + 252.seconds, sub, pixel))
        assertEquals(
            listOf(
                SystemNotice("Waiting for a phone", "launcher-tests (subagent) is #2 in line.", open = NoticeTarget.Queue(sub)),
                SystemNotice(
                    "launcher-tests (subagent) is up",
                    "Got Pixel 8 after waiting 4 min 12 s.",
                    open = NoticeTarget.Device(pixel),
                    release = HeldDevice(pixel, sub),
                ),
            ),
            sent,
        )
    }

    @Test
    fun leavingTheQueueMeansALaterGrabIsAPlainGrab() {
        emit(AllocatorEvent.Queued(t0, app, Wants(setOf(Category.ANY))))
        emit(AllocatorEvent.LeftQueue(t0, app))
        emit(AllocatorEvent.Granted(t0, app, pixel))
        assertEquals(listOf("Waiting for a device", "Pixel 8 grabbed"), sent.map { it.title })
    }

    @Test
    fun switchesFilterAndQueueStartOffFallsBackToGrab() {
        settings.value = NotificationSettings(grab = true, release = false, queued = false, queueStart = false)
        emit(AllocatorEvent.Queued(t0, app, Wants(setOf(Category.PHONE))))
        emit(AllocatorEvent.Granted(t0, app, pixel))
        emit(AllocatorEvent.Released(t0, app, pixel, ReleaseReason.SELF_RELEASE))
        assertEquals(listOf("Pixel 8 grabbed"), sent.map { it.title })

        sent.clear()
        settings.value = NotificationSettings(grab = false, release = false, queued = false, queueStart = false)
        emit(AllocatorEvent.Granted(t0, app, pixel))
        notifier.sendTest()
        assertEquals(emptyList(), sent)
    }

    @Test
    fun unknownHolderAndDeviceFallBackToIds() {
        emit(AllocatorEvent.Granted(t0, unknown, DeviceKey("ZX81")))
        assertEquals("ZX81 grabbed" to "Session deadbeef now holds ZX81.", sent.single().let { it.title to it.body })
    }

    @Test
    fun testSendsOnePerEnabledSwitch() {
        settings.value = NotificationSettings(grab = true, release = false, queued = true, queueStart = false)
        notifier.sendTest()
        assertEquals(listOf("Pixel 8 grabbed", "Waiting for a phone"), sent.map { it.title })
    }

    @Test
    fun queuedNamesPlatformAndRealOrVirtual() {
        emit(AllocatorEvent.Queued(t0, app, Wants(setOf(Category.PHONE), com.programmersbox.cdm.core.model.Platform.IOS, virtual = false)))
        emit(AllocatorEvent.Queued(t0, app, Wants(setOf(Category.ANY), com.programmersbox.cdm.core.model.Platform.ANDROID, virtual = true)))
        assertEquals(listOf("Waiting for a phone (iOS, real only)", "Waiting for a device (Android, virtual only)"), sent.map { it.title })
    }

    @Test
    fun queuedSetSummarizesMembers() {
        val phone = Wants(setOf(Category.PHONE), com.programmersbox.cdm.core.model.Platform.ANDROID)
        val set = com.programmersbox.cdm.core.allocator.SetRequest(listOf(phone, phone))
        emit(AllocatorEvent.Queued(t0, app, Wants(emptySet()), set))
        assertEquals(listOf("Waiting for 2 × Android phone"), sent.map { it.title })
    }

    @Test
    fun waitText() {
        assertEquals("35 s", Notifier.waitText(35.seconds))
        assertEquals("2 min", Notifier.waitText(120.seconds))
        assertEquals("4 min 12 s", Notifier.waitText(252.seconds))
    }
}
