package com.jacobrein.cdm.service

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.AfterTest
import kotlin.io.path.deleteRecursively
import kotlin.io.path.absolutePathString

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class PermissionMirrorTest {
    private lateinit var home: Path
    private lateinit var cwd: Path
    private lateinit var mirror: PermissionMirror

    @BeforeTest fun setUp() {
        home = Files.createTempDirectory("cdm-home")
        cwd = Files.createTempDirectory("cdm-cwd")
        mirror = PermissionMirror(home)
    }

    @AfterTest fun tearDown() {
        home.deleteRecursively()
        cwd.deleteRecursively()
    }

    private fun settings(dir: Path, name: String = "settings.json", allow: List<String> = emptyList(), ask: List<String> = emptyList(), deny: List<String> = emptyList()) {
        fun arr(l: List<String>) = l.joinToString(",") { "\"$it\"" }
        dir.resolve(".claude").createDirectories().resolve(name)
            .writeText("""{"permissions":{"allow":[${arr(allow)}],"ask":[${arr(ask)}],"deny":[${arr(deny)}]}}""")
    }

    private fun decide(cmd: String, kind: RewriteKind = RewriteKind.PIN, mode: String? = "default") =
        mirror.decide(cmd, kind, cwd.absolutePathString(), mode)

    @Test fun allowRuleMatchesOriginal() {
        settings(home, allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ALLOW, decide("adb shell ls"))
    }

    @Test fun unmatchedAsks() = assertEquals(Decision.ASK, decide("./run_tests.sh"))

    @Test fun compoundNeedsAllSegments() {
        settings(home, allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ASK, decide("rm -rf build && adb install a.apk"))
        assertEquals(Decision.ALLOW, decide("cd app && adb install a.apk"))
    }

    @Test fun readOnlyBuiltinsAllowed() {
        assertEquals(Decision.ALLOW, decide("ls -la | grep foo"))
        assertEquals(Decision.ASK, decide("find . -delete"))
        assertEquals(Decision.ASK, decide("find . -exec rm {} ;"))
        assertEquals(Decision.ALLOW, decide("find . -name x"))
    }

    @Test fun readOnlyBuiltinsWithWritesOrSubstitutionAsk() {
        assertEquals(Decision.ASK, decide("echo hi > file"))
        assertEquals(Decision.ASK, decide("sort -o out in"))
        assertEquals(Decision.ASK, decide("echo $(rm -rf x)"))
        assertEquals(Decision.ASK, decide("/tmp/evil/ls"))
        assertEquals(Decision.ALLOW, decide("ls 2>&1"))
    }

    @Test fun substitutionDefeatsAllowRule() {
        settings(home, allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ASK, decide("adb shell `rm -rf x`"))
        assertEquals(Decision.ASK, decide("adb shell $(rm x)"))
    }

    @Test fun denyWins() {
        settings(home, allow = listOf("Bash(adb:*)"), deny = listOf("Bash(adb uninstall:*)"))
        assertEquals(Decision.ASK, decide("adb uninstall com.x"))
        assertEquals(Decision.ASK, decide("adb uninstall com.x", mode = "bypassPermissions"))
    }

    @Test fun askRuleWins() {
        settings(home, allow = listOf("Bash(adb:*)"), ask = listOf("Bash(adb shell rm:*)"))
        assertEquals(Decision.ASK, decide("adb shell rm /sdcard/x"))
        assertEquals(Decision.ALLOW, decide("adb shell ls"))
    }

    @Test fun bypassMode() = assertEquals(Decision.ALLOW, decide("whatever", mode = "bypassPermissions"))

    @Test fun cdmOnlyAllowed() {
        assertEquals(Decision.ALLOW, decide("cdm acquire --category watch", RewriteKind.CDM))
        assertEquals(Decision.ASK, decide("cdm release && rm x", RewriteKind.CDM))
        assertEquals(Decision.ASK, decide("/tmp/cdm acquire", RewriteKind.CDM))
        assertEquals(Decision.ASK, decide("cdm acquire", RewriteKind.PIN))
    }

    @Test fun envWrapperDoesNotLaunderCommands() {
        assertEquals(Decision.ASK, decide("env adb shell ls"))
        assertEquals(Decision.ASK, decide("FOO=1 adb shell ls"))
        settings(home, allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ALLOW, decide("env FOO=1 adb shell ls"))
    }

    @Test fun projectAndLocalSettingsMerged() {
        settings(cwd, "settings.local.json", allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ALLOW, decide("adb shell ls"))
        settings(cwd, "settings.json", deny = listOf("Bash(adb shell:*)"))
        assertEquals(Decision.ASK, decide("adb shell ls"))
    }

    @Test fun unreadableSettingsIgnored() {
        home.resolve(".claude").createDirectories().resolve("settings.json").writeText("{not json")
        settings(cwd, "settings.local.json", allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ALLOW, decide("adb shell ls"))
        assertEquals(Decision.ASK, decide("./x.sh"))
    }

    @Test fun nonBashRulesIgnoredAndEditsReloaded() {
        settings(home, allow = listOf("Read(**)"))
        assertEquals(Decision.ASK, decide("adb shell ls"))
        Thread.sleep(20)
        settings(home, allow = listOf("Bash(adb:*)", "Read(**)"))
        assertEquals(Decision.ALLOW, decide("adb shell ls"))
    }
}
