package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/** `cdm acquire --device`: [Wants.device] limits a request to one device. */
class AllocatorDeviceWantsTest {
    private val p1 = AllocDevice(DeviceKey("P1"), "Pixel 8", Category.PHONE, false, listOf("p1"))
    private val p2 = AllocDevice(DeviceKey("P2"), "Pixel 9", Category.PHONE, false, listOf("p2"))
    private val h1 = HolderId("s1")
    private val h2 = HolderId("s2")
    private val now = Instant.fromEpochMilliseconds(1_000)

    private fun alloc() = Allocator(clock = { now }).also { it.setDevices(listOf(p1, p2)) }

    private fun only(d: AllocDevice) = Wants(setOf(Category.ANY), Platform.ANDROID, device = d.key)

    @Test fun grabsOnlyTheNamedDevice() {
        assertEquals(GrabResult.Granted(p2, "p2"), alloc().tryGrab(h1, only(p2)))
    }

    @Test fun waitsForTheNamedDeviceEvenWhenAnotherIsFree() {
        val a = alloc()
        a.tryGrab(h1, only(p2))
        assertEquals(GrabResult.NoneFree, a.tryGrab(h2, only(p2)))
        assertEquals(1, a.enqueue(h2, only(p2)))
        a.release(h1, ReleaseReason.SELF_RELEASE)
        val st = a.statusOf(h2)
        assertIs<HolderStatus.Holding>(st)
        assertEquals(p2.key, st.device)
    }

    @Test fun anotherDeviceFreeingDoesNotAnswerIt() {
        val a = alloc()
        a.tryGrab(h1, only(p1))
        val other = HolderId("s3")
        a.tryGrab(other, only(p2))
        a.enqueue(h2, only(p2))
        a.release(h1, ReleaseReason.SELF_RELEASE) // frees p1, which h2 didn't ask for
        assertIs<HolderStatus.Queued>(a.statusOf(h2))
    }

    @Test fun limitsNameTheDevice() {
        assertEquals(listOf("Android", "only P2"), only(p2).limits)
    }
}
