package com.jacobrein.cdm.core.classify

import com.jacobrein.cdm.core.classify.CommandKind.*
import com.jacobrein.cdm.core.model.HolderId
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandClassifierTest {
    @Test
    fun classifiesCommands() {
        val dev = Device(null, false, false)
        val cases = listOf(
            "ls -la" to Other,
            "grep adb notes.txt" to Other,
            "cat ~/adbkeys" to Other,
            "echo ./gradlew" to Other,
            "adb devices -l" to AdbGlobalSafe(false),
            "adb connect 10.0.0.5:5555" to AdbGlobalSafe(true),
            "adb pair 10.0.0.5:4444" to AdbGlobalSafe(false),
            "adb kill-server" to AdbGlobalDangerous("kill-server"),
            "adb disconnect" to AdbGlobalDangerous("disconnect"),
            "adb disconnect 10.0.0.5:5555" to AdbDisconnect("10.0.0.5:5555"),
            "adb shell input tap 1 2" to dev,
            "adb -s ABC123 install a.apk" to Device("ABC123", false, false),
            "adb -d logcat" to Device(null, true, false),
            "adb -e logcat" to Device(null, false, true),
            "/opt/homebrew/bin/adb shell ls" to dev,
            "./gradlew :app:installDebug" to dev,
            "./gradlew connectedAndroidTest" to dev,
            "./gradlew assembleDebug test" to GradleNonDevice,
            "cd app && ./gradlew installDebug 2>&1 | tail -20" to dev,
            "ANDROID_HOME=/x adb shell ls" to dev,
            "sudo adb shell ls" to dev,
            "./gradlew assembleDebug && adb kill-server" to AdbGlobalDangerous("kill-server"),
            "cdm acquire --category watch" to Cdm("acquire"),
        )
        for ((cmd, expected) in cases) assertEquals(expected, CommandClassifier.classify(cmd), cmd)
    }

    @Test
    fun quotedSeparatorsDoNotSplit() {
        assertEquals(Device(null, false, false), CommandClassifier.classify("""adb shell "ls; pwd""""))
        assertEquals(listOf("adb shell \"ls; pwd\""), CommandClassifier.segments("adb shell \"ls; pwd\""))
    }

    @Test
    fun segmentsStripWrappers() {
        assertEquals(listOf("cd a", "adb shell ls"), CommandClassifier.segments("cd a && A=1 sudo adb shell ls"))
        assertEquals("adb", CommandClassifier.firstWord("/usr/bin/adb shell"))
    }

    @Test
    fun addsCdmIdentity() {
        assertEquals(
            "cdm acquire --holder s1:a9 --cwd '/x/y' --category watch",
            CommandClassifier.addCdmIdentity("cdm acquire --category watch", HolderId("s1", "a9"), "/x/y"),
        )
        assertEquals(
            "cdm status --holder s1:main --cwd '/it'\\''s'",
            CommandClassifier.addCdmIdentity("cdm status", HolderId("s1"), "/it's"),
        )
    }
}
