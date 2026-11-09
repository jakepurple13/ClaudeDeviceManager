package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.Allocator
import com.programmersbox.cdm.core.allocator.GrabSetResult
import com.programmersbox.cdm.core.allocator.SetRequest
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SetHookRulesTest {
    private val u1 = "A1B2C3D4-1111-2222-3333-444455556666"
    private val u2 = "B1B2C3D4-1111-2222-3333-444455556666"

    private val p1 = AllocDevice(DeviceKey("P1"), "Pixel 1", Category.PHONE, false, listOf("p1-usb"))
    private val p2 = AllocDevice(DeviceKey("P2"), "Pixel 2", Category.PHONE, false, listOf("p2-usb"))
    private val p3 = AllocDevice(DeviceKey("P3"), "Pixel 3", Category.PHONE, false, listOf("p3-usb"))
    private val i1 = ios(u1, "iPhone 15")
    private val i2 = ios(u2, "iPhone 16")

    private fun ios(udid: String, name: String) =
        AllocDevice(DeviceKey(udid), name, Category.PHONE, false, listOf(udid), Platform.IOS, virtual = true)

    private val h = HolderId("s1")
    private val h2 = HolderId("s2")
    private val cwd = "/w"
    private val t0 = Instant.fromEpochMilliseconds(1_000_000)
    private var now = t0
    private var pinScript: Path? = null
    private val allocator = Allocator(clock = { now }).also { it.setDevices(listOf(p1, p2, p3, i1, i2)) }
    private val decider = HookDecider(allocator, iosPinScript = { pinScript }) { Category.PHONE }

    private val androidPhone = Wants(setOf(Category.PHONE), platform = Platform.ANDROID)
    private val iosPhone = Wants(setOf(Category.PHONE), platform = Platform.IOS)

    private suspend fun bash(cmd: String, holder: HolderId = h) = decider.onBash(holder, cwd, cmd)
    private fun HookReply.cmd() = assertIs<HookReply.Rewrite>(this).also { assertEquals(RewriteKind.PIN, it.kind) }.command
    private fun HookReply.reason() = assertIs<HookReply.Deny>(this).reason

    /** Holds p1 (android1) and p2 (android2), plus [extra] members. */
    private fun holdSet(vararg extra: Wants) {
        allocator.setDevices(listOf(p1, p2, i1, i2, p3))
        val req = SetRequest(listOf(androidPhone, androidPhone) + extra)
        assertIs<GrabSetResult.Granted>(allocator.tryGrabSet(h, req))
    }

    private val vars = "export CDM_ANDROID1_SERIAL=p1-usb; export CDM_ANDROID2_SERIAL=p2-usb; "

    @Test fun runPinsNamedAndroidMember() = runTest {
        holdSet()
        assertEquals(
            "( export ANDROID_SERIAL=p2-usb; export CDM_ANDROID1_SERIAL=p1-usb; export CDM_ANDROID2_SERIAL=p2-usb; adb shell ls )",
            bash("cdm run android2 -- adb shell ls").cmd(),
        )
    }

    @Test fun chainedRunsPinEachSegment() = runTest {
        holdSet()
        assertEquals(
            "( export ANDROID_SERIAL=p1-usb; ${vars}adb install a.apk ) && " +
                "( export ANDROID_SERIAL=p2-usb; ${vars}adb install a.apk )",
            bash("cdm run android1 -- adb install a.apk && cdm run android2 -- adb install a.apk").cmd(),
        )
    }

    @Test fun runInPipelineKeepsPipe() = runTest {
        holdSet()
        assertEquals(
            // `grep x` is a segment of its own: it gets what `grep x` alone would get (the sentinel).
            "export ANDROID_SERIAL=use-cdm-run; ${vars}( export ANDROID_SERIAL=p1-usb; ${vars}adb logcat ) | grep x",
            bash("cdm run android1 -- adb logcat | grep x").cmd(),
        )
    }

    @Test fun runDeniesOtherSerial() = runTest {
        holdSet()
        bash("cdm run android1 -- adb -s p2-usb shell ls").reason()
        bash("cdm run android1 -- ANDROID_SERIAL=p2-usb adb shell ls").reason()
        bash("cdm run android1 -- adb -s p3-usb shell ls").reason()
        bash("cdm run android1 -- adb -s \$CDM_ANDROID2_SERIAL shell ls").reason()
        assertIs<HookReply.Rewrite>(bash("cdm run android1 -- adb -s p1-usb shell ls"))
        assertIs<HookReply.Rewrite>(bash("cdm run android1 -- adb -s \$CDM_ANDROID1_SERIAL shell ls"))
    }

    @Test fun runDeniesDangerousAndTransportId() = runTest {
        holdSet()
        assertEquals(HookDecider.AFFECTS_OTHERS, bash("cdm run android1 -- adb kill-server").reason())
        assertEquals(HookDecider.TRANSPORT_ID, bash("cdm run android1 -- adb -t 3 shell ls").reason())
    }

    @Test fun runDeniesWrongPlatform() = runTest {
        holdSet(iosPhone)
        bash("cdm run android1 -- xcrun simctl launch booted x").reason()
        bash("cdm run ios1 -- adb shell ls").reason()
    }

    @Test fun runAllExportsCommaList() = runTest {
        holdSet()
        assertEquals(
            "( unset CDM_IOS_UDID CDM_IOS_PAIR_UDID; export ANDROID_SERIAL=p1-usb,p2-usb; ${vars}./gradlew connectedDebugAndroidTest )",
            bash("cdm run all -- ./gradlew connectedDebugAndroidTest").cmd(),
        )
        bash("cdm run all -- adb -s p3-usb shell ls").reason()
        assertIs<HookReply.Rewrite>(bash("cdm run all -- adb -s p2-usb shell ls"))
    }

    @Test fun runAllDeniesKmpIos() = runTest {
        pinScript = Path("/p/ios-pin.gradle")
        holdSet(iosPhone)
        assertEquals(
            "Inside `cdm run all`, KMP iOS tests can't pick a device. Use `cdm run ios<N> -- …`.",
            bash("cdm run all -- ./gradlew iosSimulatorArm64Test").reason(),
        )
    }

    @Test fun runAllIosNeedsMemberUdid() = runTest {
        holdSet(iosPhone, iosPhone)
        bash("cdm run all -- xcrun simctl launch booted x").reason()
        bash("cdm run all -- xcrun simctl launch \$CDM_IOS_UDID x").reason()
        val r = bash("cdm run all -- xcrun simctl launch \$CDM_IOS2_UDID x").cmd()
        assertTrue(r.endsWith("xcrun simctl launch $u2 x )"), r)
        assertTrue("export CDM_IOS_UDID=" !in r, r)
    }

    @Test fun runAllDeniesBareMaestro() = runTest {
        holdSet()
        assertEquals(
            "Name a member: `maestro --device \$CDM_ANDROID<N>_SERIAL` (or `\$CDM_IOS<N>_UDID`).",
            bash("cdm run all -- maestro test flow.yaml").reason(),
        )
        assertTrue(bash("cdm run all -- maestro --device \$CDM_ANDROID2_SERIAL test flow.yaml").cmd().endsWith("maestro --device p2-usb test flow.yaml )"))
        assertIs<HookReply.Rewrite>(bash("cdm run android1 -- maestro test flow.yaml"))
    }

    @Test fun runIosExportsUdidBeforeArgs() = runTest {
        holdSet(iosPhone)
        val r = bash("cdm run ios1 -- xcrun simctl launch \$CDM_IOS_UDID x").cmd()
        assertTrue(r.startsWith("( export CDM_IOS_UDID=$u1; "), r)
        assertTrue(r.indexOf("export CDM_IOS_UDID=$u1;") < r.indexOf("xcrun"), r)
        assertTrue("export CDM_IOS1_UDID=$u1;" in r, r)
    }

    @Test fun runIosRewritesBootedAndAddsInitScript() = runTest {
        pinScript = Path("/p/ios-pin.gradle")
        holdSet(iosPhone)
        assertTrue(bash("cdm run ios1 -- xcrun simctl install booted a.app").cmd().endsWith("xcrun simctl install $u1 a.app )"))
        assertTrue(
            bash("cdm run ios1 -- ./gradlew iosSimulatorArm64Test").cmd()
                .endsWith("./gradlew --init-script '/p/ios-pin.gradle' iosSimulatorArm64Test )"),
        )
        bash("cdm run ios1 -- xcrun simctl launch $u2 x").reason()
    }

    @Test fun runOnSingleHolderUsesAndroid1() = runTest {
        bash("adb -s p1-usb shell ls")
        assertEquals("( export ANDROID_SERIAL=p1-usb; adb shell ls )", bash("cdm run android1 -- adb shell ls").cmd())
        val r = bash("cdm run android2 -- adb shell ls").reason()
        assertTrue("android1" in r, r)
    }

    @Test fun runOnSingleHolderAfterTimeoutRegrabs() = runTest {
        bash("adb -s p1-usb shell ls")
        now = t0 + 16.minutes; allocator.tick()
        assertEquals("( export ANDROID_SERIAL=p1-usb; adb shell ls )", bash("cdm run android1 -- adb shell ls").cmd())
    }

    @Test fun runOnSingleHolderAfterTimeoutTakenUsesTodaysText() = runTest {
        bash("adb -s p1-usb shell ls")
        now = t0 + 16.minutes; allocator.tick()
        allocator.tryGrab(h2, Wants(emptySet()), p1.key)
        val r = bash("cdm run android1 -- adb shell ls").reason()
        assertTrue("released after 15 min idle" in r && "is now in use" in r, r)
    }

    @Test fun runUnknownNameListsNames() = runTest {
        holdSet()
        val r = bash("cdm run android3 -- adb shell ls").reason()
        assertTrue("android1" in r && "android2" in r, r)
    }

    @Test fun runMissingNameOrCommandShowsUsage() = runTest {
        holdSet()
        assertTrue("cdm run <name> -- <command>" in bash("cdm run android1").reason())
        assertTrue("cdm run <name> -- <command>" in bash("cdm run -- adb shell ls").reason())
        assertTrue("cdm run <name> -- <command>" in bash("cdm run android1 --").reason())
    }

    @Test fun runFromNobodyDenied() = runTest {
        assertEquals("You hold no devices. Run `cdm acquire` first.", bash("cdm run android1 -- adb shell ls").reason())
    }

    @Test fun runRegrabsAfterTimeout() = runTest {
        holdSet()
        now = t0 + 16.minutes; allocator.tick()
        assertNull(allocator.setOf(h))
        assertEquals(
            "( export ANDROID_SERIAL=p2-usb; ${vars}adb shell ls )",
            bash("cdm run android2 -- adb shell ls").cmd(),
        )
        assertNotNull(allocator.setOf(h))
    }

    @Test fun runDeniesWhenRegrabFails() = runTest {
        holdSet()
        now = t0 + 16.minutes; allocator.tick()
        allocator.tryGrab(h2, Wants(emptySet()), p2.key)
        assertEquals(
            "Your devices timed out and one was taken; run `cdm acquire` again.",
            bash("cdm run android1 -- adb shell ls").reason(),
        )
        assertNull(allocator.setOf(h))
    }

    @Test fun deniedRunAfterTimeoutTakesNothingBack() = runTest {
        holdSet()
        now = t0 + 16.minutes; allocator.tick()
        val r = bash("cdm run android9 -- adb shell ls").reason()
        assertTrue("android1" in r && "android2" in r, r)
        bash("cdm run android1 -- adb -s p3-usb shell ls").reason()
        bash("adb -s p1-usb shell ls && adb -s p2-usb shell ls").reason()
        assertNull(allocator.setOf(h))
        assertTrue(allocator.snapshot().holds.isEmpty())
        // The timed-out set can still come back.
        assertNotNull(allocator.releasedSetOf(h))
        assertIs<HookReply.Rewrite>(bash("cdm run android1 -- adb shell ls"))
        assertNotNull(allocator.setOf(h))
    }

    @Test fun deniedRunAfterSingleTimeoutTakesNothingBack() = runTest {
        bash("adb -s p1-usb shell ls")
        now = t0 + 16.minutes; allocator.tick()
        bash("cdm run android1 -- adb -s p3-usb shell ls").reason()
        assertTrue(allocator.holdingsOf(h).isEmpty())
        assertEquals("( export ANDROID_SERIAL=p1-usb; adb shell ls )", bash("cdm run android1 -- adb shell ls").cmd())
    }

    @Test fun explicitMemberSerialRegrabsAfterTimeout() = runTest {
        holdSet()
        now = t0 + 16.minutes; allocator.tick()
        assertEquals("export ANDROID_SERIAL=p1-usb; ${vars}adb -s p1-usb shell ls", bash("adb -s p1-usb shell ls").cmd())
        assertNotNull(allocator.setOf(h))
    }

    @Test fun runCountsAsSetActivity() = runTest {
        holdSet()
        now = t0 + 10.minutes
        bash("cdm run android1 -- adb shell ls")
        assertEquals(t0 + 10.minutes, allocator.setOf(h)?.lastActivity)
    }

    @Test fun bareDeviceCommandFromSetHolderDenied() = runTest {
        holdSet()
        val r = bash("adb shell ls").reason()
        assertTrue("android1" in r && "android2" in r && "cdm run" in r, r)
        bash("./gradlew connectedDebugAndroidTest").reason()
    }

    @Test fun explicitMemberSerialAllowed() = runTest {
        holdSet()
        assertEquals("export ANDROID_SERIAL=p2-usb; ${vars}adb -s p2-usb shell ls", bash("adb -s p2-usb shell ls").cmd())
        assertEquals(
            "export ANDROID_SERIAL=p1-usb; ${vars}ANDROID_SERIAL=p1-usb ./gradlew connectedCheck",
            bash("ANDROID_SERIAL=p1-usb ./gradlew connectedCheck").cmd(),
        )
        bash("adb -s p1-usb shell ls && adb -s p2-usb shell ls").reason()
        bash("adb -s p3-usb shell ls").reason()
    }

    @Test fun singleMemberPlatformPinnedAsToday() = runTest {
        holdSet(iosPhone)
        val r = bash("xcrun simctl install booted a.app").cmd()
        assertEquals("export ANDROID_SERIAL=use-cdm-run; export CDM_IOS_UDID=$u1; ${vars}export CDM_IOS1_UDID=$u1; xcrun simctl install $u1 a.app", r)
    }

    @Test fun otherCommandGetsSentinel() = runTest {
        holdSet()
        val r = bash("./run-test.sh").cmd()
        assertTrue(r.startsWith("export ANDROID_SERIAL=use-cdm-run; "), r)
        assertTrue("export CDM_ANDROID2_SERIAL=p2-usb;" in r && r.endsWith("./run-test.sh"), r)
        assertTrue("CDM_IOS_UDID" !in r, r)
    }

    @Test fun otherCommandWithTwoIosMembersGetsIosSentinel() = runTest {
        holdSet(iosPhone, iosPhone)
        val r = bash("./run-test.sh").cmd()
        assertTrue("export CDM_IOS_UDID=use-cdm-run;" in r, r)
        assertTrue("export CDM_IOS2_UDID=$u2;" in r, r)
    }

    @Test fun cdmCommandFromSetHolderKeepsIdentityRewrite() = runTest {
        holdSet()
        val r = assertIs<HookReply.Rewrite>(bash("cdm status"))
        assertEquals("cdm status --holder s1:main --cwd '/w'", r.command)
        assertEquals(RewriteKind.CDM, r.kind)
    }

    @Test fun mixedRunAndDeviceSegmentDenied() = runTest {
        holdSet()
        assertEquals(
            "Put each device command in its own `cdm run <name> -- …`.",
            bash("cdm run android1 -- adb shell ls && adb shell ls").reason(),
        )
    }

    @Test fun otherSegmentsOfRunCommandChecked() = runTest {
        holdSet()
        bash("ANDROID_SERIAL=p3-usb ./x.sh && cdm run android1 -- adb shell ls").reason()
        assertEquals(HookDecider.AFFECTS_OTHERS, bash("adb disconnect 10.0.0.9:5555 && cdm run android1 -- adb shell ls").reason())
        assertEquals(HookDecider.AFFECTS_OTHERS, bash("adb kill-server; cdm run android1 -- adb shell ls").reason())
    }

    @Test fun pathFormCdmRunAccepted() = runTest {
        holdSet()
        assertEquals(
            "( export ANDROID_SERIAL=p2-usb; ${vars}adb shell ls )",
            bash("/usr/local/bin/cdm run android2 -- adb shell ls").cmd(),
        )
        assertEquals(
            "( export ANDROID_SERIAL=p1-usb; ${vars}adb shell ls )",
            bash("composeApp/src/jvmMain/resources/cdm run android1 -- adb shell ls").cmd(),
        )
        val wrapped = "Run `cdm run` directly, not through sudo/env or a VAR= prefix."
        assertEquals(wrapped, bash("sudo /usr/local/bin/cdm run android1 -- adb shell ls").reason())
        assertEquals(wrapped, bash("env ./cdm run android1 -- adb shell ls").reason())
        assertEquals(wrapped, bash("FOO=1 ./cdm run android1 -- adb shell ls").reason())
    }

    private val memberVarDeny = "Don't set the CDM_ANDROID<N>/CDM_IOS<N> variables; Device Manager exports them for your devices."

    @Test fun settingMemberVariableDeniedWithoutRun() = runTest {
        holdSet(iosPhone)
        assertEquals(memberVarDeny, bash("export CDM_ANDROID1_SERIAL=p3-usb; adb -s \$CDM_ANDROID1_SERIAL shell ls").reason())
        assertEquals(memberVarDeny, bash("CDM_ANDROID1_SERIAL=p3-usb adb -s \$CDM_ANDROID1_SERIAL shell ls").reason())
        assertEquals(memberVarDeny, bash("adb -s \${CDM_ANDROID1_SERIAL:-p3-usb} shell ls").reason())
        assertEquals(memberVarDeny, bash("read CDM_ANDROID1_SERIAL; adb -s \$CDM_ANDROID1_SERIAL shell ls").reason())
        assertEquals(memberVarDeny, bash("export CDM_IOS1_UDID=$u2; xcrun simctl launch \$CDM_IOS1_UDID x").reason())
        assertEquals(memberVarDeny, bash("unset CDM_IOS1_UDID").reason())
        // Plain reads stay allowed.
        assertIs<HookReply.Rewrite>(bash("adb -s \$CDM_ANDROID1_SERIAL shell ls"))
        assertIs<HookReply.Rewrite>(bash("xcrun simctl launch \${CDM_IOS1_UDID} x"))
    }

    @Test fun settingMemberVariableDeniedInRun() = runTest {
        holdSet(iosPhone)
        assertEquals(memberVarDeny, bash("cdm run android1 -- CDM_ANDROID1_SERIAL=p3-usb adb -s \$CDM_ANDROID1_SERIAL shell ls").reason())
        assertEquals(memberVarDeny, bash("export CDM_ANDROID2_SERIAL=p3-usb; cdm run all -- adb -s \$CDM_ANDROID2_SERIAL shell ls").reason())
        assertEquals(memberVarDeny, bash("cdm run ios1 -- CDM_IOS1_UDID=$u2 xcrun simctl launch \$CDM_IOS1_UDID x").reason())
        assertEquals(memberVarDeny, bash("cdm run all -- xcrun simctl launch \${CDM_IOS1_UDID:=$u2} x").reason())
    }

    /** Split or escaped member names are flattened before the scan, so writing one is denied like the plain name. */
    @Test fun splitMemberNameWritesDenied() = runTest {
        holdSet(iosPhone)
        val writes = listOf(
            "export CDM_ANDROID\"1\"_SERIAL=x; ./script.sh",
            "export CDM_ANDROID1_\"SERIAL\"=p3-usb",
            "CDM_ANDROID1_\\SERIAL=p3-usb",
            "declare CDM_ANDROID1_\"SERIAL\"=p3-usb",
            "typeset CDM_ANDROID1_\"SERIAL\"=p3-usb",
            "readonly CDM_ANDROID1_\"SERIAL\"=p3-usb",
            "local CDM_ANDROID1_\"SERIAL\"=p3-usb",
            "printf -v CDM_ANDROID1_\"SERIAL\" p3-usb",
            "declare -n r=CDM_ANDROID1_\"SERIAL\"; r=p3-usb",
            "eval CDM_ANDROID1_\"SERIAL\"=p3-usb",
        )
        for (w in writes) {
            assertEquals(memberVarDeny, bash("$w; adb -s \$CDM_ANDROID1_SERIAL shell ls").reason(), w)
            assertEquals(memberVarDeny, bash("$w; cdm run android1 -- adb shell ls").reason(), w)
        }
        assertEquals(
            "Set ANDROID_SERIAL/CDM_IOS_UDID only as a literal NAME=<your device>.",
            bash("read ANDROID_SERIAL <<< p3-usb; cdm run android1 -- adb shell ls").reason(),
        )
        bash("export ANDROID_\"SERIAL\"=p3-usb; ./t.sh").reason()
    }

    /** Names no scan can see: the command that runs uses the literal member value, so rebinding is harmless. */
    @Test fun memberReferencesBecomeLiteralWithoutRun() = runTest {
        holdSet(iosPhone)
        val rebinds = listOf(
            "n=SERIAL; export CDM_ANDROID1_\$n=p3-usb",
            "source ./f",
        )
        for (rebind in rebinds) {
            val r = bash("$rebind; adb -s \$CDM_ANDROID1_SERIAL shell ls").cmd()
            assertTrue(r.endsWith("; adb -s p1-usb shell ls"), r)
            assertTrue(r.startsWith("export ANDROID_SERIAL=p1-usb; "), r)
            val i = bash("$rebind; m=UDID; export CDM_IOS1_\$m=$u2; xcrun simctl launch \${CDM_IOS1_UDID} x").cmd()
            assertTrue(i.endsWith("; xcrun simctl launch $u1 x"), i)
        }
    }

    @Test fun memberReferencesBecomeLiteralInRun() = runTest {
        holdSet(iosPhone)
        assertEquals(
            "export ANDROID_SERIAL=use-cdm-run; export CDM_IOS_UDID=$u1; ${vars}export CDM_IOS1_UDID=$u1; " +
                "n=SERIAL; export CDM_ANDROID1_\$n=p3-usb; " +
                "( export ANDROID_SERIAL=p1-usb; ${vars}export CDM_IOS1_UDID=$u1; adb -s p1-usb shell ls )",
            bash("n=SERIAL; export CDM_ANDROID1_\$n=p3-usb; cdm run android1 -- adb -s \$CDM_ANDROID1_SERIAL shell ls").cmd(),
        )
        val r = bash("source ./f; cdm run ios1 -- xcrun simctl launch \$CDM_IOS1_UDID x").cmd()
        assertTrue(r.endsWith("xcrun simctl launch $u1 x )"), r)
        val all = bash("source ./g; cdm run all -- adb -s \"\$CDM_ANDROID2_SERIAL\" shell ls").cmd()
        assertTrue(all.endsWith("adb -s \"p2-usb\" shell ls )"), all)
    }

    @Test fun singleQuotedMemberReferenceUnchanged() = runTest {
        holdSet()
        assertTrue(bash("cdm run android1 -- adb shell echo '\$CDM_ANDROID1_SERIAL'").cmd().endsWith("adb shell echo '\$CDM_ANDROID1_SERIAL' )"))
        assertTrue(bash("echo '\$CDM_ANDROID1_SERIAL' x").cmd().endsWith("echo '\$CDM_ANDROID1_SERIAL' x"))
        // A backslash-escaped name isn't a plain read: the name scan denies it (fails closed).
        bash("echo \\\$CDM_ANDROID2_SERIAL").reason()
        assertTrue(bash("echo \"\$CDM_ANDROID1_SERIAL\"").cmd().endsWith("echo \"p1-usb\""))
    }

    @Test fun queuedSetHolderDoesNotGrabOnFirstUse() = runTest {
        allocator.tryGrab(h2, Wants(emptySet()), p1.key)
        allocator.tryGrab(HolderId("s3"), Wants(emptySet()), p2.key)
        assertEquals(1, allocator.enqueueSet(h, SetRequest(listOf(androidPhone, androidPhone))))
        val expected = "You're waiting for a device set (#1 in line). Run `cdm acquire` again to keep waiting, " +
            "or `cdm release` to leave the queue."
        assertEquals(expected, bash("adb -s p3-usb shell ls").reason())
        assertEquals(expected, bash("xcrun simctl launch $u1 x").reason())
        assertEquals(expected, bash("cdm run android1 -- adb shell ls").reason())
        assertTrue(allocator.holdingsOf(h).isEmpty())
        assertNotNull(assertIs<com.programmersbox.cdm.core.allocator.HolderStatus.Queued>(allocator.statusOf(h)).set)
        assertEquals(HookReply.NoOpinion, bash("ls"))
    }

    @Test fun runAllUnsetsAmbientIosUdid() = runTest {
        holdSet(iosPhone)
        val r = bash("cd app && cdm run all -- ./gradlew connectedCheck").cmd()
        assertTrue(r.startsWith("export ANDROID_SERIAL=use-cdm-run; export CDM_IOS_UDID=$u1; "), r)
        assertTrue("&& ( unset CDM_IOS_UDID CDM_IOS_PAIR_UDID; export ANDROID_SERIAL=p1-usb,p2-usb; " in r, r)
    }

    @Test fun commentInRunClosesSubshellOnNewLine() = runTest {
        holdSet()
        assertEquals(
            "( export ANDROID_SERIAL=p1-usb; ${vars}adb shell ls # note\n)",
            bash("cdm run android1 -- adb shell ls # note").cmd(),
        )
        assertEquals(
            "( export ANDROID_SERIAL=p1-usb; ${vars}adb shell ls # note\n)\n( export ANDROID_SERIAL=p2-usb; ${vars}adb shell ls )",
            bash("cdm run android1 -- adb shell ls # note\ncdm run android2 -- adb shell ls").cmd(),
        )
        // On one line, the second run was part of the comment: rewriting would make it run.
        bash("cdm run android1 -- adb shell ls # note && cdm run android2 -- adb reboot").reason()
        // Not comments: `#` inside quotes or a word.
        assertTrue(bash("cdm run android1 -- adb shell 'echo #x' a#b").cmd().endsWith("a#b )"))
    }

    @Test fun runChainedWithOtherAndCdmSegments() = runTest {
        holdSet()
        assertEquals(
            "export ANDROID_SERIAL=use-cdm-run; ${vars}cd app && ( export ANDROID_SERIAL=p1-usb; ${vars}adb shell ls ) && " +
                "cdm status --holder s1:main --cwd '/w'",
            bash("cd app && cdm run android1 -- adb shell ls && cdm status").cmd(),
        )
    }

    @Test fun groupedRunDenied() = runTest {
        holdSet()
        val grouped = "Don't wrap `cdm run` in ( ) or { }; run it as its own command."
        assertEquals(grouped, bash("(cdm run android1 -- adb shell ls)").reason())
        assertEquals(grouped, bash("{ cdm run android1 -- adb shell ls; }").reason())
        assertEquals(grouped, bash("( cd x; cdm run android1 -- adb shell ls )").reason())
        assertIs<HookReply.Rewrite>(bash("cdm run android1 -- adb shell 'echo \$(date)'"))
    }

    @Test fun wrappedRunDenied() = runTest {
        holdSet()
        val wrapped = "Run `cdm run` directly, not through sudo/env or a VAR= prefix."
        assertEquals(wrapped, bash("sudo cdm run android1 -- adb shell ls").reason())
        assertEquals(wrapped, bash("FOO=1 cdm run android1 -- adb shell ls").reason())
        assertEquals(wrapped, bash("env cdm run android1 -- adb shell ls").reason())
    }

    @Test fun nonSetHolderWithoutRunUnchanged() = runTest {
        assertEquals(HookReply.NoOpinion, bash("ls"))
        assertEquals("export ANDROID_SERIAL=p1-usb; adb -s p1-usb shell ls", bash("adb -s p1-usb shell ls").cmd())
    }
}
