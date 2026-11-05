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
}
