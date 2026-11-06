package com.programmersbox.cdm.core.classify

import com.programmersbox.cdm.core.classify.CommandKind.*
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals

class IosCommandClassifierTest {
    private val u = "A1B2C3D4-1111-2222-3333-444455556666"
    private val real = "00008110-001A2B3C4D5E6F78"

    private fun k(c: String) = CommandClassifier.classify(c)

    @Test fun xcodebuildDestinationId() =
        assertEquals(IosDevice(listOf(u), false, true), k("xcodebuild test -destination 'id=$u'"))

    @Test fun xcodebuildDestinationByName() =
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test -destination 'platform=iOS Simulator,name=iPhone 16'"))

    @Test fun xcodebuildSafe() {
        assertEquals(IosGlobalSafe, k("xcodebuild -list"))
        assertEquals(IosGlobalSafe, k("xcodebuild build -scheme App"))
        assertEquals(IosGlobalSafe, k("xcodebuild build -destination 'generic/platform=iOS'"))
    }

    @Test fun xcodebuildTestWithoutDestination() =
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test -scheme App"))

    @Test fun simctl() {
        assertEquals(IosDevice(emptyList(), true, true), k("xcrun simctl install booted App.app"))
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun simctl boot $u"))
        assertEquals(IosGlobalSafe, k("xcrun simctl list"))
        assertEquals(IosGlobalSafe, k("xcrun simctl help"))
        assertEquals(IosDevice(emptyList(), true, true), k("sudo env X=1 timeout 30 xcrun simctl launch booted com.x"))
    }

    @Test fun dangerous() {
        assertEquals(IosGlobalDangerous("simctl shutdown all"), k("xcrun simctl shutdown all"))
        assertEquals(IosGlobalDangerous("simctl erase all"), k("xcrun simctl erase all"))
        assertEquals(IosGlobalDangerous("simctl delete"), k("xcrun simctl delete $u"))
        assertEquals(IosGlobalDangerous("killall Simulator"), k("killall Simulator"))
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun simctl shutdown $u"))
    }

    @Test fun devicectl() {
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun devicectl device install app --device $u x.app"))
        assertEquals(IosGlobalSafe, k("xcrun devicectl list devices"))
    }

    @Test fun xcrunFind() = assertEquals(IosGlobalSafe, k("xcrun --find simctl"))

    @Test fun maestro() {
        assertEquals(IosDevice(emptyList(), false, true), k("maestro --device \$CDM_IOS_UDID test f.yaml"))
        assertEquals(IosDevice(emptyList(), false, true), k("maestro --device \"\${CDM_IOS_UDID}\" test f.yaml"))
        assertEquals(IosDevice(listOf(u), false, true), k("maestro --device $u test f.yaml"))
        assertEquals(IosDevice(listOf(real), false, true), k("maestro --udid=$real test f.yaml"))
        // Single-quoted: Maestro gets the literal text, which names no device.
        assertEquals(IosDevice(emptyList(), false, false), k("maestro --device '\$CDM_IOS_UDID' test f.yaml"))
    }

    /** Final review: Maestro without an iOS selector is Android, as before iOS support. */
    @Test fun maestroAndroid() {
        assertEquals(Device(null, false, false, bareMaestro = true), k("maestro test f.yaml"))
        assertEquals(Device("emulator-5554", false, false), k("maestro --device emulator-5554 test f.yaml"))
        assertEquals(Device("R58M123", false, false), k("maestro --device=R58M123 test f.yaml"))
        assertEquals(Device("p-usb", false, false, bareMaestro = true), k("ANDROID_SERIAL=p-usb maestro test f.yaml"))
        assertEquals(setOf(Platform.ANDROID), CommandClassifier.platformsOf("maestro test f.yaml"))
        assertEquals(setOf(Platform.ANDROID), CommandClassifier.platformsOf("maestro --device emulator-5554 test f.yaml"))
    }

    /** Final review: `-log` takes no argument, so `simctl` after it is still seen. */
    @Test fun xcrunLogFlagTakesNoArgument() {
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun -log simctl erase $u"))
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun --log simctl install $u a.app"))
        assertEquals(IosDevice(emptyList(), true, true), k("xcrun -l simctl install booted a.app"))
        assertEquals(IosDevice(emptyList(), true, true), k("xcrun -sdk iphonesimulator simctl install booted a.app"))
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun -log devicectl device install app --device $u x.app"))
    }

