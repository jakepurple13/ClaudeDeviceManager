package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AllocatorTimerTest {
    private val p = AllocDevice(DeviceKey("P"), "Pixel", Category.PHONE, false, listOf("p-usb"))
    private val w = AllocDevice(DeviceKey("W"), "Watch", Category.WATCH, false, listOf("w-usb"))
    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private var now = t0
    private val events = mutableListOf<AllocatorEvent>()
    private val watch = setOf(Category.WATCH)

    private fun alloc() = Allocator(clock = { now }, onEvent = { events += it }).also { it.setDevices(listOf(p, w)) }

    @Test fun idleTimeoutReleasesAfter15Min() {
        val a = alloc()
        a.tryGrab(h1, watch)
        now = t0 + 14.minutes + 59.seconds; a.tick()
        assertIs<HolderStatus.Holding>(a.statusOf(h1))
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertEquals(HolderStatus.Released(ReleaseReason.IDLE_TIMEOUT, w.key), a.statusOf(h1))
        assertTrue(events.contains(AllocatorEvent.Released(now, h1, w.key, ReleaseReason.IDLE_TIMEOUT)))
    }

    @Test fun editShortensTimer() {
        val a = alloc()
        a.tryGrab(h1, watch)
        now = t0 + 1.minutes; a.recordEdit(h1)
        now = t0 + 5.minutes + 1.seconds; a.tick()
        assertEquals(HolderStatus.Released(ReleaseReason.EDIT_TIMEOUT, w.key), a.statusOf(h1))
    }

    @Test fun deviceActivityClearsEditing() {
        val a = alloc()
        a.tryGrab(h1, watch)
        now = t0 + 1.minutes; a.recordEdit(h1)
        now = t0 + 4.minutes; a.recordDeviceActivity(h1)
        now = t0 + 8.minutes; a.tick()
        assertIs<HolderStatus.Holding>(a.statusOf(h1))
    }

    @Test fun offlineDeviceStaysHeld() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.setDevices(listOf(p))
        assertIs<HolderStatus.Holding>(a.statusOf(h1))
        assertEquals(null, a.serialFor(h1))
    }

    @Test fun regrabWhenFree() {
        val a = alloc()
        a.tryGrab(h1, watch)
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertEquals(GrabResult.Granted(w, "w-usb"), a.tryRegrab(h1))
        assertIs<HolderStatus.Holding>(a.statusOf(h1))
    }

    @Test fun regrabFailsWhenTaken() {
        val a = alloc()
        val h2 = h2
        a.tryGrab(h1, watch); a.enqueue(h2, watch)
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertEquals(GrabResult.HeldByOther(w, h2), a.tryRegrab(h1))
    }

    @Test fun regrabRespectsDontUseAndOffline() {
        val a = alloc()
        a.tryGrab(h1, watch)
        now = t0 + 16.minutes; a.tick()
        a.setDontUse(w.key, true, releaseNow = false)
        assertEquals(GrabResult.DontUse, a.tryRegrab(h1))
        a.setDontUse(w.key, false, releaseNow = false)
        a.setDevices(listOf(p, w.copy(connectedSerials = emptyList())))
        assertEquals(GrabResult.NoneFree, a.tryRegrab(h1))
    }

    @Test fun regrabOnlyAfterTimeout() {
        val a = alloc()
        a.tryGrab(h1, watch)
        assertEquals(GrabResult.NoneFree, a.tryRegrab(h1))
    }

    @Test fun queueGraceExpires() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.enqueue(h2, watch); a.attachWaiter(h2)
        a.detachWaiter(h2)
        now = t0 + 1.minutes + 59.seconds; a.tick()
        assertEquals(HolderStatus.Queued(1, watch), a.statusOf(h2))
        now = t0 + 2.minutes + 1.seconds
        a.recordDeviceActivity(h1); a.tick()
        assertEquals(HolderStatus.None, a.statusOf(h2))
        assertTrue(events.contains(AllocatorEvent.LeftQueue(now, h2)))
    }

    @Test fun reattachKeepsQueueSpot() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.enqueue(h2, watch)
        a.detachWaiter(h2)
        now = t0 + 1.minutes; a.attachWaiter(h2)
        now = t0 + 10.minutes; a.recordDeviceActivity(h1); a.tick()
        assertEquals(HolderStatus.Queued(1, watch), a.statusOf(h2))
    }

    @Test fun neverDetachedStaysQueued() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.enqueue(h2, watch)
        now = t0 + 10.minutes; a.recordDeviceActivity(h1); a.tick()
        assertEquals(HolderStatus.Queued(1, watch), a.statusOf(h2))
    }

    @Test fun timeoutPassesDeviceToQueue() {
        val a = alloc()
        a.tryGrab(h1, watch); a.enqueue(h2, watch)
        now = t0 + 15.minutes + 1.milliseconds; a.tick()
        assertIs<HolderStatus.Holding>(a.statusOf(h2))
    }
}
