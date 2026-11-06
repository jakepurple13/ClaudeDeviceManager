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
import kotlin.time.Instant

class AllocatorPairTest {
    private val watchKey = DeviceKey("W-UDID")
    private val phoneKey = DeviceKey("I-UDID")
    private val w = AllocDevice(watchKey, "Apple Watch", Category.WATCH, false, listOf("W-UDID"), Platform.IOS, partner = phoneKey)
    private val i = AllocDevice(phoneKey, "iPhone", Category.PHONE, false, listOf("I-UDID"), Platform.IOS, partner = watchKey)
    private val i2 = AllocDevice(DeviceKey("I2-UDID"), "iPhone 2", Category.PHONE, false, listOf("I2-UDID"), Platform.IOS)
    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private var now = t0

    private val iosWatch = Wants(setOf(Category.WATCH), platform = Platform.IOS)
    private val iosPhone = Wants(setOf(Category.PHONE), platform = Platform.IOS)

    private fun alloc(vararg devices: AllocDevice = arrayOf(w, i)) =
        Allocator(clock = { now }).also { it.setDevices(devices.toList()) }

    private fun pairHeldBy(a: Allocator, h: HolderId) = assertEquals(mapOf(watchKey to h, phoneKey to h), a.snapshot().holds)

    @Test fun grantingWatchHoldsPairedPhone() {
        val a = alloc()
        assertEquals(GrabResult.Granted(w, "W-UDID"), a.tryGrab(h1, iosWatch))
        pairHeldBy(a, h1)
        val hold = a.holdingsOf(h1).single()
        assertEquals(watchKey, hold.device)
        assertEquals(phoneKey, hold.partner)
        assertEquals("W-UDID", a.serialFor(h1, Platform.IOS))
    }

    @Test fun grantingPhoneHoldsPairedWatch() {
        val a = alloc()
        assertEquals(GrabResult.Granted(i, "I-UDID"), a.tryGrab(h1, Wants(emptySet()), specific = phoneKey))
        pairHeldBy(a, h1)
        val hold = a.holdingsOf(h1).single()
        assertEquals(phoneKey, hold.device)
        assertEquals(watchKey, hold.partner)
        assertEquals(listOf(hold), a.snapshot().holdings[h1])
    }

    @Test fun pairNotGrantedWhenPartnerHeld() {
        // The partner can only be held on its own when it has no partner set; simulate with a one-sided phone.
        val lonePhone = i.copy(partner = null)
        val a = alloc(w, lonePhone)
        a.tryGrab(h2, iosPhone)
        assertEquals(GrabResult.NoneFree, a.tryGrab(h1, iosWatch))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h1, Wants(emptySet()), specific = watchKey))
        assertEquals(mapOf(phoneKey to h2), a.snapshot().holds)
    }

    @Test fun pairNotGrantedWhenPartnerDontUse() {
        val a = alloc(w, i.copy(dontUse = true))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h1, iosWatch))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h1, Wants(emptySet()), specific = watchKey))
        assertTrue(a.snapshot().holds.isEmpty())
        // Partner missing from the device list or offline is not grantable either.
        assertEquals(GrabResult.NoneFree, alloc(w).tryGrab(h1, iosWatch))
        assertEquals(GrabResult.NoneFree, alloc(w, i.copy(connectedSerials = emptyList())).tryGrab(h1, iosWatch))
    }

    @Test fun releaseFreesBoth() {
        val a = alloc()
        a.tryGrab(h1, iosWatch)
        a.release(h1, ReleaseReason.SELF_RELEASE, Platform.IOS)
        assertTrue(a.snapshot().holds.isEmpty())
        assertTrue(a.holdingsOf(h1).isEmpty())
        assertEquals(HolderStatus.Released(ReleaseReason.SELF_RELEASE, watchKey), a.statusOf(h1))
        assertEquals(GrabResult.Granted(i, "I-UDID"), a.tryGrab(h2, iosPhone))
        pairHeldBy(a, h2)
    }

    @Test fun releaseDeviceOnPartnerFreesPair() {
        val a = alloc()
        a.tryGrab(h1, iosWatch)
        a.releaseDevice(phoneKey, block = false)
        assertTrue(a.snapshot().holds.isEmpty())
        assertTrue(a.holdingsOf(h1).isEmpty())
        assertEquals(HolderStatus.Released(ReleaseReason.USER_RELEASE, watchKey), a.statusOf(h1))
    }

    @Test fun handOffMovesBoth() {
        val a = alloc()
        a.tryGrab(h1, iosWatch)
        a.handOff(phoneKey, h2)
        pairHeldBy(a, h2)
        val hold = a.holdingsOf(h2).single()
        assertEquals(watchKey, hold.device)
        assertEquals(phoneKey, hold.partner)
        assertTrue(a.holdingsOf(h1).isEmpty())
        assertEquals(HolderStatus.Released(ReleaseReason.USER_HANDOFF, watchKey), a.statusOf(h1))
    }

    @Test fun partnerDisconnectWhileHeldKeepsPair() {
        val a = alloc()
        a.tryGrab(h1, iosWatch)
        a.setDevices(listOf(w))
        pairHeldBy(a, h1)
        a.setDevices(listOf(w, i.copy(connectedSerials = emptyList())))
        pairHeldBy(a, h1)
        a.setDontUse(phoneKey, true, releaseNow = false)
        pairHeldBy(a, h1)
        assertEquals(phoneKey, a.holdingsOf(h1).single().partner)
        // Timeouts still release the pair as a unit.
        now = t0 + 16.minutes; a.tick()
        assertTrue(a.snapshot().holds.isEmpty())
    }

    @Test fun pairReturnsToQueueOnlyWhenBothUsable() {
        val a = alloc(w, i, i2)
        a.tryGrab(h1, iosWatch)
        assertEquals(1, a.enqueue(h2, iosWatch))
        a.setDevices(listOf(w, i.copy(connectedSerials = emptyList()), i2))
        a.release(h1, ReleaseReason.SELF_RELEASE)
        assertTrue(a.snapshot().holds.isEmpty())
        assertEquals(HolderStatus.Queued(1, iosWatch), a.statusOf(h2))
        // The partner comes back online: the pair goes to the queue.
        a.setDevices(listOf(w, i, i2))
        pairHeldBy(a, h2)
        assertTrue(a.snapshot().queue.isEmpty())
        // Released again while the partner is don't-use: stays free until the partner is usable again.
        assertEquals(1, a.enqueue(h1, iosWatch))
        a.setDontUse(phoneKey, true, releaseNow = true)
        assertTrue(a.snapshot().holds.isEmpty())
        assertEquals(HolderStatus.Queued(1, iosWatch), a.statusOf(h1))
        a.setDontUse(phoneKey, false, releaseNow = false)
        pairHeldBy(a, h1)
        val hold = assertIs<HolderStatus.Holding>(a.statusOf(h1))
        assertEquals(watchKey, hold.device)
        assertEquals(phoneKey, hold.partner)
    }
}
