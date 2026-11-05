package com.jacobrein.cdm.install

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InstallerTest {
    private fun tmpHome(): Path = Files.createTempDirectory("cdm-home")
    private fun settings(home: Path) = home.resolve(".claude/settings.json")

    private fun cdmGroups(home: Path): List<JsonObject> {
        val hooks = Json.parseToJsonElement(settings(home).readText()).jsonObject["hooks"]!!.jsonObject
        return hooks.values.flatMap { it.jsonArray }.map { it.jsonObject }.filter { g ->
            g["hooks"]!!.jsonArray.any { it.jsonObject["statusMessage"]?.jsonPrimitive?.content == "claude-device-manager" }
        }
    }

    private fun urls(groups: List<JsonObject>) =
        groups.flatMap { g -> g["hooks"]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content } }

    @Test
    fun installsIntoMissingSettings() {
        val home = tmpHome()
        val inst = Installer(home)
        assertEquals(InstallStatus.NotInstalled, inst.hookStatus(47823))
        val path = inst.installHooks(47823).getOrThrow()
        assertEquals(settings(home), path)
        assertEquals(4, cdmGroups(home).size)
        assertEquals(InstallStatus.Installed, inst.hookStatus(47823))
        assertIs<InstallStatus.Outdated>(inst.hookStatus(1))
    }

    @Test
    fun preservesExistingHooksAndKeys() {
        val home = tmpHome()
        home.resolve(".claude").createDirectories()
        val original = """{"model":"opus","hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}]},"zeta":1}"""
        settings(home).writeText(original)
        val backup = Installer(home).installHooks(47823).getOrThrow()
        assertTrue(backup.fileName.toString().startsWith("settings.json.cdm-backup-"))
        assertEquals(original, backup.readText())
        val root = Json.parseToJsonElement(settings(home).readText()).jsonObject
        assertEquals(listOf("model", "hooks", "zeta"), root.keys.toList())
        assertEquals("opus", root["model"]!!.jsonPrimitive.content)
        val pre = root["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(3, pre.size)
        assertEquals(
            Json.parseToJsonElement("""{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}"""),
            pre[0],
        )
        assertEquals(4, cdmGroups(home).size)
    }

    @Test
    fun reinstallReplacesNotDuplicates() {
        val home = tmpHome()
        val inst = Installer(home)
        inst.installHooks(47823).getOrThrow()
        inst.installHooks(50000).getOrThrow()
        val groups = cdmGroups(home)
        assertEquals(4, groups.size)
        assertTrue(urls(groups).all { it.startsWith("http://127.0.0.1:50000/hooks/") })
        assertEquals(InstallStatus.Installed, inst.hookStatus(50000))
        assertIs<InstallStatus.Outdated>(inst.hookStatus(47823))
    }

    @Test
    fun unparsableSettingsRefused() {
        val home = tmpHome()
        home.resolve(".claude").createDirectories()
        settings(home).writeText("""{"broken":""")
        val inst = Installer(home)
        assertTrue(inst.installHooks(47823).isFailure)
        assertEquals("""{"broken":""", settings(home).readText())
        assertEquals(InstallStatus.SettingsUnreadable, inst.hookStatus(47823))
    }

    @Test
    fun appendSnippetIdempotent() {
        val home = tmpHome()
        home.resolve(".claude").createDirectories()
        val md = home.resolve(".claude/CLAUDE.md")
        md.writeText("# Mine\nkeep me\n")
        val inst = Installer(home)
        inst.appendSnippet().getOrThrow()
        inst.appendSnippet().getOrThrow()
        val text = md.readText()
        assertEquals(1, Regex("<!-- cdm:start -->").findAll(text).count())
        assertEquals(1, Regex("<!-- cdm:end -->").findAll(text).count())
        assertTrue(text.startsWith("# Mine\nkeep me\n"))
        assertTrue(text.contains(inst.claudeMdSnippet))
    }

    @Test
    fun appendSnippetReplacesInPlaceAndCreatesFile() {
        val home = tmpHome()
        val md = home.resolve(".claude/CLAUDE.md")
        md.parent.createDirectories()
        md.writeText("top\n<!-- cdm:start -->\nOLD\n<!-- cdm:end -->\nbottom\n")
        Installer(home).appendSnippet().getOrThrow()
        val text = md.readText()
        assertTrue(!text.contains("OLD") && text.startsWith("top\n") && text.trimEnd().endsWith("bottom"))
        val h2 = tmpHome()
        Installer(h2).appendSnippet().getOrThrow()
        assertTrue(h2.resolve(".claude/CLAUDE.md").exists())
    }

    @Test
    fun installCdmCopiesExecutable() {
        val home = tmpHome()
        val p = Installer(home).installCdm()
        assertEquals(home.resolve(".local/bin/cdm"), p)
        assertTrue(p.readBytes().isNotEmpty())
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(p))
        Installer(home).installCdm()
    }

    @Test
    fun localBinOnPath() {
        val home = tmpHome()
        val inst = Installer(home)
        assertTrue(inst.localBinOnPath("/usr/bin:${home.resolve(".local/bin")}"))
        assertTrue(!inst.localBinOnPath("/usr/bin"))
    }

    private fun withSettings(content: String): Pair<Path, Installer> {
        val home = tmpHome()
        home.resolve(".claude").createDirectories()
        settings(home).writeText(content)
        return home to Installer(home)
    }

    @Test
    fun malformedHooksShapeRefusedUnchanged() {
        for (bad in listOf("""{"hooks":[1]}""", """{"hooks":{"PreToolUse":{"a":1}}}""", """{"hooks":"x"}""")) {
            val (home, inst) = withSettings(bad)
            assertTrue(inst.installHooks(47823).isFailure)
            assertEquals(bad, settings(home).readText())
            assertEquals(InstallStatus.SettingsUnreadable, inst.hookStatus(47823))
            assertEquals(1, Files.list(home.resolve(".claude")).count())
        }
    }

    @Test
    fun sharedGroupKeepsUserHook() {
        val shared = """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"},{"type":"http","url":"http://127.0.0.1:1/hooks/pre-tool-use","statusMessage":"claude-device-manager"}]}],"Stop":[{"hooks":[{"type":"http","url":"http://127.0.0.1:1/x","statusMessage":"claude-device-manager"}]}]}}"""
        val (home, inst) = withSettings(shared)
        inst.installHooks(47823).getOrThrow()
        val root = Json.parseToJsonElement(settings(home).readText()).jsonObject
        val pre = root["hooks"]!!.jsonObject["PreToolUse"]!!.jsonArray
        assertEquals(
            Json.parseToJsonElement("""{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}"""),
            pre[0],
        )
        assertEquals(3, pre.size)
        assertTrue("Stop" !in root["hooks"]!!.jsonObject)
        assertEquals(InstallStatus.Installed, inst.hookStatus(47823))
    }

    @Test
    fun backupsNeverOverwrite() {
        val (home, inst) = withSettings("""{"a":1}""")
        val backups = (1..3).map { inst.installHooks(47823).getOrThrow() }
        assertEquals(3, backups.toSet().size)
        assertEquals("""{"a":1}""", backups[0].readText())
        assertTrue(backups.all { it.exists() })
        assertEquals(4, Files.list(home.resolve(".claude")).filter { it.fileName.toString().contains("backup") }.count() + 1)
    }

    @Test
    fun symlinkedFilesStayLinksAndKeepPerms() {
        val home = tmpHome()
        val dot = tmpHome()
        home.resolve(".claude").createDirectories()
        val realS = dot.resolve("settings.json").also { it.writeText("{}") }
        val realM = dot.resolve("CLAUDE.md").also { it.writeText("hi\n") }
        for (f in listOf(realS, realM)) Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        Files.createSymbolicLink(settings(home), realS)
        Files.createSymbolicLink(home.resolve(".claude/CLAUDE.md"), realM)
        val inst = Installer(home)
        inst.installHooks(47823).getOrThrow()
        inst.appendSnippet().getOrThrow()
        assertTrue(Files.isSymbolicLink(settings(home)))
        assertTrue(Files.isSymbolicLink(home.resolve(".claude/CLAUDE.md")))
        assertTrue(realS.readText().contains("claude-device-manager"))
        assertTrue(realM.readText().contains("cdm:start"))
        for (f in listOf(realS, realM)) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"), Files.getPosixFilePermissions(f))
        }
    }

    @Test
    fun orphanStartMarkerFailsWithoutWriting() {
        val home = tmpHome()
        val md = home.resolve(".claude/CLAUDE.md")
        md.parent.createDirectories()
        md.writeText("x\n<!-- cdm:start -->\nhalf\n")
        val r = Installer(home).appendSnippet()
        assertTrue(r.isFailure)
        assertEquals("x\n<!-- cdm:start -->\nhalf\n", md.readText())
    }
}
