package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AllocatorSetTest {
    private val a1 = AllocDevice(DeviceKey("A1"), "Pixel 1", Category.PHONE, false, listOf("a1-usb"))
    private val a2 = AllocDevice(DeviceKey("A2"), "Pixel 2", Category.PHONE, false, listOf("a2-usb"))
    private val i1 = AllocDevice(DeviceKey("I1"), "iPhone", Category.PHONE, false, listOf("I1"), Platform.IOS)
    private val watchKey = DeviceKey("W-UDID")
    private val phoneKey = DeviceKey("I-UDID")
    private val pw = AllocDevice(watchKey, "Apple Watch", Category.WATCH, false, listOf("W-UDID"), Platform.IOS, partner = phoneKey)
    private val pi = AllocDevice(phoneKey, "Paired iPhone", Category.PHONE, false, listOf("I-UDID"), Platform.IOS, partner = watchKey)

    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val h3 = HolderId("s3")
    private val now = Instant.fromEpochMilliseconds(1_000_000)

    private val androidPhone = Wants(setOf(Category.PHONE), platform = Platform.ANDROID)
    private val iosPhone = Wants(setOf(Category.PHONE), platform = Platform.IOS)
    private val iosWatch = Wants(setOf(Category.WATCH), platform = Platform.IOS)
    private val threeReq = SetRequest(listOf(androidPhone, androidPhone, iosPhone))
    private val twoAndroid = SetRequest(listOf(androidPhone, androidPhone))

    private val events = mutableListOf<AllocatorEvent>()

    private fun alloc(vararg devices: AllocDevice = arrayOf(a1, a2, i1)) =
        Allocator(clock = { now }, onEvent = { events += it }).also { it.setDevices(devices.toList()) }

    @Test fun grantsWholeSetAtOnce() {
        val a = alloc()
        val r = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq))
        assertEquals(listOf("android1", "android2", "ios1"), r.set.members.map { it.name })
        assertEquals(listOf(a1.key, a2.key, i1.key), r.set.members.map { it.device })
        assertEquals(listOf(Platform.ANDROID, Platform.ANDROID, Platform.IOS), r.set.members.map { it.platform })
        assertEquals(mapOf(a1.key to h1, a2.key to h1, i1.key to h1), a.snapshot().holds)
        assertEquals(HolderStatus.HoldingSet(r.set), a.statusOf(h1))
        assertEquals(r.set, a.setOf(h1))
        assertEquals(mapOf(h1 to r.set), a.snapshot().sets)
        assertEquals(HolderStatus.HoldingSet(r.set), a.snapshot().statuses[h1])
        assertEquals(3, events.filterIsInstance<AllocatorEvent.Granted>().count { it.holder == h1 })
    }

    @Test fun neverGrantsPartially() {
        val a = alloc()
        a.tryGrab(h2, androidPhone)
        assertEquals(GrabSetResult.NoneFree, a.tryGrabSet(h1, threeReq))
        assertTrue(h1 !in a.snapshot().holds.values)
        assertNull(a.setOf(h1))
        assertEquals(HolderStatus.None, a.statusOf(h1))
    }

    @Test fun queuedSetGrantedWhenLastDeviceFrees() {
        val a = alloc()
        a.tryGrab(h2, androidPhone)
        assertEquals(GrabSetResult.NoneFree, a.tryGrabSet(h1, threeReq))
        assertEquals(1, a.enqueueSet(h1, threeReq))
        val q = assertIs<HolderStatus.Queued>(a.statusOf(h1))
        assertEquals(threeReq, q.set)
        a.release(h2, ReleaseReason.SELF_RELEASE)
        val held = assertIs<HolderStatus.HoldingSet>(a.statusOf(h1))
        assertEquals(listOf("android1", "android2", "ios1"), held.set.members.map { it.name })
        assertEquals(mapOf(a1.key to h1, a2.key to h1, i1.key to h1), a.snapshot().holds)
        assertTrue(a.snapshot().queue.isEmpty())
    }

    @Test fun queuedSingleBehindSetStillServed() {
        // A set that no listed devices could meet reserves nothing, so it does not stop a single behind it.
        val a = alloc(a1, a2)
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, androidPhone)
        assertEquals(1, a.enqueueSet(h1, SetRequest(listOf(androidPhone, androidPhone, androidPhone))))
        assertEquals(2, a.enqueue(HolderId("s4"), androidPhone))
        a.release(h2, ReleaseReason.SELF_RELEASE)
        assertEquals(HolderId("s4"), a.snapshot().holds[a1.key])
    }

    @Test fun feasibleFalseWhenTooFewListed() {
        val a = alloc(a1, i1)
        assertFalse(a.feasible(threeReq))
        assertEquals(GrabSetResult.Impossible, a.tryGrabSet(h1, threeReq))
        // Holds and connection are ignored; don't-use is not.
        val b = alloc(a1, a2.copy(connectedSerials = emptyList()), i1)
        b.tryGrab(h2, androidPhone)
        assertTrue(b.feasible(threeReq))
        b.setDontUse(a2.key, true, releaseNow = false)
        assertFalse(b.feasible(threeReq))
    }

    @Test fun releasePlatformKeepsOtherMembersAndNames() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        events.clear()
        a.release(h1, ReleaseReason.SELF_RELEASE, Platform.ANDROID)
        val set = a.setOf(h1)!!
        assertEquals(listOf("ios1"), set.members.map { it.name })
        assertEquals(mapOf(i1.key to h1), a.snapshot().holds)
        assertEquals(2, events.filterIsInstance<AllocatorEvent.Released>().size)
        a.release(h1, ReleaseReason.SELF_RELEASE)
        assertNull(a.setOf(h1))
        assertTrue(a.snapshot().holds.isEmpty())
        assertIs<HolderStatus.Released>(a.statusOf(h1))
    }

    @Test fun userReleaseOfOneMemberReleasesSet() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        a.enqueue(h2, androidPhone)
        events.clear()
        a.releaseDevice(a2.key, block = false)
        assertNull(a.setOf(h1))
        assertEquals(3, events.filterIsInstance<AllocatorEvent.Released>().count { it.reason == ReleaseReason.USER_RELEASE })
        // The freed devices go through the queue.
        assertEquals(mapOf(a1.key to h2), a.snapshot().holds)
    }

    @Test fun userReleaseWithBlockReleasesSetAndBlocks() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        a.releaseDevice(i1.key, block = true)
        assertNull(a.setOf(h1))
        assertTrue(a.snapshot().holds.isEmpty())
        assertEquals(HolderStatus.Blocked, a.statusOf(h1))
    }

    @Test fun dontUseReleasesSet() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        a.enqueue(h2, iosPhone)
        a.setDontUse(a1.key, true, releaseNow = true)
        assertNull(a.setOf(h1))
        assertEquals(mapOf(i1.key to h2), a.snapshot().holds)
    }

    @Test fun handOffIgnoresSetMember() {
        val a = alloc()
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq)).set
        a.handOff(a1.key, h2)
        assertEquals(set, a.setOf(h1))
        assertEquals(mapOf(a1.key to h1, a2.key to h1, i1.key to h1), a.snapshot().holds)
    }

    @Test fun pairedIphoneInSetHoldsWatchToo() {
        val a = alloc(a1, pw, pi)
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, SetRequest(listOf(androidPhone, iosPhone)))).set
        assertEquals(SetMember("ios1", Platform.IOS, phoneKey, watchKey), set.members[1])
        assertEquals(mapOf(a1.key to h1, phoneKey to h1, watchKey to h1), a.snapshot().holds)
        a.release(h1, ReleaseReason.SELF_RELEASE, Platform.IOS)
        assertEquals(mapOf(a1.key to h1), a.snapshot().holds)
    }

    @Test fun pairedWatchSpecMakesWatchTheMember() {
        val a = alloc(a1, pi, pw)
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, SetRequest(listOf(androidPhone, iosWatch)))).set
        assertEquals(SetMember("ios1", Platform.IOS, watchKey, phoneKey), set.members[1])
        // The pair counts once: a phone and a watch member cannot both come from it.
        assertEquals(GrabSetResult.Impossible, a.tryGrabSet(h2, SetRequest(listOf(iosPhone, iosWatch))))
    }

    @Test fun holderHasSinglesOrSetNeverBoth() {
        val a = alloc()
        a.tryGrab(h1, iosPhone)
        assertEquals(GrabSetResult.NoneFree, a.tryGrabSet(h1, twoAndroid))
        assertEquals(0, a.enqueueSet(h1, twoAndroid))
        a.tryGrabSet(h2, twoAndroid)
        assertEquals(GrabResult.NoneFree, a.tryGrab(h2, iosPhone))
        assertEquals(0, a.enqueue(h2, iosPhone))
    }

    @Test fun endHolderClearsSet() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        a.endHolder(h1, ReleaseReason.SUBAGENT_STOP)
        assertNull(a.setOf(h1))
        assertTrue(a.snapshot().holds.isEmpty())
        a.tryGrabSet(h2, threeReq)
        a.endSession("s2")
        assertTrue(a.snapshot().holds.isEmpty())
        assertTrue(a.snapshot().sets.isEmpty())
    }

    @Test fun removeFromQueueDropsSetEntry() {
        val a = alloc()
        a.tryGrab(h2, androidPhone)
        a.enqueueSet(h1, threeReq)
        a.removeFromQueue(h1)
        a.release(h2, ReleaseReason.SELF_RELEASE)
        assertNull(a.setOf(h1))
        assertEquals(HolderStatus.None, a.statusOf(h1))
    }

    private val h4 = HolderId("s4")
    private val h5 = HolderId("s5")
    private val aw = AllocDevice(DeviceKey("AW"), "Pixel Watch", Category.WATCH, false, listOf("aw-usb"))
    private val androidWatch = Wants(setOf(Category.WATCH), platform = Platform.ANDROID)

    @Test fun firstSetReservesFreedDevices() {
        val a = alloc(a1, a2)
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h4, androidPhone)
        assertEquals(1, a.enqueueSet(h1, twoAndroid))
        assertTrue(a.snapshot().reserved.isEmpty())
        a.release(h2, ReleaseReason.SELF_RELEASE)
        assertEquals(mapOf(a1.key to h1), a.snapshot().reserved)
        assertEquals(listOf(a1.key), a.reservedFor(h1))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h3, androidPhone))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h3, androidPhone, specific = a1.key))
        assertIs<HolderStatus.Queued>(a.statusOf(h1))
        assertTrue(a1.key !in a.snapshot().holds)
    }

    @Test fun singleBehindSetCanTakeDeviceSetDoesNotNeed() {
        val a = alloc(a1, a2, aw)
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, androidWatch)
        a.enqueueSet(h1, twoAndroid)
        assertEquals(mapOf(a2.key to h1), a.snapshot().reserved)
        assertEquals(2, a.enqueue(h4, androidWatch))
        a.release(h3, ReleaseReason.SELF_RELEASE)
        assertEquals(h4, a.snapshot().holds[aw.key])
        assertEquals(mapOf(a2.key to h1), a.snapshot().reserved)
    }

    @Test fun secondSetDoesNotReserve() {
        val a = alloc()
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, iosPhone)
        a.enqueueSet(h1, twoAndroid)
        a.enqueueSet(h4, SetRequest(listOf(iosPhone, androidPhone)))
        assertEquals(mapOf(a2.key to h1), a.snapshot().reserved)
        a.release(h3, ReleaseReason.SELF_RELEASE)
        // The second set can't be met (its Android phone is reserved), and it reserves nothing.
        assertEquals(mapOf(a2.key to h1), a.snapshot().reserved)
        assertTrue(a.reservedFor(h4).isEmpty())
        assertIs<HolderStatus.Queued>(a.statusOf(h4))
        assertIs<GrabResult.Granted>(a.tryGrab(h5, iosPhone))
        assertEquals(h5, a.snapshot().holds[i1.key])
    }

    @Test fun reservationDroppedWhenSetRemoved() {
        val a = alloc(a1, a2)
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, androidPhone)
        a.enqueueSet(h1, twoAndroid)
        assertEquals(2, a.enqueue(h4, androidPhone))
        a.release(h2, ReleaseReason.SELF_RELEASE)
        assertEquals(mapOf(a1.key to h1), a.snapshot().reserved)
        assertTrue(a1.key !in a.snapshot().holds)
        a.removeFromQueue(h1)
        assertTrue(a.snapshot().reserved.isEmpty())
        assertEquals(h4, a.snapshot().holds[a1.key])
    }

    @Test fun reservedDeviceOfflineLeavesReservationKeepsPlace() {
        val a = alloc(a1, a2)
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, androidPhone)
        a.enqueueSet(h1, twoAndroid)
        a.enqueue(h4, androidPhone)
        a.release(h2, ReleaseReason.SELF_RELEASE)
        assertEquals(mapOf(a1.key to h1), a.snapshot().reserved)
        a.setDevices(listOf(a1.copy(connectedSerials = emptyList()), a2))
        assertTrue(a.snapshot().reserved.isEmpty())
        assertEquals(1, assertIs<HolderStatus.Queued>(a.statusOf(h1)).position)
        // Back online: reserved again, not handed to the single behind.
        a.setDevices(listOf(a1, a2))
        assertEquals(mapOf(a1.key to h1), a.snapshot().reserved)
        assertTrue(a1.key !in a.snapshot().holds)
        // Don't-use leaves the reservation the same way.
        a.setDontUse(a1.key, true, releaseNow = false)
        assertTrue(a.snapshot().reserved.isEmpty())
        assertEquals(1, assertIs<HolderStatus.Queued>(a.statusOf(h1)).position)
    }

    @Test fun setGrantedFromReservedPlusFreed() {
        val a = alloc(a1, a2)
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, androidPhone)
        a.enqueueSet(h1, twoAndroid)
        a.enqueue(h4, androidPhone)
        a.release(h2, ReleaseReason.SELF_RELEASE)
        a.release(h3, ReleaseReason.SELF_RELEASE)
        val held = assertIs<HolderStatus.HoldingSet>(a.statusOf(h1))
        assertEquals(setOf(a1.key, a2.key), held.set.members.map { it.device }.toSet())
        assertTrue(a.snapshot().reserved.isEmpty())
        assertTrue(a.reservedFor(h1).isEmpty())
        assertEquals(1, assertIs<HolderStatus.Queued>(a.statusOf(h4)).position)
    }

    @Test fun reservedPairHeldAsUnit() {
        val a = alloc(a1, pw, pi)
        a.tryGrab(h2, androidPhone)
        a.enqueueSet(h1, SetRequest(listOf(androidPhone, iosPhone)))
        assertEquals(mapOf(phoneKey to h1, watchKey to h1), a.snapshot().reserved)
        assertEquals(GrabResult.NoneFree, a.tryGrab(h3, iosWatch))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h3, iosPhone))
        a.release(h2, ReleaseReason.SELF_RELEASE)
        val held = assertIs<HolderStatus.HoldingSet>(a.statusOf(h1))
        assertEquals(SetMember("ios1", Platform.IOS, phoneKey, watchKey), held.set.members[1])
        assertTrue(a.snapshot().reserved.isEmpty())
    }

    @Test fun movingAnotherSetFirstMovesTheReservation() {
        val a = alloc()
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, iosPhone)
        a.enqueueSet(h1, twoAndroid)
        a.enqueueSet(h4, SetRequest(listOf(androidPhone, iosPhone)))
        assertEquals(mapOf(a2.key to h1), a.snapshot().reserved)
        a.moveInQueue(h4, 0)
        assertEquals(mapOf(a2.key to h4), a.snapshot().reserved)
    }

    @Test fun queueGraceExpiryDropsReservation() {
        var t = now
        val a = Allocator(clock = { t }).also { it.setDevices(listOf(a1, a2)) }
        a.tryGrab(h2, androidPhone)
        a.tryGrab(h3, androidPhone)
        a.enqueueSet(h1, twoAndroid)
        a.enqueue(h4, androidPhone)
        a.release(h2, ReleaseReason.SELF_RELEASE)
        a.detachWaiter(h1)
        t = now + 3.minutes
        a.tick()
        assertTrue(a.snapshot().reserved.isEmpty())
        assertEquals(h4, a.snapshot().holds[a1.key])
    }
}
