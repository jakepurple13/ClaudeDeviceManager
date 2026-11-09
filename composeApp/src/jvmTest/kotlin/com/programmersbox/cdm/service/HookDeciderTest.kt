package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.Allocator
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
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
        allocator.tryGrab(h2, Wants(setOf(Category.WATCH)))
        assertTrue("`cdm acquire --category watch --android`" in bash("adb shell ls").reason())
    }

    @Test fun explicitSerialHeldByOtherDenies() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.WATCH)))
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

    private val wWifiOnly = w.copy(connectedSerials = listOf(wWifi))

    @Test fun usbFlagGrabsSoleUsbDeviceThenStaysAllowed() = runTest {
        allocator.setDevices(listOf(p, wWifiOnly))
        assertEquals(pinPrefix("p-usb") + "adb -d shell ls", bash("adb -d shell ls").cmd())
        assertEquals(p.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
        // The same command again must not be called "a different device".
        assertEquals(pinPrefix("p-usb") + "adb -d shell ls", bash("adb -d shell ls").cmd())
    }

    @Test fun usbFlagWithTwoUsbDevicesDeniedWhenFresh() = runTest {
        val r = bash("adb -d shell ls").reason()
        assertTrue("Multiple USB-connected devices" in r, r)
        assertEquals(HolderStatus.None, allocator.statusOf(h))
    }

    @Test fun usbFlagSoleDeviceHeldByOtherNamesHolder() = runTest {
        allocator.setDevices(listOf(p, wWifiOnly))
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE)))
        assertTrue("s2:main" in bash("adb -d shell ls").reason())
    }

    @Test fun holdingUsbFlagAllowedOnlyWhenSoleUsbDevice() = runTest {
        bash("adb shell ls") // holds W (w-usb); P is also USB
        assertTrue("you hold" in bash("adb -d shell ls").reason())
        allocator.setDevices(listOf(w)) // P unplugged: W is the sole USB device
        assertIs<HookReply.Rewrite>(bash("adb -d shell ls"))
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
        allocator.tryGrab(h2, Wants(setOf(Category.WATCH)))
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

    // ---- final review #1: ANDROID_SERIAL on any command ----

    @Test fun holderAssigningOtherSerialOnNonAdbCommandDenied() = runTest {
        bash("adb shell ls") // holds W
        for (c in listOf(
            "ANDROID_SERIAL=p-usb ./run_tests.sh",
            "export ANDROID_SERIAL=p-usb; scrcpy",
            "(export ANDROID_SERIAL=p-usb; adb shell ls)",
            "cd x && env ANDROID_SERIAL=p-usb ./gradlew assembleDebug",
        )) {
            assertTrue("you hold" in bash(c).reason(), c)
        }
        assertIs<HookReply.Rewrite>(bash("ANDROID_SERIAL=$wWifi ./run_tests.sh"))
    }

    @Test fun freshAssignmentOnNonAdbCommandGrabsThatDevice() = runTest {
        assertEquals(pinPrefix("p-usb") + "ANDROID_SERIAL=p-usb ./run_tests.sh", bash("ANDROID_SERIAL=p-usb ./run_tests.sh").cmd())
        assertEquals(p.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
    }

    @Test fun freshAssignmentToHeldDeviceNamesHolder() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE)))
        assertTrue("s2:main" in bash("export ANDROID_SERIAL=p-usb; scrcpy").reason())
    }

    // ---- pin variables written by split names or indirect writes ----

    private val pinVarWrite = "Set ANDROID_SERIAL/CDM_IOS_UDID only as a literal NAME=<your device>."

    @Test fun splitOrIndirectSerialWritesDeniedOrChecked() = runTest {
        bash("adb shell ls") // holds W
        // Split names are assignments: their value goes through the own-serial check.
        for (c in listOf(
            "export ANDROID_\"SERIAL\"=p-usb; adb shell ls",
            "env ANDROID_\\SERIAL=p-usb adb shell ls",
            "export 'ANDROID_SERIAL'=p-usb; ./t.sh",
        )) {
            assertTrue("you hold" in bash(c).reason(), c)
        }
        // Writes that aren't a literal NAME=value are denied outright.
        for (c in listOf(
            "read ANDROID_SERIAL <<< p-usb; adb shell ls",
            "printf -v ANDROID_SERIAL %s p-usb; adb shell ls",
            "declare -n r=ANDROID_SERIAL; r=p-usb; adb shell ls",
            "for ANDROID_SERIAL in \$(adb devices | tail -n +2 | cut -f1); do adb install x.apk; done",
            "unset ANDROID_SERIAL; adb shell ls",
            "export -n ANDROID_SERIAL; ./t.sh",
            "export ANDROID_SERIAL+=x; ./t.sh",
            "mapfile -t ANDROID_SERIAL < f; adb shell ls",
        )) {
            assertEquals(pinVarWrite, bash(c).reason(), c)
        }
        // From a session that holds nothing, too.
        assertEquals(pinVarWrite, bash("for ANDROID_SERIAL in a b; do adb install x.apk; done", h2).reason())
    }


    /** Commands that mention a pin variable as text, or only read it: no write. */
    private val pinVarTextOnly = listOf(
        "git commit -m \"\$(cat <<'EOF'\nfix: honor ANDROID_SERIAL\nEOF\n)\"",
        "gh pr create --title \"ANDROID_SERIAL support\" --body \"\$(cat <<'EOF'\nread ANDROID_SERIAL; unset ANDROID_SERIAL\nEOF\n)\"",
        "gh pr create --title \"ANDROID_SERIAL support\"",
        "adb -s \${ANDROID_SERIAL%%,*} shell ls",
        "for s in \${ANDROID_SERIAL//,/ }; do echo \$s; done",
        "x=\${ANDROID_SERIAL:+a}; n=\${#ANDROID_SERIAL}; : \${ANDROID_SERIAL:?not set}; y=\${!ANDROID_SERIAL}",
        "z=\${ANDROID_SERIAL#p}\${ANDROID_SERIAL##p}\${ANDROID_SERIAL%x}\${ANDROID_SERIAL^^}\${ANDROID_SERIAL,,}\${ANDROID_SERIAL:0:2}\${ANDROID_SERIAL@Q}\${ANDROID_SERIAL+x}\${ANDROID_SERIAL?x}",
        "sed -i 's/ANDROID_SERIAL/X/' build.gradle",
        "awk '/ANDROID_SERIAL/' f",
        "jq '.env.ANDROID_SERIAL' f.json",
        "python3 -c \"import os; print(os.environ['ANDROID_SERIAL'])\"",
        "node -e \"console.log(process.env.ANDROID_SERIAL)\"",
        "find . -name '*.kts' | xargs grep ANDROID_SERIAL",
        "diff <(grep ANDROID_SERIAL a) <(grep ANDROID_SERIAL b)",
        "cat > notes.md <<'X'\nread ANDROID_SERIAL; don't for ANDROID_SERIAL in x\nX",
        "less ANDROID_SERIAL",
        "less CDM_IOS_UDID",
    )

    @Test fun pinVariableTextAndReadsAllowedForEverySession() = runTest {
        // (`adb -s ${ANDROID_SERIAL%%,*}` is still checked as a serial, as before: only the write rule must not fire.)
        for (c in pinVarTextOnly) assertTrue((bash(c, h2) as? HookReply.Deny)?.reason != pinVarWrite, c) // holds nothing
        bash("adb shell ls") // holds W
        for (c in pinVarTextOnly) assertTrue((bash(c) as? HookReply.Deny)?.reason != pinVarWrite, c)
    }

    @Test fun pinVariableReadsAndLiteralOwnAssignmentsAllowed() = runTest {
        bash("adb shell ls") // holds W
        assertEquals(pinPrefix("w-usb") + "echo \$ANDROID_SERIAL", bash("echo \$ANDROID_SERIAL").cmd())
        assertEquals(pinPrefix("w-usb") + "grep ANDROID_SERIAL f", bash("grep ANDROID_SERIAL f").cmd())
        assertEquals(
            pinPrefix("w-usb") + "ANDROID_SERIAL=w-usb ./gradlew connectedCheck",
            bash("ANDROID_SERIAL=w-usb ./gradlew connectedCheck").cmd(),
        )
        assertIs<HookReply.Rewrite>(bash("echo \${ANDROID_SERIAL:-none}"))
        assertIs<HookReply.Rewrite>(bash("sh -c 'echo \$ANDROID_SERIAL'"))
    }

    // ---- final review #2: wrappers and -t ----

    @Test fun wrappedWrongSerialWhileHoldingDenied() = runTest {
        bash("adb shell ls")
        for (c in listOf("timeout 30 adb -s p-usb shell ls", "nohup adb -s p-usb logcat", "(adb -s p-usb shell ls)", "env -i adb -s p-usb shell")) {
            assertTrue("you hold" in bash(c).reason(), c)
        }
    }

    @Test fun transportIdDeniedHoldingOrFresh() = runTest {
        assertTrue("-t" in bash("adb -t 3 shell ls").reason())
        assertEquals(HolderStatus.None, allocator.statusOf(h))
        bash("adb shell ls")
        assertTrue("-t" in bash("adb -t 3 shell ls").reason())
    }

    // ---- final review #3: fresh grab checks every segment ----

    @Test fun freshGrabWithConflictingSecondSegmentDenied() = runTest {
        // -d is ambiguous (two USB devices) so it can't be checked to land on p-usb.
        assertIs<HookReply.Deny>(bash("adb -s p-usb shell x && adb -d install y"))
        assertEquals(HolderStatus.None, allocator.statusOf(h))
        assertIs<HookReply.Deny>(bash("adb -s p-usb shell x && adb -e install y"))
        assertEquals(HolderStatus.None, allocator.statusOf(h))
    }

    @Test fun freshGrabUsbFlagPlusOtherSerialDenied() = runTest {
        allocator.setDevices(listOf(p, wWifiOnly))
        assertIs<HookReply.Deny>(bash("adb -d shell x; ANDROID_SERIAL=$wWifi ./t.sh"))
        assertEquals(HolderStatus.None, allocator.statusOf(h))
    }

    // ---- final review #4, Ruling V ----

    @Test fun acquireChainedWithDeviceWorkWhileHoldingNothingDenied() = runTest {
        val r = bash("cdm acquire --category watch && adb shell ls").reason()
        assertTrue("on its own first" in r, r)
        assertEquals(HolderStatus.None, allocator.statusOf(h))
        assertIs<HookReply.Rewrite>(bash("cdm acquire --category watch && cdm status"))
    }

    @Test fun acquireChainedWhileHoldingIsPinned() = runTest {
        bash("adb shell ls")
        assertIs<HookReply.Rewrite>(bash("cdm acquire && adb shell ls"))
    }

    // ---- final whole-branch review: Android Maestro and platform hints ----

    @Test fun maestroWithoutFlagGrabsAndPinsAndroidSerial() = runTest {
        assertEquals(pinPrefix("w-usb") + "maestro test flow.yaml", bash("maestro test flow.yaml").cmd())
        assertEquals(w.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
        assertEquals(pinPrefix("w-usb") + "maestro test flow.yaml", bash("maestro test flow.yaml").cmd())
    }

    @Test fun maestroDeviceOwnSerialAllowed() = runTest {
        bash("adb shell ls") // holds W
        assertEquals(pinPrefix("w-usb") + "maestro --device w-usb test f.yaml", bash("maestro --device w-usb test f.yaml").cmd())
        assertIs<HookReply.Rewrite>(bash("maestro --device=$wWifi test f.yaml"))
    }

    @Test fun maestroDeviceFreeSerialGrabsThatDevice() = runTest {
        assertEquals(pinPrefix("p-usb") + "maestro --device p-usb test f.yaml", bash("maestro --device p-usb test f.yaml").cmd())
        assertEquals(p.key, assertIs<HolderStatus.Holding>(allocator.statusOf(h)).device)
    }

    @Test fun maestroDeviceOthersSerialDenied() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE))) // s2 holds P
        assertTrue("s2:main" in bash("maestro --device p-usb test f.yaml").reason())
        bash("adb shell ls") // s1 holds W
        assertTrue("you hold" in bash("maestro --device p-usb test f.yaml").reason())
    }

    @Test fun androidHintsSayAndroid() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.WATCH)))
        assertTrue("`cdm acquire --category watch --android`" in bash("adb shell ls").reason())
        assertTrue("`cdm acquire --category watch --android`" in bash("adb -s w-usb shell ls").reason())
        assertTrue("--android" in bash("adb -s nope shell ls").reason())
        assertTrue("--android" in bash("adb -e shell ls").reason())
        allocator.release(h2, com.programmersbox.cdm.core.allocator.ReleaseReason.SELF_RELEASE)
        bash("adb shell ls")
        assertTrue("`cdm release --android`" in bash("adb -s p-usb shell ls").reason())
        allocator.releaseDevice(w.key, block = false)
        assertTrue("`cdm acquire --android`" in bash("adb shell ls").reason())
    }

    @Test fun timeoutHintSaysAndroid() = runTest {
        bash("adb shell ls")
        now = t0 + 16.minutes; allocator.tick()
        allocator.tryGrab(h2, Wants(setOf(Category.WATCH)))
        assertTrue("`cdm acquire --category watch --android`" in bash("adb shell ls").reason())
    }

    // ---- first-use grab honours the queued request ----

    private val emu = AllocDevice(
        DeviceKey("E"), "Emu", Category.PHONE, false, listOf("emulator-5554"),
        com.programmersbox.cdm.core.model.Platform.ANDROID, virtual = true,
    )
    private val android = com.programmersbox.cdm.core.model.Platform.ANDROID

    @Test fun queuedForVirtualOnlyRealFreeDenied() = runTest {
        allocator.enqueue(h, Wants(setOf(Category.PHONE), android, virtual = true))
        val r = bash("adb shell am start -n a/.Main").reason()
        assertEquals(
            "You're waiting for a phone device (Android, virtual only), #1 in line. " +
                "Run `cdm acquire --category phone --android --virtual` to keep waiting, " +
                "`cdm acquire --category <other>` to wait for something else, or `cdm release` to leave the queue.",
            r,
        )
        assertTrue(allocator.holdingsOf(h).isEmpty())
        assertIs<HolderStatus.Queued>(allocator.statusOf(h))
    }

    @Test fun queuedWithoutPlatformDenyHasNoPlatformFlag() = runTest {
        allocator.enqueue(h, Wants(setOf(Category.TABLET)))
        val r = bash("adb shell ls").reason()
        assertTrue("You're waiting for a tablet device, #1 in line." in r, r)
        assertTrue("Run `cdm acquire --category tablet` to keep waiting" in r, r)
        assertTrue(allocator.holdingsOf(h).isEmpty())
    }

    @Test fun queuedExplicitSerialNotFittingIsGranted() = runTest {
        // Accepted ruling: an explicit -s is the session's own choice, even if it doesn't fit the queued request.
        allocator.enqueue(h, Wants(setOf(Category.PHONE), android, virtual = true))
        assertEquals(pinPrefix("p-usb") + "adb -s p-usb shell ls", bash("adb -s p-usb shell ls").cmd())
        assertEquals(p.key, allocator.holdingsOf(h).single().device)
    }

    @Test fun queuedExplicitSerialHeldByOtherDenied() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE)))
        allocator.enqueue(h, Wants(setOf(Category.PHONE), android, virtual = true))
        val r = bash("adb -s p-usb shell ls").reason()
        assertTrue("in use by s2:main" in r, r)
        assertTrue(allocator.holdingsOf(h).isEmpty())
    }

    @Test fun queuedForVirtualGrabsEmulator() = runTest {
        allocator.setDevices(listOf(p, w, emu))
        allocator.enqueue(h, Wants(setOf(Category.PHONE), android, virtual = true))
        assertEquals(pinPrefix("emulator-5554") + "adb shell ls", bash("adb shell ls").cmd())
        assertEquals(emu.key, allocator.holdingsOf(h).single().device)
    }

    @Test fun queuedForAnyPlatformUsesQueuedCategories() = runTest {
        // Project default is watch; queued for phone with no platform limit, the grab takes the phone.
        allocator.enqueue(h, Wants(setOf(Category.PHONE)))
        assertEquals(pinPrefix("p-usb") + "adb shell ls", bash("adb shell ls").cmd())
    }
}
