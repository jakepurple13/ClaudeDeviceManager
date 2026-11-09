package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorEvent
import com.programmersbox.cdm.core.allocator.Allocator
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class HookDeciderIosTest {
    private val u1 = "A1B2C3D4-1111-2222-3333-444455556666"
    private val u2 = "B1B2C3D4-1111-2222-3333-444455556666"
    private val untracked = "C1B2C3D4-1111-2222-3333-444455556666"
    private val watchUdid = "D1B2C3D4-1111-2222-3333-444455556666"

    private val p = AllocDevice(DeviceKey("P"), "Pixel", Category.PHONE, false, listOf("p-usb"))
    private val w = AllocDevice(DeviceKey("W"), "Watch", Category.WATCH, false, listOf("w-usb"))
    private val i1 = ios(u1, "iPhone 15")
    private val i2 = ios(u2, "iPad Air", Category.TABLET)

    private fun ios(udid: String, name: String, category: Category = Category.PHONE, partner: String? = null) =
        AllocDevice(DeviceKey(udid), name, category, false, listOf(udid), Platform.IOS, virtual = true, partner = partner?.let(::DeviceKey))

    private val h = HolderId("s1")
    private val h2 = HolderId("s2")
    private val cwd = "/w"
    private var category = Category.ANY
    private var pinScript: Path? = null
    private var now = Instant.fromEpochMilliseconds(1_000_000)
    private val events = mutableListOf<AllocatorEvent>()
    private val allocator = Allocator(clock = { now }, onEvent = { events += it }).also { it.setDevices(listOf(p, w, i1, i2)) }
    private val decider = HookDecider(allocator, iosPinScript = { pinScript }) { category }

    private suspend fun bash(cmd: String, holder: HolderId = h) = decider.onBash(holder, cwd, cmd)
    private fun HookReply.cmd() = assertIs<HookReply.Rewrite>(this).command
    private fun HookReply.reason() = assertIs<HookReply.Deny>(this).reason
    private fun iosHold(holder: HolderId = h) = allocator.holdingsOf(holder).firstOrNull { it.platform == Platform.IOS }?.device

    @Test fun iosCommandGetsUdidExport() = runTest {
        val r = assertIs<HookReply.Rewrite>(bash("xcrun simctl install \$CDM_IOS_UDID App.app"))
        assertEquals("export CDM_IOS_UDID=$u1; xcrun simctl install \$CDM_IOS_UDID App.app", r.command)
        assertEquals(RewriteKind.PIN, r.kind)
        assertEquals(i1.key, iosHold())
    }

    @Test fun bootedRewrittenToHeldUdid() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.TABLET), Platform.IOS))
        assertEquals(
            "export CDM_IOS_UDID=$u2; xcrun simctl openurl $u2 https://x",
            bash("xcrun simctl openurl booted https://x").cmd(),
        )
    }

    @Test fun bootedWithoutIosHoldGrabsThenRewrites() = runTest {
        assertEquals(
            "export CDM_IOS_UDID=$u1; xcrun simctl install $u1 App.app",
            bash("xcrun simctl install booted App.app").cmd(),
        )
        assertEquals(i1.key, iosHold())
    }

    @Test fun foreignUdidDenied() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE), Platform.IOS)) // s2 holds i1
        val fresh = bash("xcrun simctl install $u1 App.app").reason()
        assertTrue("s2:main" in fresh, fresh)
        allocator.tryGrab(h, Wants(setOf(Category.TABLET), Platform.IOS)) // s1 holds i2
        val r = bash("xcodebuild test -scheme App -destination 'id=$u1'").reason()
        assertTrue("you hold iPad Air" in r, r)
        // Naming a free tracked device that isn't yours is denied too, like `adb -s <other>`.
        allocator.release(h2, com.programmersbox.cdm.core.allocator.ReleaseReason.SELF_RELEASE)
        assertTrue("you hold" in bash("xcrun simctl install $u1 App.app").reason())
    }

    @Test fun freeTrackedUdidGrabsThatDevice() = runTest {
        assertEquals("export CDM_IOS_UDID=$u2; xcrun simctl install $u2 App.app", bash("xcrun simctl install $u2 App.app").cmd())
        assertEquals(i2.key, iosHold())
    }

    @Test fun untrackedUdidDeniedExceptSimctlBoot() = runTest {
        val r = bash("xcrun simctl install $untracked App.app").reason()
        assertTrue(untracked in r, r)
        assertFalse(bash("xcrun simctl boot $untracked") is HookReply.Deny)
        assertEquals(null, iosHold()) // booting an untracked sim grabs nothing
        assertTrue(untracked in bash("xcrun simctl boot $untracked && xcrun simctl install $untracked a.app").reason())
    }

    @Test fun unpinnableXcodebuildDenied() = runTest {
        for (c in listOf(
            "xcodebuild test -scheme App",
            "xcodebuild test -scheme App -destination 'platform=iOS Simulator,name=iPhone 15'",
            "xcodebuild test -destination id=foo\$CDM_IOS_UDID",
        )) {
            val r = bash(c).reason()
            assertTrue("-destination id=\$CDM_IOS_UDID" in r && "--device \$CDM_IOS_UDID" in r, "$c: $r")
        }
        assertEquals(null, iosHold())
    }

    @Test fun kmpGradleIosTestDeniedWithoutPinScript() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.ANY), Platform.IOS))
        assertTrue("--device \$CDM_IOS_UDID" in bash("./gradlew iosSimulatorArm64Test").reason())
    }

    @Test fun kmpGradleIosTestGetsInitScriptWhenInstalled() = runTest {
        pinScript = Path("/home/me/.claude-device-manager/ios-pin.gradle")
        allocator.tryGrab(h, Wants(setOf(Category.ANY), Platform.IOS))
        assertEquals(
            "export CDM_IOS_UDID=$u1; ./gradlew --init-script '/home/me/.claude-device-manager/ios-pin.gradle' iosSimulatorArm64Test",
            bash("./gradlew iosSimulatorArm64Test").cmd(),
        )
    }

    @Test fun kmpGradleIosTestGrabsOnFirstUseWithPinScript() = runTest {
        pinScript = Path("/p/ios-pin.gradle")
        assertEquals(
            "export CDM_IOS_UDID=$u1; ./gradlew --init-script '/p/ios-pin.gradle' iosSimulatorArm64Test",
            bash("./gradlew iosSimulatorArm64Test").cmd(),
        )
    }

    @Test fun dangerousDeniedWhileOthersHoldIos() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE), Platform.IOS))
        for (c in listOf("xcrun simctl shutdown all", "xcrun simctl erase all", "killall Simulator", "xcrun simctl delete unavailable")) {
            val r = bash(c).reason()
            val what = if (c.startsWith("killall")) "killall Simulator" else c.removePrefix("xcrun ").substringBefore(" unavailable")
            assertTrue(what in r, "$c: $r")
        }
    }

    @Test fun dangerousDeniedWhileOthersHoldIosInASet() = runTest {
        val req = com.programmersbox.cdm.core.allocator.SetRequest(
            listOf(Wants(setOf(Category.PHONE), Platform.ANDROID), Wants(setOf(Category.PHONE), Platform.IOS)),
        )
        assertIs<com.programmersbox.cdm.core.allocator.GrabSetResult.Granted>(allocator.tryGrabSet(h2, req))
        for (c in listOf("xcrun simctl shutdown all", "xcrun simctl erase all", "killall Simulator", "xcrun simctl delete unavailable")) {
            val r = bash(c).reason()
            assertTrue("affects other sessions' iOS devices" in r, "$c: $r")
        }
    }

    @Test fun dangerousAllowedWhenNoOtherIosHolds() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE), Platform.ANDROID)) // s2 holds only Android
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // s1's own iOS hold doesn't count
        assertFalse(bash("xcrun simctl shutdown all") is HookReply.Deny)
        assertFalse(bash("killall Simulator") is HookReply.Deny)
    }

    @Test fun mixedCommandExportsBoth() = runTest {
        category = Category.WATCH
        // The Android part grabs the watch by the project default; the iPhone is held up front.
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        assertEquals(
            pinPrefix("w-usb", u1, null) + "adb shell ls && xcrun simctl install $u1 App.app",
            bash("adb shell ls && xcrun simctl install booted App.app").cmd(),
        )
        assertEquals(
            "export ANDROID_SERIAL=w-usb; export CDM_IOS_UDID=$u1; ",
            pinPrefix("w-usb", u1, null),
        )
    }

    @Test fun mixedCommandGrabsBothPlatforms() = runTest {
        assertEquals(
            pinPrefix("p-usb", u1, null) + "adb install a.apk; xcrun simctl install $u1 App.app",
            bash("adb install a.apk; xcrun simctl install booted App.app").cmd(),
        )
        assertEquals(setOf(Platform.ANDROID, Platform.IOS), allocator.holdingsOf(h).map { it.platform }.toSet())
    }

    @Test fun androidCommandWithOnlyIosHoldGrabsAndroid() = runTest {
        category = Category.WATCH
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        val c = bash("adb shell ls").cmd()
        assertEquals(pinPrefix("w-usb", u1, null) + "adb shell ls", c)
        assertTrue("ANDROID_SERIAL=$u1" !in c, c)
        assertEquals(w.key, allocator.holdingsOf(h).first { it.platform == Platform.ANDROID }.device)
    }

    @Test fun adbUsbFlagIgnoresIosDevices() = runTest {
        allocator.setDevices(listOf(p, i1, i2))
        assertEquals(pinPrefix("p-usb") + "adb -d shell ls", bash("adb -d shell ls").cmd())
    }

    @Test fun offlineHeldSimulatorUdidIsStillOwn() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        allocator.setDevices(listOf(p, w, i1.copy(connectedSerials = emptyList()), i2))
        assertEquals("export CDM_IOS_UDID=$u1; xcrun simctl boot $u1", bash("xcrun simctl boot $u1").cmd())
        assertEquals(
            "export CDM_IOS_UDID=$u1; xcrun simctl install $u1 App.app",
            bash("xcrun simctl install $u1 App.app").cmd(),
        )
    }

    @Test fun pairExportsPairUdid() = runTest {
        val phone = ios(u1, "iPhone 15", partner = watchUdid)
        val watch = ios(watchUdid, "Apple Watch", Category.WATCH, partner = u1)
        allocator.setDevices(listOf(p, w, phone, watch))
        assertEquals(
            "export CDM_IOS_UDID=$u1; export CDM_IOS_PAIR_UDID=$watchUdid; xcrun simctl install $u1 App.app",
            bash("xcrun simctl install booted App.app").cmd(),
        )
        // The partner's UDID is the holder's own too.
        assertIs<HookReply.Rewrite>(bash("xcrun simctl install $watchUdid W.app"))
    }

    @Test fun noIosDevicesNoXcodeDeniesWithAcquireHint() = runTest {
        category = Category.PHONE
        allocator.setDevices(listOf(p, w))
        val r = bash("xcrun simctl install booted App.app").reason()
        assertTrue("cdm acquire --category phone --ios" in r, r)
    }

    @Test fun iosHeldByOtherDeniesWithAcquireHint() = runTest {
        category = Category.TABLET
        allocator.tryGrab(h2, Wants(setOf(Category.TABLET), Platform.IOS))
        val r = bash("xcrun simctl install booted App.app").reason()
        assertTrue("cdm acquire --category tablet --ios" in r, r)
    }

    @Test fun iosOnlyHolderGetsNoAndroidSerial() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        assertEquals("export CDM_IOS_UDID=$u1; ./run_tests.sh", bash("./run_tests.sh").cmd())
        assertEquals("export CDM_IOS_UDID=$u1; ./gradlew assembleDebug", bash("./gradlew assembleDebug").cmd())
    }

    @Test fun iosGlobalSafeFromNonHolderIsNoOpinion() = runTest {
        assertEquals(HookReply.NoOpinion, bash("xcrun simctl list devices"))
        assertEquals(HookReply.NoOpinion, bash("xcodebuild -list"))
    }

    @Test fun acquireChainedWithIosWorkWhileHoldingNoIosDenied() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.ANDROID))
        assertTrue("on its own first" in bash("cdm acquire --ios && xcrun simctl install booted a.app").reason())
    }

    // ---- fix round 1 ----

    @Test fun udidAssignmentToOtherDeviceDeniedWhileHolding() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // holds u1
        for (c in listOf(
            "export CDM_IOS_UDID=$u2; xcrun simctl install \$CDM_IOS_UDID a.app",
            "CDM_IOS_UDID=$u2 ./gradlew iosSimulatorArm64Test",
            "CDM_IOS_UDID=\$(echo x) xcrun simctl install \$CDM_IOS_UDID a.app",
            "CDM_IOS_UDID=`echo x` ./t.sh",
            "export CDM_IOS_UDID+=x; ./t.sh",
            "CDM_IOS_PAIR_UDID=$u2 ./t.sh",
            "env CDM_IOS_UDID=$untracked ./t.sh",
        )) {
            assertIs<HookReply.Deny>(bash(c), c)
        }
    }

    @Test fun splitOrIndirectUdidWritesDenied() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // holds u1
        for (c in listOf(
            "export CDM_IOS_\"UDID\"=$u2; xcrun simctl launch \$CDM_IOS_UDID x",
            "CDM_IOS_\\UDID=$u2 ./t.sh",
            "export CDM_IOS_PAIR_\"UDID\"=$u2; ./t.sh",
        )) {
            assertTrue("different device" in bash(c).reason(), c)
        }
        for (c in listOf(
            "read CDM_IOS_UDID <<< $u2; xcrun simctl launch \$CDM_IOS_UDID x",
            "printf -v CDM_IOS_UDID %s $u2; ./t.sh",
            "unset CDM_IOS_UDID; ./t.sh",
        )) {
            assertEquals("Set ANDROID_SERIAL/CDM_IOS_UDID only as a literal NAME=<your device>.", bash(c).reason(), c)
        }
        assertIs<HookReply.Rewrite>(bash("CDM_IOS_UDID=$u1 xcrun simctl launch \$CDM_IOS_UDID x"))
    }

    @Test fun udidTextInArgumentsIsNotAnAssignment() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // holds u1
        for (c in listOf(
            "xcrun simctl openurl booted https://example.com && echo \"CDM_IOS_UDID=\$CDM_IOS_UDID\"",
            "grep CDM_IOS_UDID= file",
            "printf 'CDM_IOS_UDID=%s' x",
        )) {
            assertFalse(bash(c) is HookReply.Deny, c)
        }
    }

    @Test fun realUdidAssignmentFormsDenied() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // holds u1
        for (c in listOf(
            "declare CDM_IOS_UDID=$u2; ./t.sh",
            "echo \${CDM_IOS_UDID:=$u2}; ./t.sh",
            "(CDM_IOS_UDID=$u2 xcrun simctl install \$CDM_IOS_UDID a.app)",
            "CDM_IOS_UDID=\"$u2\" ./t.sh",
        )) {
            assertIs<HookReply.Deny>(bash(c), c)
        }
    }

    @Test fun nestedShellUdidAssignmentsDenied() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // holds u1
        for (c in listOf(
            "bash -c 'CDM_IOS_UDID=$u2 xcrun simctl install \$CDM_IOS_UDID a.app'",
            "sh -c \"CDM_IOS_UDID=$u2 ./t.sh\"",
            "zsh -c 'echo hi; CDM_IOS_UDID=$u2 ./t.sh'",
            "bash -c 'echo CDM_IOS_UDID=$u2'",
            "eval \"CDM_IOS_UDID=$u2\"; ./t.sh",
            "x=\$(CDM_IOS_UDID=$u2 ./t.sh)",
            "echo \$(CDM_IOS_UDID=$u2 xcrun simctl install \$CDM_IOS_UDID a.app)",
            "echo \"\$(CDM_IOS_UDID=$u2 ./t.sh)\"",
            "echo `CDM_IOS_UDID=$u2 ./t.sh`",
            "bash <<EOF\nCDM_IOS_UDID=$u2 ./t.sh\nEOF",
            "./t.sh CDM_IOS_UDID=$u2",
        )) {
            assertIs<HookReply.Deny>(bash(c), c)
        }
    }

    @Test fun exemptionGapsDenied() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // holds u1
        for (c in listOf(
            // Process substitution runs its own command.
            "cat <(CDM_IOS_UDID=$u2 xcrun simctl install \$CDM_IOS_UDID a.app)",
            "echo >(CDM_IOS_UDID=$u2 ./t.sh)",
            // Printed text piped or written into a shell.
            "echo 'CDM_IOS_UDID=$u2 ./t.sh' | sh",
            "printf 'CDM_IOS_UDID=$u2' > f; bash f",
            "grep CDM_IOS_UDID= script | bash",
            "printf 'CDM_IOS_UDID=$u2 ./t.sh' | xargs -I{} sh -c {}",
            "echo CDM_IOS_UDID=$u2 > f; source f",
            "echo CDM_IOS_UDID=$u2 > f; . f",
            "echo 'CDM_IOS_UDID=$u2 ./t.sh' > f.sh; find . -name f.sh -exec sh {} \\;",
            // Git forms that can run a command.
            "git -c alias.x='!CDM_IOS_UDID=$u2 cmd' x",
            "git -c core.sshCommand='CDM_IOS_UDID=$u2 ssh' fetch",
            "git rebase --exec 'CDM_IOS_UDID=$u2 ./t.sh' HEAD~1",
        )) {
            assertIs<HookReply.Deny>(bash(c), c)
        }
        assertFalse(bash("git commit -m \"mention CDM_IOS_UDID=$u2\"") is HookReply.Deny)
        assertFalse(bash("git log --grep CDM_IOS_UDID= && git diff") is HookReply.Deny)
    }

    @Test fun queuedWithoutPlatformIosUsesQueuedCategories() = runTest {
        // Project default (any) would take the iPhone listed first; queued for tablets, the grab takes the iPad.
        allocator.enqueue(h, Wants(setOf(Category.TABLET)))
        assertEquals("export CDM_IOS_UDID=$u2; xcrun simctl install $u2 a.app", bash("xcrun simctl install booted a.app").cmd())
        assertEquals(i2.key, iosHold())
    }

    @Test fun queuedForRealIosOnlySimulatorFreeDenied() = runTest {
        allocator.enqueue(h, Wants(setOf(Category.ANY), Platform.IOS, virtual = false))
        val r = bash("xcrun simctl install booted a.app").reason()
        assertTrue("a device (iOS, real only), #1 in line." in r, r)
        assertTrue("`cdm acquire --category any --ios --real`" in r, r)
        assertEquals(null, iosHold())
        assertTrue(allocator.holdingsOf(h).isEmpty())
    }

    @Test fun queuedForRealIosGrabsRealDeviceOnFirstUse() = runTest {
        val realUdid = "00008110-001A2B3C4D5E6F78"
        val real = AllocDevice(DeviceKey(realUdid), "iPhone Real", Category.PHONE, false, listOf(realUdid), Platform.IOS, virtual = false)
        allocator.setDevices(listOf(p, w, i1, i2, real))
        allocator.enqueue(h, Wants(setOf(Category.ANY), Platform.IOS, virtual = false))
        // The project default (any) would take the simulator listed first; the queued request wants a real device.
        bash("xcrun simctl install \$CDM_IOS_UDID a.app").cmd()
        assertEquals(real.key, iosHold())
    }

    @Test fun queuedForIosAndroidCommandUsesProjectDefault() = runTest {
        allocator.enqueue(h, Wants(setOf(Category.ANY), Platform.IOS, virtual = false))
        assertEquals(pinPrefix("p-usb") + "adb shell ls", bash("adb shell ls").cmd())
    }

    @Test fun udidAssignmentEqualToOwnAllowed() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        val c = "export CDM_IOS_UDID=$u1; xcrun simctl install \$CDM_IOS_UDID a.app"
        assertEquals("export CDM_IOS_UDID=$u1; $c", bash(c).cmd())
    }

    @Test fun udidAssignmentWithoutHoldIsANamedDevice() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE), Platform.IOS)) // s2 holds u1
        assertTrue("s2:main" in bash("CDM_IOS_UDID=$u1 ./t.sh").reason())
        assertTrue(untracked in bash("CDM_IOS_UDID=$untracked ./t.sh").reason())
        assertIs<HookReply.Deny>(bash("CDM_IOS_UDID=\$(pick) ./t.sh"))
        assertEquals(null, iosHold())
        // A free tracked UDID is grabbed, like ANDROID_SERIAL=<free serial>.
        assertEquals("export CDM_IOS_UDID=$u2; CDM_IOS_UDID=$u2 ./t.sh", bash("CDM_IOS_UDID=$u2 ./t.sh").cmd())
        assertEquals(i2.key, iosHold())
    }

    @Test fun iosDenyRollsBackFreshAndroidGrab() = runTest {
        allocator.setDevices(listOf(p, w)) // no iOS device
        assertIs<HookReply.Deny>(bash("adb shell ls && xcrun simctl install booted a.app"))
        assertEquals(emptyList(), allocator.holdingsOf(h))
    }

    @Test fun iosDenyKeepsExistingAndroidHold() = runTest {
        allocator.setDevices(listOf(p, w))
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.ANDROID))
        assertIs<HookReply.Deny>(bash("adb shell ls && xcrun simctl install booted a.app"))
        assertEquals(p.key, allocator.holdingsOf(h).single().device)
    }

    @Test fun iosRegrabTakenDeniesWithReleasedMessage() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS)) // u1
        now = Instant.fromEpochMilliseconds(1_000_000) + kotlin.time.Duration.parse("16m"); allocator.tick()
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE), Platform.IOS)) // s2 takes u1; u2 (tablet) still free
        val r = bash("xcrun simctl install booted a.app").reason()
        assertTrue("released after 15 min idle" in r && "is now in use" in r, r)
        assertTrue("cdm acquire --category phone --ios" in r, r)
        assertEquals(null, iosHold())
    }

    @Test fun iosRegrabAfterIdle() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.TABLET), Platform.IOS)) // u2
        now = Instant.fromEpochMilliseconds(1_000_000) + kotlin.time.Duration.parse("16m"); allocator.tick()
        assertEquals("export CDM_IOS_UDID=$u2; xcrun simctl install $u2 a.app", bash("xcrun simctl install booted a.app").cmd())
    }

    @Test fun pinPrefixForms() {
        assertEquals("export ANDROID_SERIAL=x; ", pinPrefix("x"))
        assertEquals("export ANDROID_SERIAL=x; ", pinPrefix("x", null, null))
        assertEquals("export CDM_IOS_UDID=u; export CDM_IOS_PAIR_UDID=w; ", pinPrefix(null, "u", "w"))
        assertEquals("", pinPrefix(null, null, null))
    }

    // ---- final whole-branch review ----

    @Test fun maestroWithoutFlagFromIosOnlyHolderDenied() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        val r = bash("maestro test flow.yaml").reason()
        assertTrue("maestro --device \$CDM_IOS_UDID" in r && "--android" in r, r)
        assertEquals(listOf(Platform.IOS), allocator.holdingsOf(h).map { it.platform })
    }

    @Test fun maestroWithoutFlagFromNonHolderGrabsAndroid() = runTest {
        assertEquals(pinPrefix("p-usb") + "maestro test flow.yaml", bash("maestro test flow.yaml").cmd())
        assertEquals(listOf(Platform.ANDROID), allocator.holdingsOf(h).map { it.platform })
    }

    @Test fun maestroWithoutFlagHoldingBothPinsAndroid() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.ANDROID))
        assertEquals(pinPrefix("p-usb", u1, null) + "maestro test flow.yaml", bash("maestro test flow.yaml").cmd())
    }

    @Test fun maestroWithUdidIsIos() = runTest {
        assertEquals(
            "export CDM_IOS_UDID=$u1; maestro --device \$CDM_IOS_UDID test f.yaml",
            bash("maestro --device \$CDM_IOS_UDID test f.yaml").cmd(),
        )
        assertEquals(listOf(Platform.IOS), allocator.holdingsOf(h).map { it.platform })
        allocator.tryGrab(h2, Wants(setOf(Category.TABLET), Platform.IOS)) // s2 holds u2
        assertTrue("you hold" in bash("maestro --device $u2 test f.yaml").reason())
    }

    @Test fun xcrunLogSimctlEraseForeignDenied() = runTest {
        allocator.tryGrab(h2, Wants(setOf(Category.PHONE), Platform.IOS)) // s2 holds u1
        assertTrue("s2:main" in bash("xcrun -log simctl erase $u1").reason())
        allocator.tryGrab(h, Wants(setOf(Category.TABLET), Platform.IOS)) // s1 holds u2
        assertTrue("you hold" in bash("xcrun -log simctl erase $u1").reason())
    }

    @Test fun xcodebuildMacOsTestAllowed() = runTest {
        val c = "xcodebuild test -scheme MacApp -destination 'platform=macOS'"
        assertEquals(HookReply.NoOpinion, bash(c))
        assertEquals(HookReply.NoOpinion, bash("xcodebuild test -scheme App -destination 'platform=macOS,variant=Mac Catalyst'"))
        assertEquals(null, iosHold())
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        assertEquals("export CDM_IOS_UDID=$u1; $c", bash(c).cmd())
    }

    @Test fun iosHintsSayIos() = runTest {
        allocator.tryGrab(h, Wants(setOf(Category.PHONE), Platform.IOS))
        allocator.releaseDevice(i1.key, block = false)
        val r = bash("xcrun simctl install booted a.app").reason()
        assertTrue("`cdm acquire --ios`" in r, r)
    }

    @Test fun iosDenyRollbackFiresNoGrabOrReleaseEvents() = runTest {
        allocator.setDevices(listOf(p, w)) // no iOS device
        events.clear()
        assertIs<HookReply.Deny>(bash("adb shell ls && xcrun simctl install booted a.app"))
        assertEquals(emptyList(), allocator.holdingsOf(h))
        assertEquals(emptyList(), events.filter { it is AllocatorEvent.Granted || it is AllocatorEvent.Released })
        // A grab that sticks still fires its event.
        assertIs<HookReply.Rewrite>(bash("adb shell ls"))
        assertEquals(1, events.count { it is AllocatorEvent.Granted })
    }
}
