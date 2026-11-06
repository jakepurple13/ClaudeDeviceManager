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
        assertEquals(IosDevice(emptyList(), false, false), k("maestro test f.yaml"))
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
}
