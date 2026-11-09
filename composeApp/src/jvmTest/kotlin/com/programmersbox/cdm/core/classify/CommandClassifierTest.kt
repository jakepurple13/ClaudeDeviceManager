package com.programmersbox.cdm.core.classify

import com.programmersbox.cdm.core.classify.CommandKind.*
import com.programmersbox.cdm.core.model.HolderId
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

    @Test
    fun tieBreaking() {
        assertEquals(AdbDisconnect("A"), CommandClassifier.classify("adb disconnect A && adb disconnect A"))
        assertEquals(AdbGlobalDangerous("disconnect"), CommandClassifier.classify("adb disconnect A && adb disconnect B"))
        assertEquals(Device("OTHER", false, false), CommandClassifier.classify("adb shell ls && adb -s OTHER install x"))
        assertEquals(Device(null, true, false), CommandClassifier.classify("adb shell ls && adb -d logcat"))
        assertEquals(AdbGlobalSafe(true), CommandClassifier.classify("adb devices && adb connect X"))
        assertEquals(AdbGlobalSafe(true), CommandClassifier.classify("adb connect X && adb devices"))
    }

    @Test
    fun classifyAllPerSegment() {
        assertEquals(
            listOf(AdbDisconnect("A"), AdbDisconnect("B")),
            CommandClassifier.classifyAll("adb disconnect A && adb disconnect B"),
        )
    }

    @Test
    fun androidSerialEnv() {
        assertEquals(Device("X", false, false), CommandClassifier.classify("ANDROID_SERIAL=X adb shell ls"))
        assertEquals(Device("Y", false, false), CommandClassifier.classify("ANDROID_SERIAL=X adb -s Y shell ls"))
        assertEquals(Device("X", false, false), CommandClassifier.classify("export ANDROID_SERIAL=X; adb shell ls"))
        assertEquals(Device("X", false, false), CommandClassifier.classify("export ANDROID_SERIAL=X; ./gradlew installDebug"))
        assertEquals(
            listOf(Other, Device("X", false, false)),
            CommandClassifier.classifyAll("export ANDROID_SERIAL=X; adb shell ls"),
        )
    }

    /** Final review #2: wrappers must not hide the inner adb/Gradle command or its -s. */
    @Test
    fun wrappersAreSeenThrough() {
        val b = Device("B", false, false)
        val cases = listOf(
            "timeout 30 adb -s B shell ls",
            "timeout -s KILL -k 5 30s adb -s B shell ls",
            "timeout --signal=KILL 30 adb -s B shell ls",
            "gtimeout 5 adb -s B shell ls",
            "nohup adb -s B logcat",
            "nice -n 10 adb -s B shell ls",
            "nice -5 adb -s B shell ls",
            "nice adb -s B shell ls",
            "stdbuf -oL adb -s B logcat",
            "stdbuf -o L adb -s B logcat",
            "time -p adb -s B shell ls",
            "env -i -u FOO X=1 adb -s B shell ls",
            "env -uFOO adb -s B shell ls",
            "command adb -s B shell ls",
            "exec adb -s B shell ls",
            "(adb -s B shell ls)",
            "( adb -s B shell ls )",
            "{ adb -s B shell ls; }",
            "sudo -u me adb -s B shell ls",
            "nohup nice -n 5 timeout 9 adb -s B shell ls",
            "adb -sB shell ls",
        )
        for (c in cases) assertEquals(b, CommandClassifier.classify(c), c)
        assertEquals(Device(null, false, false), CommandClassifier.classify("timeout 600 ./gradlew connectedCheck"))
    }

    @Test
    fun transportIdIsItsOwnKind() {
        for (c in listOf("adb -t 3 shell ls", "adb -t3 shell ls", "adb --transport-id 3 shell", "adb --transport-id=3 shell", "timeout 5 adb -t 3 shell")) {
            assertEquals(AdbTransportId, CommandClassifier.classify(c), c)
        }
    }

    @Test
    fun serialAssignmentsFoundAnywhere() {
        assertEquals(listOf("B"), CommandClassifier.serialAssignments("ANDROID_SERIAL=B ./run_tests.sh"))
        assertEquals(listOf("B"), CommandClassifier.serialAssignments("export ANDROID_SERIAL=B; scrcpy"))
        assertEquals(listOf("B"), CommandClassifier.serialAssignments("(export ANDROID_SERIAL=B; adb shell ls)"))
        assertEquals(listOf("B C"), CommandClassifier.serialAssignments("export ANDROID_SERIAL='B C'; x"))
        assertEquals(listOf("B"), CommandClassifier.serialAssignments("env ANDROID_SERIAL=\"B\" scrcpy"))
        assertEquals(emptyList(), CommandClassifier.serialAssignments("MY_ANDROID_SERIAL=B x; echo \$ANDROID_SERIAL"))
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

    @Test
    fun pinVarWritesIgnoresTextAndReads() {
        for (c in pinVarTextOnly) assertEquals(emptyList(), CommandClassifier.pinVarWrites(c), c)
    }

    @Test
    fun pinVarWritesStillSeesRealWrites() {
        val writes = listOf(
            "read ANDROID_SERIAL", "read \"ANDROID_SERIAL\"", "while read -r ANDROID_SERIAL; do adb install x; done < f",
            "for ANDROID_SERIAL in a b; do adb install x; done", "printf -v ANDROID_SERIAL %s p3", "printf -vANDROID_SERIAL %s p3",
            "declare -n r=ANDROID_SERIAL; r=p3", "mapfile -t ANDROID_SERIAL < f", "unset ANDROID_SERIAL", "export -n ANDROID_SERIAL",
            "{ read ANDROID_SERIAL; }", "x=\$(read ANDROID_SERIAL)", "bash <<'EOF'\nread ANDROID_SERIAL\nEOF",
            "cat <<'EOF' | sh\nread ANDROID_SERIAL\nEOF", "cat > f.sh <<'EOF'\nread ANDROID_SERIAL\nEOF\nbash f.sh",
        )
        for (c in writes) {
            assertEquals(listOf(CommandClassifier.PinVarWrite("ANDROID_SERIAL", null, dynamic = false)), CommandClassifier.pinVarWrites(c), c)
        }
        // A heredoc with a bare delimiter expands \$(…), so its body is still scanned.
        assertEquals(listOf("p3"), CommandClassifier.serialAssignments("cat <<EOF\n\$(ANDROID_SERIAL=p3 adb shell ls)\nEOF"))
    }

    @Test
    fun pinVarWritesFlattensSplitNames() {
        val w = CommandClassifier.pinVarWrites("export ANDROID_\"SERIAL\"=p3; CDM_ANDROID\"1\"_SERIAL=x ./s.sh; read CDM_IOS_UDID")
        assertEquals(
            listOf(
                CommandClassifier.PinVarWrite("ANDROID_SERIAL", "p3", dynamic = false),
                CommandClassifier.PinVarWrite("CDM_ANDROID1_SERIAL", "x", dynamic = false),
                CommandClassifier.PinVarWrite("CDM_IOS_UDID", null, dynamic = false),
            ),
            w,
        )
        assertEquals(emptyList(), CommandClassifier.pinVarWrites("echo \$ANDROID_SERIAL \${CDM_IOS_UDID} \${ANDROID_SERIAL:-x}; grep ANDROID_SERIAL= f"))
        assertEquals(listOf("B C"), CommandClassifier.serialAssignments("export ANDROID_\"SERIAL\"='B C'; x"))
        assertEquals(
            listOf(CommandClassifier.PinVarWrite("ANDROID_SERIAL", "\$x", dynamic = true)),
            CommandClassifier.pinVarWrites("ANDROID_SERIAL=\$x ./t.sh"),
        )
        assertEquals(
            listOf(CommandClassifier.PinVarWrite("ANDROID_SERIAL", null, dynamic = false)),
            CommandClassifier.pinVarWrites("printf -v ANDROID_SERIAL %s p3"),
        )
    }
}