    /** Final review: macOS (and Mac Catalyst) destinations reach no iOS device. */
    @Test fun xcodebuildMacOsDestinations() {
        assertEquals(IosGlobalSafe, k("xcodebuild test -scheme MacApp -destination 'platform=macOS'"))
        assertEquals(IosGlobalSafe, k("xcodebuild test -scheme MacApp -destination 'platform=macOS,arch=arm64'"))
        assertEquals(IosGlobalSafe, k("xcodebuild test -scheme App -destination 'platform=macOS,variant=Mac Catalyst'"))
        assertEquals(IosGlobalSafe, k("xcodebuild test -scheme App -destination 'variant=Mac Catalyst,platform=macOS'"))
        assertEquals(IosGlobalSafe, k("xcodebuild test-without-building -destination platform=macOS -destination 'platform=macOS,variant=Mac Catalyst'"))
        assertEquals(IosGlobalSafe, k("xcodebuild build -destination 'generic/platform=macOS'"))
        // A macOS destination next to an iOS one: only the iOS one selects a device.
        assertEquals(IosDevice(listOf(u), false, true), k("xcodebuild test -destination platform=macOS -destination id=$u"))
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test -destination platform=macOS -destination 'platform=iOS Simulator,name=iPhone 16'"))
    }

    @Test fun idevice() {
        assertEquals(IosDevice(listOf(u), false, true), k("idevicesyslog -u $u"))
        assertEquals(IosDevice(listOf(real), false, true), k("idevicesyslog -u $real"))
    }

    @Test fun fastlane() {
        assertEquals(IosDevice(emptyList(), false, false), k("fastlane scan"))
        assertEquals(Other, k("fastlane beta"))
    }

    @Test fun gradle() {
        assertEquals(IosDevice(emptyList(), false, false), k("./gradlew :shared:iosSimulatorArm64Test"))
        assertEquals(GradleNonDevice, k("./gradlew assembleDebug"))
        assertEquals(Device(null, false, false), k("./gradlew installDebug"))
    }

    @Test fun platforms() {
        assertEquals(
            setOf(Platform.ANDROID, Platform.IOS),
            CommandClassifier.platformsOf("adb install x.apk && xcrun simctl install booted A.app"),
        )
        assertEquals(setOf(Platform.IOS), CommandClassifier.platformsOf("killall Simulator"))
        assertEquals(setOf(Platform.ANDROID), CommandClassifier.platformsOf("adb shell ls"))
        assertEquals(emptySet(), CommandClassifier.platformsOf("xcrun simctl list"))
    }

    @Test fun rewriteBooted() {
        assertEquals("xcrun simctl install $u A.app", CommandClassifier.rewriteBooted("xcrun simctl install booted A.app", u))
        assertEquals("echo booted", CommandClassifier.rewriteBooted("echo booted", u))
        assertEquals("xcrun simctl list booted", CommandClassifier.rewriteBooted("xcrun simctl list booted", u))
        assertEquals(
            "cd x && sudo xcrun simctl spawn $u echo booted; echo booted",
            CommandClassifier.rewriteBooted("cd x && sudo xcrun simctl spawn booted echo booted; echo booted", u),
        )
        assertEquals(
            "FOO=1 xcrun simctl io $u screenshot a.png",
            CommandClassifier.rewriteBooted("FOO=1 xcrun simctl io 'booted' screenshot a.png", u),
        )
    }

    @Test fun pinOnlyFromDeviceSelectingPositions() {
        val name = "-destination 'platform=iOS Simulator,name=iPhone 16'"
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test $name -derivedDataPath out/\$CDM_IOS_UDID"))
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test $name -resultBundlePath out/$u.xcresult"))
        assertEquals(IosDevice(listOf(u), false, true), k("xcrun simctl install $u /tmp/$real.app"))
        assertEquals(IosDevice(emptyList(), true, true), k("xcrun simctl install booted /tmp/$u.app"))
    }

    @Test fun cdmRefQuoting() {
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test -destination 'id=\$CDM_IOS_UDID'"))
        assertEquals(IosDevice(emptyList(), false, true), k("xcodebuild test -destination \"id=\$CDM_IOS_UDID\""))
        assertEquals(IosDevice(emptyList(), false, true), k("xcodebuild test -destination id=\$CDM_IOS_UDID"))
    }

    /** A selector only counts as the held device when its whole value is `$CDM_IOS_UDID`, not merely contains it. */
    @Test fun cdmRefMustBeWholeSelector() {
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test -destination id=foo\$CDM_IOS_UDID"))
        assertEquals(IosDevice(emptyList(), false, false), k("xcodebuild test -destination id=\${CDM_IOS_UDID}x"))
        assertEquals(IosDevice(emptyList(), false, false), k("maestro --device x\$CDM_IOS_UDID test f.yaml"))
        assertEquals(IosDevice(emptyList(), false, false), k("xcrun simctl install \$CDM_IOS_UDID-2 App.app"))
        assertEquals(IosDevice(emptyList(), false, true), k("xcodebuild test -destination id=\${CDM_IOS_UDID}"))
        assertEquals(IosDevice(emptyList(), false, true), k("xcrun simctl install \$CDM_IOS_UDID App.app"))
    }

