package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AllocatorSetTimerTest {
    private val a1 = AllocDevice(DeviceKey("A1"), "Pixel 1", Category.PHONE, false, listOf("a1-usb"))
    private val a2 = AllocDevice(DeviceKey("A2"), "Pixel 2", Category.PHONE, false, listOf("a2-usb"))
    private val a3 = AllocDevice(DeviceKey("A3"), "Pixel 3", Category.PHONE, false, listOf("a3-usb"))
    private val i1 = AllocDevice(DeviceKey("I1"), "iPhone", Category.PHONE, false, listOf("I1"), Platform.IOS)
    private val watchKey = DeviceKey("W-UDID")
    private val phoneKey = DeviceKey("I-UDID")
    private val pw = AllocDevice(watchKey, "Apple Watch", Category.WATCH, false, listOf("W-UDID"), Platform.IOS, partner = phoneKey)
    private val pi = AllocDevice(phoneKey, "Paired iPhone", Category.PHONE, false, listOf("I-UDID"), Platform.IOS, partner = watchKey)

    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val h3 = HolderId("s3")
    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private var now = t0

    private val androidPhone = Wants(setOf(Category.PHONE), platform = Platform.ANDROID)
    private val iosPhone = Wants(setOf(Category.PHONE), platform = Platform.IOS)
    private val threeReq = SetRequest(listOf(androidPhone, androidPhone, iosPhone))
    private val twoAndroid = SetRequest(listOf(androidPhone, androidPhone))

    private val events = mutableListOf<AllocatorEvent>()

    private fun alloc(vararg devices: AllocDevice = arrayOf(a1, a2, i1)) =
        Allocator(clock = { now }, onEvent = { events += it }).also { it.setDevices(devices.toList()) }

    private fun timedOut(a: Allocator): HeldSet {
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq)).set
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertNull(a.setOf(h1))
        return set
    }

    @Test fun activityOnAnyMemberKeepsWholeSet() {
        val a = alloc()
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq)).set
        now = t0 + 10.minutes; a.recordDeviceActivity(h1, Platform.IOS)
        now = t0 + 20.minutes; a.tick()
        assertEquals(set.members, a.setOf(h1)?.members)
        assertEquals(t0 + 10.minutes, a.setOf(h1)?.lastActivity)
        now = t0 + 24.minutes; a.recordSetActivity(h1)
        now = t0 + 39.minutes; a.tick()
        assertEquals(set.members, a.setOf(h1)?.members)
        assertEquals(mapOf(a1.key to h1, a2.key to h1, i1.key to h1), a.snapshot().holds)
        now = t0 + 39.minutes + 1.seconds; a.tick()
        assertNull(a.setOf(h1))
    }

    @Test fun idleTimeoutReleasesWholeSet() {
        val a = alloc()
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq)).set
        now = t0 + 14.minutes + 59.seconds; a.tick()
        assertEquals(set, a.setOf(h1))
        events.clear()
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertNull(a.setOf(h1))
        assertTrue(a.snapshot().holds.isEmpty())
        assertEquals(ReleasedSet(ReleaseReason.IDLE_TIMEOUT, set.members), a.releasedSetOf(h1))
        assertEquals(
            listOf(a1.key, a2.key, i1.key).map { AllocatorEvent.Released(now, h1, it, ReleaseReason.IDLE_TIMEOUT) },
            events.filterIsInstance<AllocatorEvent.Released>(),
        )
        val st = assertIs<HolderStatus.Released>(a.statusOf(h1))
        assertEquals(ReleaseReason.IDLE_TIMEOUT, st.reason)
    }

    @Test fun regrabRestoresSameDevicesAndNames() {
        val a = alloc()
        val set = timedOut(a)
        now = t0 + 20.minutes
        val regrabbed = assertIs<GrabSetResult.Granted>(a.tryRegrabSet(h1)).set
        assertEquals(set.members, regrabbed.members)
        assertEquals(now, regrabbed.lastActivity)
        assertEquals(regrabbed, a.setOf(h1))
        assertEquals(mapOf(a1.key to h1, a2.key to h1, i1.key to h1), a.snapshot().holds)
        assertNull(a.releasedSetOf(h1))
        // The record is used up: a second regrab has nothing to restore.
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
    }

    @Test fun regrabKeepsMemberDevicesOverOtherFreeOnes() {
        // a3 is listed first after the timeout, but the regrab takes exactly the former members.
        val a = alloc()
        val set = timedOut(a)
        a.setDevices(listOf(a3, a2, a1, i1))
        val regrabbed = assertIs<GrabSetResult.Granted>(a.tryRegrabSet(h1)).set
        assertEquals(set.members, regrabbed.members)
        assertTrue(a3.key !in a.snapshot().holds)
    }

    @Test fun regrabFailsIfOneMemberTaken() {
        val a = alloc()
        val set = timedOut(a)
        assertIs<GrabResult.Granted>(a.tryGrab(h2, androidPhone))
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
        assertEquals(mapOf(a1.key to h2), a.snapshot().holds)
        assertTrue(h1 !in a.snapshot().holds.values)
        assertNull(a.setOf(h1))
        assertEquals(ReleasedSet(ReleaseReason.IDLE_TIMEOUT, set.members), a.releasedSetOf(h1))
    }

    @Test fun regrabFailsIfMemberOfflineOrDontUse() {
        val a = alloc()
        timedOut(a)
        a.setDevices(listOf(a1, i1))
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
        a.setDevices(listOf(a1, a2, i1))
        a.setDontUse(i1.key, true, releaseNow = false)
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
        assertTrue(a.snapshot().holds.isEmpty())
        assertNotNull(a.releasedSetOf(h1))
    }

    @Test fun regrabDoesNotTakeDeviceReservedForAnotherSet() {
        val a = alloc(a1, a2, a3, i1)
        assertIs<GrabResult.Granted>(a.tryGrab(h3, androidPhone)) // a1
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq)).set // a2, a3, i1
        assertEquals(listOf(a2.key, a3.key, i1.key), set.members.map { it.device })
        val threeAndroid = SetRequest(listOf(androidPhone, androidPhone, androidPhone))
        assertEquals(1, a.enqueueSet(h2, threeAndroid))
        now = t0 + 15.minutes + 1.seconds
        a.recordDeviceActivity(h3, Platform.ANDROID)
        a.tick()
        // The timed-out set's devices went through the reservation: h2 now reserves a2 and a3.
        assertEquals(setOf(a2.key, a3.key), a.reservedFor(h2).toSet())
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
        assertTrue(h1 !in a.snapshot().holds.values)
        assertEquals(setOf(a2.key, a3.key), a.reservedFor(h2).toSet())
        assertNotNull(a.releasedSetOf(h1))
    }

    @Test fun timedOutSetDevicesGoToQueue() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        assertEquals(1, a.enqueue(h2, iosPhone))
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertEquals(mapOf(i1.key to h2), a.snapshot().holds)
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
    }

    @Test fun editingTimerAppliesToSet() {
        val a = alloc()
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, threeReq)).set
        now = t0 + 1.minutes; a.recordEdit(h1)
        assertEquals(true, a.setOf(h1)?.editing)
        now = t0 + 4.minutes; a.recordSetActivity(h1)
        assertEquals(false, a.setOf(h1)?.editing)
        now = t0 + 5.minutes; a.recordEdit(h1)
        now = t0 + 9.minutes; a.tick()
        assertEquals(set.members, a.setOf(h1)?.members)
        now = t0 + 9.minutes + 1.seconds; a.tick()
        assertNull(a.setOf(h1))
        assertEquals(ReleasedSet(ReleaseReason.EDIT_TIMEOUT, set.members), a.releasedSetOf(h1))
        assertIs<GrabSetResult.Granted>(a.tryRegrabSet(h1))
    }

    @Test fun deviceActivityClearsSetEditing() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        now = t0 + 1.minutes; a.recordEdit(h1)
        now = t0 + 4.minutes; a.recordDeviceActivity(h1, Platform.ANDROID)
        now = t0 + 10.minutes; a.tick()
        assertEquals(false, a.setOf(h1)?.editing)
        assertNotNull(a.setOf(h1))
    }

    @Test fun pairedMemberRegrabbedWithPartner() {
        val a = alloc(a1, pw, pi)
        val set = assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, SetRequest(listOf(androidPhone, iosPhone)))).set
        now = t0 + 15.minutes + 1.seconds; a.tick()
        assertTrue(a.snapshot().holds.isEmpty())
        assertEquals(set.members, assertIs<GrabSetResult.Granted>(a.tryRegrabSet(h1)).set.members)
        assertEquals(mapOf(a1.key to h1, phoneKey to h1, watchKey to h1), a.snapshot().holds)
    }

    @Test fun regrabOnlyAfterTimeout() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        a.release(h1, ReleaseReason.SELF_RELEASE)
        assertNull(a.releasedSetOf(h1))
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
        assertTrue(a.snapshot().holds.isEmpty())
    }

    @Test fun partialReleaseRecordsNoReleasedSet() {
        val a = alloc()
        a.tryGrabSet(h1, threeReq)
        a.release(h1, ReleaseReason.SELF_RELEASE, Platform.ANDROID)
        assertNull(a.releasedSetOf(h1))
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
    }

    @Test fun releasedSetClearedOnRelease() {
        val a = alloc()
        timedOut(a)
        a.release(h1, ReleaseReason.SELF_RELEASE, Platform.IOS)
        assertNull(a.releasedSetOf(h1))
    }

    @Test fun releasedSetClearedOnAcquireAgain() {
        val a = alloc()
        timedOut(a)
        assertIs<GrabSetResult.Granted>(a.tryGrabSet(h1, twoAndroid))
        assertNull(a.releasedSetOf(h1))
        a.release(h1, ReleaseReason.SELF_RELEASE)

        now = t0
        val b = alloc()
        timedOut(b)
        b.tryGrab(h2, androidPhone)
        assertEquals(1, b.enqueueSet(h1, threeReq))
        assertNull(b.releasedSetOf(h1))
    }

    @Test fun releasedSetClearedOnEndHolder() {
        val a = alloc()
        timedOut(a)
        a.endHolder(h1, ReleaseReason.SUBAGENT_STOP)
        assertNull(a.releasedSetOf(h1))
        now = t0
        val b = alloc()
        timedOut(b)
        b.endSession("s1")
        assertNull(b.releasedSetOf(h1))
    }

    @Test fun singleRegrabRefusedAfterSetTimeout() {
        // A timed-out set comes back whole through tryRegrabSet, never one member at a time.
        val a = alloc()
        timedOut(a)
        assertEquals(GrabResult.NoneFree, a.tryRegrab(h1, Platform.IOS))
        assertTrue(a.snapshot().holds.isEmpty())
    }

    @Test fun singleGrantAfterSetTimeoutClearsRecordAndRegrabsAsSingle() {
        val a = alloc()
        timedOut(a)
        val t1 = now
        assertIs<GrabResult.Granted>(a.tryGrab(h1, androidPhone))
        assertNull(a.releasedSetOf(h1))
        now = t1 + 15.minutes + 1.seconds; a.tick()
        assertEquals(ReleaseReason.IDLE_TIMEOUT, assertIs<HolderStatus.Released>(a.statusOf(h1, Platform.ANDROID)).reason)
        assertIs<GrabResult.Granted>(a.tryRegrab(h1, Platform.ANDROID))
        assertNull(a.releasedSetOf(h1))
        assertEquals(GrabSetResult.NoneFree, a.tryRegrabSet(h1))
    }

    @Test fun singleEnqueueAfterSetTimeoutClearsRecord() {
        val a = alloc()
        timedOut(a)
        a.tryGrab(h2, iosPhone)
        assertEquals(1, a.enqueue(h1, iosPhone))
        assertNull(a.releasedSetOf(h1))
    }
}
