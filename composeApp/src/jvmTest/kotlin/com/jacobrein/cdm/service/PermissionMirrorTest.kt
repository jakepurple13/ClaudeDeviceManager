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
        assertEquals(Decision.ASK, decide("find . -name x"))
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
        assertEquals(Decision.ASK, decide("env FOO=1 adb shell ls"))
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

    private fun assertAsk(vararg cmds: String) = cmds.forEach { assertEquals(Decision.ASK, decide(it), it) }

    @Test fun bareAssignmentAndWrappersAsk() {
        settings(home, allow = listOf("Bash(adb:*)"))
        assertAsk(
            "PATH=/tmp/evil; ls", "sudo ls", "PATH=/tmp/evil ls", "DYLD_INSERT_LIBRARIES=x cat f",
            "env PATH=/tmp/evil ls", "sudo adb shell ls", "LD_PRELOAD=x adb shell ls", "time ls",
        )
        assertEquals(Decision.ALLOW, decide("env"))
    }

    @Test fun quotedFlagsDoNotBypassWriterChecks() = assertAsk(
        "find . '-delete'", "find . \"-exec\" rm {} \\;", "find . -exe\\c rm {} +", "sort '-o' out in",
    )

    @Test fun readOnlyBuiltinsThatRunOrWrite() = assertAsk(
        "rg --pre=./evil.sh x", "rg --pre ./evil.sh x", "sort --compress-program=sh f", "sort -T dir f",
        "sort --temporary-directory=d f", "uniq in out", "file -C -m m", "date -s 2020-01-01",
    )

    @Test fun redirectsAskOnAllPaths() {
        settings(home, allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ASK, decide("cdm acquire > ~/.zshrc", RewriteKind.CDM))
        assertAsk("adb shell ls > ~/.bashrc")
    }

    @Test fun denyMatchesUnquotedForms() {
        settings(home, allow = listOf("Bash"), deny = listOf("Bash(rm:*)"))
        assertAsk("'rm' -rf x", "\\rm -rf x", "sudo rm x", "FOO=1 rm x")
    }

    @Test fun managedSettingsDenyApplies() {
        val managed = home.resolve("managed.json")
        managed.writeText("""{"permissions":{"deny":["Bash(adb:*)"]}}""")
        mirror = PermissionMirror(home, managedSettings = managed)
        settings(home, allow = listOf("Bash(adb:*)"))
        assertEquals(Decision.ASK, decide("adb shell ls"))
    }

    @Test fun shellSyntaxMakesSegmentsIneligible() {
        settings(home, allow = listOf("Bash"))
        assertAsk("find . \$'-delete'", "ls \$X", "cat {a,b}", "(rm -rf x)", "{ rm -rf x; }", "! rm x", "ls *")
        assertEquals(Decision.ALLOW, decide("[ -f x ]"))
        assertEquals(Decision.ALLOW, decide("echo '\$X *'"))
    }

    @Test fun keywordsAndExecWrappersNeverAllowEligible() {
        settings(home, allow = listOf("Bash"), deny = listOf("Bash(rm:*)"))
        assertAsk(
            "if true; then rm -rf x; fi", "command rm x", "exec rm x", "eval rm x", "nohup rm x", "nice rm x",
            "timeout 5 rm x", "bash -c 'rm x'", "xargs rm", "source f", ". f",
        )
    }

    @Test fun denyMatchesBasenameOfFirstWord() {
        settings(home, allow = listOf("Bash"), deny = listOf("Bash(rm:*)"))
        assertAsk("/bin/rm x", "./rm x")
    }

    @Test fun removedBuiltinsNowAsk() = assertAsk("find . -name x", "sort f", "uniq f", "file f", "date", "rg x")

    @Test fun printenvOnlyWithVarNames() {
        assertEquals(Decision.ALLOW, decide("printenv HOME"))
        assertAsk("printenv --help x/y")
    }
}
