package com.jacobrein.cdm.core.allocator

import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class AllocatorQueueTest {
    private val p = AllocDevice(DeviceKey("P"), "Pixel", Category.PHONE, false, listOf("p-usb", "p-wifi"))
    private val w = AllocDevice(DeviceKey("W"), "Watch", Category.WATCH, false, listOf("w-usb"))
    private val h1 = HolderId("s1", "m1")
    private val h2 = HolderId("s2")
    private val h3 = HolderId("s3")
    private val h4 = HolderId("s4")
    private val events = mutableListOf<AllocatorEvent>()
    private val now = Instant.fromEpochMilliseconds(1_000)

    private fun alloc(blocked: Set<HolderId> = emptySet()) =
        Allocator(clock = { now }, blocked = blocked, onEvent = { events += it }).also { it.setDevices(listOf(p, w)) }

    private val watch = setOf(Category.WATCH)
    private val phone = setOf(Category.PHONE)

    @Test fun grantsFreeDeviceOfWantedCategory() {
        val a = alloc()
        assertEquals(GrabResult.Granted(w, "w-usb"), a.tryGrab(h1, watch))
        assertEquals(GrabResult.HeldByOther(w, h1), a.tryGrab(h2, watch, specific = w.key))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h2, watch))
    }

    @Test fun anyMatchesAllCategories() {
        assertIs<GrabResult.Granted>(alloc().tryGrab(h1, setOf(Category.ANY)))
    }

    @Test fun grabIsIdempotentForHolder() {
        val a = alloc()
        val first = a.tryGrab(h1, watch)
        assertEquals(first, a.tryGrab(h1, watch))
        assertEquals(1, a.snapshot().holds.size)
    }

    @Test fun releasePassesToFirstFittingQueued() {
        val a = alloc()
        a.tryGrab(h1, watch); a.tryGrab(h3, phone)
        a.enqueue(h2, phone); a.enqueue(h4, watch)
        a.release(h1, ReleaseReason.SELF_RELEASE)
        assertIs<HolderStatus.Holding>(a.statusOf(h4))
        assertEquals(h4, a.snapshot().holds[w.key])
        assertEquals(HolderStatus.Queued(1, phone), a.statusOf(h2))
    }

    @Test fun enqueueIsIdempotent() {
        val a = alloc()
        assertEquals(1, a.enqueue(h1, watch))
        assertEquals(1, a.enqueue(h1, watch))
        assertEquals(1, a.snapshot().queue.size)
    }

    @Test fun blockedHolderCannotGrab() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.releaseDevice(w.key, block = true)
        assertEquals(HolderStatus.Blocked, a.statusOf(h1))
        assertEquals(GrabResult.Blocked, a.tryGrab(h1, watch))
        a.unblock(h1)
        assertIs<GrabResult.Granted>(a.tryGrab(h1, watch))
    }

    @Test fun dontUseNeverGranted() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.enqueue(h2, watch)
        a.setDontUse(w.key, true, releaseNow = false)
        assertEquals(h1, a.snapshot().holds[w.key])
        a.release(h1, ReleaseReason.SELF_RELEASE)
        assertTrue(a.snapshot().holds.isEmpty())
        assertEquals(HolderStatus.Queued(1, watch), a.statusOf(h2))
        assertEquals(GrabResult.DontUse, a.tryGrab(h3, watch, specific = w.key))
    }

    @Test fun dontUseReleaseNow() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.setDontUse(w.key, true, releaseNow = true)
        assertEquals(HolderStatus.Released(ReleaseReason.DONT_USE, w.key), a.statusOf(h1))
    }

    @Test fun handOffGivesDeviceToChosenHolder() {
        val a = alloc()
        a.tryGrab(h1, watch); a.enqueue(h2, watch)
        a.handOff(w.key, h2)
        assertIs<HolderStatus.Holding>(a.statusOf(h2))
        assertEquals(HolderStatus.Released(ReleaseReason.USER_HANDOFF, w.key), a.statusOf(h1))
        assertTrue(a.snapshot().queue.isEmpty())
    }

    @Test fun moveInQueueReorders() {
        val a = alloc()
        a.enqueue(h2, watch); a.enqueue(h3, watch); a.enqueue(h4, watch)
        a.moveInQueue(h4, 0)
        assertEquals(listOf(h4, h2, h3), a.snapshot().queue)
    }

    @Test fun endSessionReleasesAllAgents() {
        val a = alloc()
        val m = HolderId("s1"); val sub = HolderId("s1", "a1")
        a.tryGrab(m, phone); a.tryGrab(sub, watch)
        a.endSession("s1")
        assertTrue(a.snapshot().holds.isEmpty())
    }

    @Test fun serialForNullWhenOffline() {
        val a = alloc()
        a.tryGrab(h1, watch)
        assertEquals("w-usb", a.serialFor(h1))
        a.setDevices(listOf(p))
        assertEquals(null, a.serialFor(h1))
        assertEquals(h1, a.snapshot().holds[w.key])
    }

    @Test fun eventsEmitted() {
        val a = alloc()
        a.tryGrab(h1, watch)
        a.enqueue(h2, phone)
        a.releaseDevice(w.key, block = true)
        assertEquals(
            listOf(
                AllocatorEvent.Granted(now, h1, w.key),
                AllocatorEvent.Queued(now, h2, phone),
                AllocatorEvent.Released(now, h1, w.key, ReleaseReason.USER_RELEASE),
                AllocatorEvent.Blocked(now, h1),
            ),
            events,
        )
    }
}