    @Test fun iosUdidAssignments() {
        val a = CommandClassifier::iosUdidAssignments
        assertEquals(listOf(CommandClassifier.IosAssignment(false, u)), a("export CDM_IOS_UDID=$u; x"))
        assertEquals(listOf(CommandClassifier.IosAssignment(true, u)), a("CDM_IOS_PAIR_UDID='$u' ./t.sh"))
        assertEquals(listOf(CommandClassifier.IosAssignment(false, null)), a("CDM_IOS_UDID=\$(echo x) ./t.sh"))
        assertEquals(listOf(CommandClassifier.IosAssignment(false, null)), a("CDM_IOS_UDID=\"\$X\" ./t.sh"))
        assertEquals(listOf(CommandClassifier.IosAssignment(false, null)), a("CDM_IOS_UDID+=x ./t.sh"))
        assertEquals(listOf(CommandClassifier.IosAssignment(false, "\$X")), a("CDM_IOS_UDID='\$X' ./t.sh"))
        assertEquals(emptyList(), a("MY_CDM_IOS_UDID=$u ./t.sh; echo \$CDM_IOS_UDID"))
    }

    @Test fun iosUdidAssignmentsOnlyRealAssignments() {
        val a = CommandClassifier::iosUdidAssignments
        val lit = listOf(CommandClassifier.IosAssignment(false, u))
        // Text inside another command's argument doesn't assign anything.
        assertEquals(emptyList(), a("xcrun simctl openurl booted https://example.com && echo \"CDM_IOS_UDID=\$CDM_IOS_UDID\""))
        assertEquals(emptyList(), a("grep CDM_IOS_UDID= file"))
        assertEquals(emptyList(), a("printf 'CDM_IOS_UDID=%s' x"))
        assertEquals(emptyList(), a("echo CDM_IOS_PAIR_UDID=$u"))
        assertEquals(emptyList(), a("git commit -m 'set CDM_IOS_UDID=$u'"))
        assertEquals(emptyList(), a("test \"\$X\" = CDM_IOS_UDID=$u && rg -n CDM_IOS_UDID= src | cat"))
        assertEquals(emptyList(), a("/bin/echo CDM_IOS_UDID=$u"))
        // Fails closed: any other command's argument, nested shells and substitutions still count.
        assertEquals(lit, a("./t.sh CDM_IOS_UDID=$u"))
        assertEquals(lit, a("bash -c 'echo CDM_IOS_UDID=$u'"))
        assertEquals(lit, a("eval \"CDM_IOS_UDID=$u\""))
        assertEquals(lit, a("echo \$(CDM_IOS_UDID=$u ./t.sh)"))
        assertEquals(lit, a("echo `CDM_IOS_UDID=$u ./t.sh`"))
        assertEquals(lit, a("CDM_IOS_UDID=$u echo hi"))
        // Assignment-prefix position, through wrappers and grouping.
        assertEquals(lit, a("(CDM_IOS_UDID=$u xcrun simctl install \$CDM_IOS_UDID a.app)"))
        assertEquals(lit, a("{ CDM_IOS_UDID=$u ./t.sh; }"))
        assertEquals(lit, a("sudo -u me CDM_IOS_UDID=$u ./t.sh"))
        assertEquals(lit, a("timeout 30 env CDM_IOS_UDID=$u ./t.sh"))
        assertEquals(lit, a("A=1 CDM_IOS_UDID=\"$u\" ./t.sh"))
        assertEquals(lit, a("ls; CDM_IOS_UDID=$u ./t.sh"))
        // Declaration builtins.
        for (b in listOf("export", "declare", "declare -x", "typeset", "local", "readonly", "env")) {
            assertEquals(lit, a("$b A=1 CDM_IOS_UDID=$u; ./t.sh"), b)
        }
        // ${VAR=…} / ${VAR:=…} assign wherever they appear.
        assertEquals(lit, a("echo \${CDM_IOS_UDID:=$u}"))
        assertEquals(lit, a("echo \"\${CDM_IOS_UDID=$u}\""))
        assertEquals(listOf(CommandClassifier.IosAssignment(true, null)), a(": \${CDM_IOS_PAIR_UDID:=\$(pick)}"))
        assertEquals(emptyList(), a("echo \${CDM_IOS_UDID:-$u} \${CDM_IOS_UDID}"))
    }

    @Test fun multipleDestinations() {
        assertEquals(
            IosDevice(listOf(u), false, false),
            k("xcodebuild test -destination id=$u -destination 'platform=iOS Simulator,name=iPhone 16'"),
        )
        assertEquals(
            IosDevice(listOf(u, real), false, true),
            k("xcodebuild test -destination id=$u -destination id=$real"),
        )
    }

