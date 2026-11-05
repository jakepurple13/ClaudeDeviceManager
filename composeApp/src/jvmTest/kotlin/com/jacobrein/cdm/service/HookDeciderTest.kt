package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.allocator.AllocDevice
import com.jacobrein.cdm.core.allocator.Allocator
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class HookDeciderTest {
    private val wWifi = "192.168.1.5:5555"
    private val p = AllocDevice(DeviceKey("P"), "Pixel", Category.PHONE, false, listOf("p-usb"))
    private val w = AllocDevice(DeviceKey("W"), "Watch", Category.WATCH, false, listOf("w-usb", wWifi))
    private val h = HolderId("s1")
    private val h2 = HolderId("s2")
    private val cwd = "/w"
    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private var now = t0
    private val allocator = Allocator(clock = { now }).also { it.setDevices(listOf(p, w)) }
    private val decider = HookDecider(allocator) { Category.WATCH }

    private suspend fun bash(cmd: String, holder: HolderId = h) = decider.onBash(holder, cwd, cmd)

    private fun HookReply.cmd() = assertIs<HookReply.Rewrite>(this).command
    private fun HookReply.reason() = assertIs<HookReply.Deny>(this).reason

    @Test fun otherCommandFromNonHolderIsNoOpinion() = runTest {
        assertEquals(HookReply.NoOpinion, bash("ls"))
    }

    @Test fun cdmGetsIdentity() = runTest {
        val r = assertIs<HookReply.Rewrite>(bash("cdm release"))
        assertEquals("cdm release --holder s1:main --cwd '/w'", r.command)
        assertEquals(RewriteKind.CDM, r.kind)
        assertEquals("cdm release", r.original)
    }

    @Test fun deviceCommandGrabsByProjectCategory() = runTest {
        val r = assertIs<HookReply.Rewrite>(bash("adb shell ls"))
        assertEquals(pinPrefix("w-usb") + "adb shell ls", r.command)
        assertEquals(RewriteKind.PIN, r.kind)
        assertEquals(w.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
    }

    @Test fun holderPinsEveryCommand() = runTest {
        bash("adb shell ls")
        assertEquals(pinPrefix("w-usb") + "./run_tests.sh", bash("./run_tests.sh").cmd())
    }

    @Test fun compoundCommandPinnedWhole() = runTest {
        val c = "cd app && ./gradlew installDebug | tail"
        assertEquals(pinPrefix("w-usb") + c, bash(c).cmd())
    }

    @Test fun noneFreeDenies() = runTest {
        allocator.tryGrab(h2, setOf(Category.WATCH))
        assertTrue("`cdm acquire --category watch`" in bash("adb shell ls").reason())
    }

    @Test fun explicitSerialHeldByOtherDenies() = runTest {
        allocator.tryGrab(h2, setOf(Category.WATCH))
        assertTrue("Watch" in bash("adb -s w-usb shell ls").reason())
    }

    @Test fun explicitFreeSerialGrabsThatDevice() = runTest {
        assertEquals(pinPrefix("p-usb") + "adb -s p-usb shell ls", bash("adb -s p-usb shell ls").cmd())
        assertEquals(p.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
    }

    @Test fun explicitOtherTransportSerialAccepted() = runTest {
        bash("adb shell ls")
        assertIs<HookReply.Rewrite>(bash("adb -s $wWifi shell ls"))
    }

    @Test fun explicitWrongSerialWhileHoldingDenies() = runTest {
        bash("adb shell ls")
        assertTrue("you hold" in bash("adb -s p-usb shell ls").reason())
    }

    /** Ruling G: every segment is checked, not just the merged classification. */
    @Test fun compoundWrongSerialInLaterSegmentDenies() = runTest {
        bash("adb shell ls")
        assertTrue("you hold" in bash("adb shell ls && adb -s p-usb install x").reason())
    }

    @Test fun androidSerialAssignmentToOtherDeviceDenies() = runTest {
        bash("adb shell ls")
        assertTrue("you hold" in bash("ANDROID_SERIAL=p-usb adb shell ls").reason())
    }

    @Test fun dangerousDenied() = runTest {
        assertTrue("Ask the user" in bash("adb kill-server").reason())
        assertTrue("Ask the user" in bash("adb shell ls; adb kill-server").reason())
    }

    @Test fun disconnectOwnDeviceAllowedOthersDenied() = runTest {
        assertTrue("Ask the user" in bash("adb disconnect $wWifi").reason())
        bash("adb shell ls")
        assertIs<HookReply.Rewrite>(bash("adb disconnect $wWifi"))
        assertTrue("Ask the user" in bash("adb disconnect 10.0.0.9:5555").reason())
    }

    @Test fun globalSafeFromNonHolderIsNoOpinion() = runTest {
        assertEquals(HookReply.NoOpinion, bash("adb devices"))
    }

    @Test fun usbFlagGrabsFreeUsbDevice() = runTest {
        allocator.tryGrab(h2, setOf(Category.WATCH))
        assertEquals(pinPrefix("p-usb") + "adb -d shell ls", bash("adb -d shell ls").cmd())
    }

    @Test fun emulatorFlagWithNoEmulatorDenies() = runTest {
        assertIs<HookReply.Deny>(bash("adb -e shell ls"))
    }

    @Test fun regrabAfterIdleTimeout() = runTest {
        bash("adb shell ls")
        now = t0 + 16.minutes; allocator.tick()
        assertIs<HookReply.Rewrite>(bash("adb shell ls"))
        assertEquals(w.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
    }

    @Test fun noRegrabWhenTaken() = runTest {
        bash("adb shell ls")
        now = t0 + 16.minutes; allocator.tick()
        allocator.tryGrab(h2, setOf(Category.WATCH))
        val r = bash("adb shell ls").reason()
        assertTrue("released after 15 min idle" in r, r)
        assertTrue("cdm acquire" in r, r)
    }

    @Test fun userReleasedDenies() = runTest {
        bash("adb shell ls")
        allocator.releaseDevice(w.key, block = false)
        val r = bash("adb shell ls").reason()
        assertTrue("released from Device Manager" in r, r)
        assertTrue("blocked" !in r, r)
    }

    @Test fun userReleasedAndBlockedDenies() = runTest {
        bash("adb shell ls")
        allocator.releaseDevice(w.key, block = true)
        val r = bash("adb shell ls").reason()
        assertTrue("released from Device Manager" in r, r)
        assertTrue("blocked" in r, r)
    }

    @Test fun gradleNonDeviceResetsTimer() = runTest {
        bash("adb shell ls")
        now = t0 + 14.minutes
        assertEquals(pinPrefix("w-usb") + "./gradlew assembleDebug", bash("./gradlew assembleDebug").cmd())
        now = t0 + 20.minutes; allocator.tick()
        assertIs<HolderStatus.Holding>(allocator.statusOf(h))
    }

    @Test fun editIsNoOpinionAndMarksEditing() = runTest {
        bash("adb shell ls")
        assertEquals(HookReply.NoOpinion, decider.onEdit(h))
        assertTrue(assertIs<HolderStatus.Holding>(allocator.statusOf(h)).editing)
    }

    @Test fun cdmMixedWithDeviceCommandKeepsIdentityAndPins() = runTest {
        val r = bash("cdm status && adb shell ls")
        assertEquals(pinPrefix("w-usb") + "cdm status --holder s1:main --cwd '/w' && adb shell ls", r.cmd())
    }
}
