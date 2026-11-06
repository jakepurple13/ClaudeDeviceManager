package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AllocatorPlatformTest {
    private val p = AllocDevice(DeviceKey("P"), "Pixel", Category.PHONE, false, listOf("p-usb"))
    private val p2 = AllocDevice(DeviceKey("P2"), "Pixel 2", Category.PHONE, false, listOf("p2-usb"))
    private val w = AllocDevice(DeviceKey("W"), "Watch", Category.WATCH, false, listOf("w-usb"))
    private val i = AllocDevice(DeviceKey("I-UDID"), "iPhone", Category.PHONE, false, listOf("I-UDID"), platform = Platform.IOS)
    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val h3 = HolderId("s3")
    private val h4 = HolderId("s4")
    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private var now = t0
    private val events = mutableListOf<AllocatorEvent>()

    private val androidPhone = Wants(setOf(Category.PHONE), platform = Platform.ANDROID)
    private val iosPhone = Wants(setOf(Category.PHONE), platform = Platform.IOS)
    private val watch = Wants(setOf(Category.WATCH))

    private fun alloc(vararg devices: AllocDevice = arrayOf(p, i)) =
        Allocator(clock = { now }, onEvent = { events += it }).also { it.setDevices(devices.toList()) }

    @Test fun holdsOneAndroidAndOneIosAtOnce() {
        val a = alloc()
        val first = assertIs<GrabResult.Granted>(a.tryGrab(h1, Wants.ANY))
        val second = assertIs<GrabResult.Granted>(a.tryGrab(h1, Wants.ANY.copy(platform = Platform.IOS)))
        assertEquals(setOf(Platform.ANDROID, Platform.IOS), setOf(first.device.platform, second.device.platform))
        assertEquals(2, a.holdingsOf(h1).size)
        assertEquals(mapOf(p.key to h1, i.key to h1), a.snapshot().holds)
        assertEquals("p-usb", a.serialFor(h1, Platform.ANDROID))
        assertEquals("I-UDID", a.serialFor(h1, Platform.IOS))
    }

    @Test fun unflaggedGrabReturnsExistingHold() {
        val a = alloc()
        a.tryGrab(h1, androidPhone)
        assertEquals(GrabResult.Granted(p, "p-usb"), a.tryGrab(h1, Wants.ANY))
        assertEquals(1, a.holdingsOf(h1).size)
        assertEquals(null, a.snapshot().holds[i.key])
    }

    @Test fun secondGrabSamePlatformReturnsExistingHold() {
        val a = alloc(p, p2, i)
        val first = a.tryGrab(h1, androidPhone)
        assertEquals(GrabResult.Granted(p, "p-usb"), first)
        assertEquals(first, a.tryGrab(h1, androidPhone))
        assertEquals(1, a.holdingsOf(h1).size)
        assertEquals(1, a.snapshot().holds.size)
    }

    @Test fun specificRefusedWhenPlatformAlreadyHeld() {
        val a = alloc(p, p2, i)
        a.tryGrab(h1, androidPhone)
        assertEquals(GrabResult.NoneFree, a.tryGrab(h1, Wants(emptySet()), specific = p2.key))
        assertEquals(GrabResult.Granted(i, "I-UDID"), a.tryGrab(h1, Wants(emptySet()), specific = i.key))
        assertEquals(2, a.holdingsOf(h1).size)
    }

    @Test fun holdingPlatformMatchesGrantedDevice() {
        val a = alloc()
        a.tryGrab(h1, iosPhone)
        assertEquals(Platform.IOS, assertIs<HolderStatus.Holding>(a.statusOf(h1)).platform)
        a.tryGrab(h2, androidPhone)
        assertEquals(Platform.ANDROID, assertIs<HolderStatus.Holding>(a.statusOf(h2)).platform)
    }

    @Test fun releaseByPlatformKeepsTheOther() {
        val a = alloc()
        a.tryGrab(h1, androidPhone); a.tryGrab(h1, iosPhone)
        a.release(h1, ReleaseReason.SELF_RELEASE, Platform.IOS)
        assertEquals(mapOf(p.key to h1), a.snapshot().holds)
        assertEquals(listOf(Platform.ANDROID), a.holdingsOf(h1).map { it.platform })
        assertEquals(p.key, assertIs<HolderStatus.Holding>(a.statusOf(h1)).device)
        assertEquals(null, a.serialFor(h1, Platform.IOS))
    }

    @Test fun releaseAllFreesBoth() {
        val a = alloc()
        a.tryGrab(h1, androidPhone); a.tryGrab(h1, iosPhone)
        a.release(h1, ReleaseReason.SELF_RELEASE)
        assertTrue(a.snapshot().holds.isEmpty())
        assertTrue(a.holdingsOf(h1).isEmpty())
        assertIs<HolderStatus.Released>(a.statusOf(h1))
    }

    @Test fun idleTimeoutIsPerHold() {
        val a = alloc()
        a.tryGrab(h1, androidPhone); a.tryGrab(h1, iosPhone)
        now = t0 + 10.minutes; a.recordDeviceActivity(h1, Platform.ANDROID)
        now = t0 + 16.minutes; a.tick()
        assertEquals(mapOf(p.key to h1), a.snapshot().holds)
        assertTrue(events.contains(AllocatorEvent.Released(now, h1, i.key, ReleaseReason.IDLE_TIMEOUT)))
        assertEquals(p.key, assertIs<HolderStatus.Holding>(a.statusOf(h1)).device)
        // The released iOS hold can be regrabbed on its own.
        assertEquals(GrabResult.Granted(i, "I-UDID"), a.tryRegrab(h1, Platform.IOS))
        assertEquals(GrabResult.NoneFree, a.tryRegrab(h1, Platform.ANDROID))
    }

    @Test fun editMarksBothHolds() {
        val a = alloc()
        a.tryGrab(h1, androidPhone); a.tryGrab(h1, iosPhone)
        now = t0 + 1.minutes; a.recordEdit(h1)
        assertTrue(a.holdingsOf(h1).all { it.editing })
        // Activity on one platform clears editing on that hold only.
        now = t0 + 2.minutes; a.recordDeviceActivity(h1, Platform.ANDROID)
        assertEquals(mapOf(Platform.ANDROID to false, Platform.IOS to true), a.holdingsOf(h1).associate { it.platform to it.editing })
        now = t0 + 5.minutes + 1.seconds; a.tick()
        assertTrue(events.contains(AllocatorEvent.Released(now, h1, i.key, ReleaseReason.EDIT_TIMEOUT)))
        assertEquals(mapOf(p.key to h1), a.snapshot().holds)
    }

    @Test fun queuedHolderWithAndroidHoldGetsIosOnRelease() {
        val a = alloc()
        a.tryGrab(h1, androidPhone)
        a.tryGrab(h2, iosPhone)
        assertEquals(1, a.enqueue(h1, iosPhone))
        a.release(h2, ReleaseReason.SELF_RELEASE)
        assertEquals(mapOf(p.key to h1, i.key to h1), a.snapshot().holds)
        assertEquals(2, a.holdingsOf(h1).size)
        assertTrue(a.snapshot().queue.isEmpty())
    }

    @Test fun passToQueueSkipsHolderAlreadyHoldingPlatform() {
        val a = alloc(p, p2, w, i)
        a.tryGrab(h1, watch)        // W (Android)
        a.tryGrab(h2, androidPhone) // P
        a.tryGrab(h3, androidPhone) // P2
        a.tryGrab(h4, iosPhone)     // I
        val phone = Wants(setOf(Category.PHONE))
        assertEquals(1, a.enqueue(h1, phone)) // holds no phone, so it may queue
        val h5 = HolderId("s5")
        assertEquals(2, a.enqueue(h5, androidPhone))
        a.release(h3, ReleaseReason.SELF_RELEASE)
        assertEquals(h5, a.snapshot().holds[p2.key])
        assertEquals(HolderStatus.Queued(1, phone), a.statusOf(h1))
        assertEquals(listOf(w.key), a.holdingsOf(h1).map { it.device })
    }

    @Test fun grantOnOtherPlatformKeepsQueueSpot() {
        val a = alloc(p, p2, i)
        a.tryGrab(h2, androidPhone) // P
        a.tryGrab(h3, iosPhone)     // I
        assertEquals(1, a.enqueue(h1, iosPhone))
        a.handOff(p.key, h1)
        assertEquals(h1, a.snapshot().holds[p.key])
        assertEquals(HolderStatus.Queued(1, iosPhone), a.statusOf(h1))
        a.release(h3, ReleaseReason.SELF_RELEASE)
        assertEquals(setOf(p.key, i.key), a.holdingsOf(h1).map { it.device }.toSet())
        assertTrue(a.snapshot().queue.isEmpty())
    }

    @Test fun unflaggedEnqueueRefusedWhenHoldingFittingDevice() {
        val a = alloc()
        a.tryGrab(h1, androidPhone)
        assertEquals(0, a.enqueue(h1, Wants.ANY))
        assertEquals(p.key, assertIs<HolderStatus.Holding>(a.statusOf(h1)).device)
        assertTrue(a.snapshot().queue.isEmpty())
    }

    @Test fun flaggedEnqueueRefusedForHeldPlatform() {
        val a = alloc(p, p2, i)
        a.tryGrab(h1, androidPhone)
        assertEquals(0, a.enqueue(h1, Wants(setOf(Category.WATCH), platform = Platform.ANDROID)))
        assertEquals(1, a.enqueue(h1, iosPhone))
    }

    @Test fun handOffRefusedOnlyForSamePlatform() {
        val a = alloc(p, p2, i)
        a.tryGrab(h1, androidPhone) // P
        a.tryGrab(h2, androidPhone) // P2
        a.tryGrab(h3, iosPhone)     // I
        a.handOff(p2.key, h1)
        assertEquals(h2, a.snapshot().holds[p2.key])
        a.handOff(i.key, h1)
        assertEquals(h1, a.snapshot().holds[i.key])
        assertEquals(2, a.holdingsOf(h1).size)
        assertEquals(HolderStatus.Released(ReleaseReason.USER_HANDOFF, i.key), a.statusOf(h3))
    }

    @Test fun snapshotStatusesUnchangedForSingleHold() {
        val a = Allocator(clock = { now }, blocked = setOf(h4)).also { it.setDevices(listOf(p, w)) }
        a.tryGrab(h1, watch)
        a.enqueue(h2, watch)
        a.tryGrab(h3, androidPhone); a.release(h3, ReleaseReason.SELF_RELEASE)
        val snap = a.snapshot()
        assertEquals(
            mapOf(
                h1 to HolderStatus.Holding(w.key, t0, t0, editing = false, platform = Platform.ANDROID),
                h2 to HolderStatus.Queued(1, watch),
                h3 to HolderStatus.Released(ReleaseReason.SELF_RELEASE, p.key),
                h4 to HolderStatus.Blocked,
            ),
            snap.statuses,
        )
        assertEquals(mapOf(h1 to listOf(HolderStatus.Holding(w.key, t0, t0, editing = false, platform = Platform.ANDROID))), snap.holdings)
    }
}