    @Test fun mixedGradleKeepsIos() {
        val kinds = CommandClassifier.classifyAll("./gradlew connectedDebugAndroidTest iosSimulatorArm64Test")
        assertEquals(listOf(Device(null, false, false), IosDevice(emptyList(), false, false)), kinds)
        assertEquals(setOf(Platform.ANDROID, Platform.IOS), CommandClassifier.platformsOf("./gradlew connectedDebugAndroidTest iosSimulatorArm64Test"))
    }

    private val pin = "/Users/me/.claude-device-manager/ios-pin.gradle"

    @Test fun gradleIosTestWithPinInitScriptIsPinnable() {
        val pinned = IosDevice(emptyList(), false, true)
        assertEquals(pinned, k("./gradlew --init-script $pin :shared:iosSimulatorArm64Test"))
        assertEquals(pinned, k("./gradlew :shared:iosSimulatorArm64Test --init-script '$pin'"))
        assertEquals(pinned, k("./gradlew --init-script=$pin iosX64Test"))
        assertEquals(pinned, k("./gradlew -I $pin iosSimulatorArm64Test"))
        assertEquals(pinned, k("gradle -I$pin iosSimulatorArm64Test"))
    }

    @Test fun gradleIosTestOtherInitScriptStaysUnpinnable() {
        assertEquals(IosDevice(emptyList(), false, false), k("./gradlew --init-script /tmp/other.gradle iosSimulatorArm64Test"))
        // The script name only counts as an init-script argument, not as a task or a property value.
        assertEquals(IosDevice(emptyList(), false, false), k("./gradlew -Pfoo=$pin iosSimulatorArm64Test"))
    }

    @Test fun gradleDeviceOptionOverridesThePin() {
        // A task's --device option is applied after the init script, so it decides the simulator.
        assertEquals(IosDevice(listOf(u), false, true), k("./gradlew --init-script $pin iosSimulatorArm64Test --device $u"))
        assertEquals(IosDevice(emptyList(), false, true), k("./gradlew iosSimulatorArm64Test --device \$CDM_IOS_UDID"))
        assertEquals(IosDevice(emptyList(), false, false), k("./gradlew --init-script $pin iosSimulatorArm64Test --device 'iPhone 16'"))
        assertEquals(IosDevice(emptyList(), false, false), k("./gradlew --init-script $pin iosSimulatorArm64Test --device='iPhone 16'"))
    }

    @Test fun mixedGradleWithPinInitScript() {
        val kinds = CommandClassifier.classifyAll("./gradlew --init-script $pin connectedDebugAndroidTest iosSimulatorArm64Test")
        assertEquals(listOf(Device(null, false, false), IosDevice(emptyList(), false, true)), kinds)
    }

    @Test fun addIosInitScriptAppendsOnlyToIosGradleSegments() {
        val q = "'$pin'"
        assertEquals(
            "./gradlew --init-script $q :shared:iosSimulatorArm64Test",
            CommandClassifier.addIosInitScript("./gradlew :shared:iosSimulatorArm64Test", pin),
        )
        assertEquals(
            "./gradlew assembleDebug && ANDROID_SERIAL=x ./gradlew --init-script $q connectedDebugAndroidTest iosX64Test; adb devices",
            CommandClassifier.addIosInitScript(
                "./gradlew assembleDebug && ANDROID_SERIAL=x ./gradlew connectedDebugAndroidTest iosX64Test; adb devices", pin,
            ),
        )
        assertEquals(
            "{ cd app && timeout 600 ./gradlew --init-script $q iosSimulatorArm64Test 2>&1; }",
            CommandClassifier.addIosInitScript("{ cd app && timeout 600 ./gradlew iosSimulatorArm64Test 2>&1; }", pin),
        )
        val other = "./gradlew installDebug; xcrun simctl list; echo iosSimulatorArm64Test"
        assertEquals(other, CommandClassifier.addIosInitScript(other, pin))
    }

    @Test fun addIosInitScriptIsIdempotent() {
        val once = CommandClassifier.addIosInitScript("./gradlew iosSimulatorArm64Test", pin)
        assertEquals(once, CommandClassifier.addIosInitScript(once, pin))
        val manual = "./gradlew -I $pin iosSimulatorArm64Test"
        assertEquals(manual, CommandClassifier.addIosInitScript(manual, pin))
        assertEquals(IosDevice(emptyList(), false, true), k(once))
    }

    @Test fun addIosInitScriptQuotesThePath() {
        val spaced = "/Users/a b/it's/ios-pin.gradle"
        val out = CommandClassifier.addIosInitScript("./gradlew iosSimulatorArm64Test", spaced)
        assertEquals("./gradlew --init-script '/Users/a b/it'\\''s/ios-pin.gradle' iosSimulatorArm64Test", out)
        assertEquals(listOf("./gradlew", "--init-script", spaced, "iosSimulatorArm64Test"), CommandClassifier.unquotedWords(out))
    }
}
