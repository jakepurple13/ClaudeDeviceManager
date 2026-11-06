package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.AllocDevice
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
    private val allocator = Allocator(clock = { Instant.fromEpochMilliseconds(1_000_000) }).also { it.setDevices(listOf(p, w, i1, i2)) }
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
            "maestro test flow.yaml",
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

    @Test fun pinPrefixForms() {
        assertEquals("export ANDROID_SERIAL=x; ", pinPrefix("x"))
        assertEquals("export ANDROID_SERIAL=x; ", pinPrefix("x", null, null))
        assertEquals("export CDM_IOS_UDID=u; export CDM_IOS_PAIR_UDID=w; ", pinPrefix(null, "u", "w"))
        assertEquals("", pinPrefix(null, null, null))
    }
}
