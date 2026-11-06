package com.programmersbox.cdm.install

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
import kotlin.test.assertFailsWith
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
        // The remove path also writes through the links and keeps them links.
        inst.uninstallHooks().getOrThrow()
        inst.removeSnippet().getOrThrow()
        assertTrue(Files.isSymbolicLink(settings(home)))
        assertTrue(Files.isSymbolicLink(home.resolve(".claude/CLAUDE.md")))
        assertEquals(Json.parseToJsonElement("{}"), Json.parseToJsonElement(realS.readText()))
        assertEquals("hi\n", realM.readText())
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

    // --- Task 11b: uninstall -------------------------------------------------------------------------------------

    private fun hooksOf(home: Path) = Json.parseToJsonElement(settings(home).readText()).jsonObject

    @Test
    fun uninstallRemovesOnlyOurs() {
        val user = """{"model":"opus","hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}],"Stop":[{"hooks":[{"type":"command","command":"say done"}]}]},"zeta":1}"""
        val (home, inst) = withSettings(user)
        inst.installHooks(47823).getOrThrow()
        assertEquals(4, cdmGroups(home).size)
        inst.uninstallHooks().getOrThrow()
        assertEquals(Json.parseToJsonElement(user), hooksOf(home))
        assertEquals(listOf("model", "hooks", "zeta"), hooksOf(home).keys.toList())
        assertTrue(!settings(home).readText().contains("claude-device-manager"))
        assertEquals(InstallStatus.NotInstalled, inst.hookStatus(47823))
    }

    @Test
    fun uninstallKeepsUserHookSharingOurGroup() {
        val shared = """{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"},{"type":"http","url":"http://127.0.0.1:1/hooks/pre-tool-use","statusMessage":"claude-device-manager"}]}]}}"""
        val (home, inst) = withSettings(shared)
        inst.uninstallHooks().getOrThrow()
        assertEquals(
            Json.parseToJsonElement("""{"hooks":{"PreToolUse":[{"matcher":"Bash","hooks":[{"type":"command","command":"echo hi"}]}]}}"""),
            hooksOf(home),
        )
    }

    @Test
    fun uninstallWhenNotInstalledIsNoop() {
        val original = """{"model":"opus",  "hooks":{"Stop":[]}}"""
        val (home, inst) = withSettings(original)
        inst.uninstallHooks().getOrThrow()
        assertEquals(original, settings(home).readText())
        assertEquals(1, Files.list(home.resolve(".claude")).count())
        val empty = tmpHome()
        Installer(empty).uninstallHooks().getOrThrow()
        assertTrue(!settings(empty).exists())
    }

    @Test
    fun uninstallRefusesUnreadableSettings() {
        val (home, inst) = withSettings("""{"broken":""")
        assertTrue(inst.uninstallHooks().isFailure)
        assertEquals("""{"broken":""", settings(home).readText())
    }

    @Test
    fun uninstallDeletesSettingsWeCreated() {
        val home = tmpHome()
        val inst = Installer(home)
        inst.installHooks(47823).getOrThrow()
        // A crash and restart in between must not forget that we created the file.
        Installer(home).installHooks(47823).getOrThrow()
        Installer(home).uninstallHooks().getOrThrow()
        assertTrue(!settings(home).exists())
    }

    @Test
    fun uninstallKeepsCreatedSettingsTheUserAddedTo() {
        val home = tmpHome()
        val inst = Installer(home)
        inst.installHooks(47823).getOrThrow()
        val root = hooksOf(home)
        settings(home).writeText(JsonObject(root + ("model" to Json.parseToJsonElement("\"opus\""))).toString())
        inst.uninstallHooks().getOrThrow()
        assertEquals(Json.parseToJsonElement("""{"model":"opus"}"""), hooksOf(home))
    }

    @Test
    fun uninstallKeepsPreExistingEmptySettings() {
        val (home, inst) = withSettings("{}")
        inst.installHooks(47823).getOrThrow()
        inst.uninstallHooks().getOrThrow()
        assertTrue(settings(home).exists())
        assertEquals(Json.parseToJsonElement("{}"), hooksOf(home))
    }

    @Test
    fun removeSnippetRestoresOriginalClaudeMd() {
        for (original in listOf("# Mine\nkeep me\n", "no newline", "blank after\n\n", "\n", "")) {
            val home = tmpHome()
            val md = home.resolve(".claude/CLAUDE.md")
            md.parent.createDirectories()
            md.writeText(original)
            val inst = Installer(home)
            inst.appendSnippet().getOrThrow()
            assertTrue(md.readText().contains("cdm:start"))
            inst.removeSnippet().getOrThrow()
            assertTrue(md.exists(), "a file that existed before stays: ${original.length}")
            assertEquals(original, md.readText())
        }
    }

    @Test
    fun removeSnippetDeletesClaudeMdWeCreated() {
        val home = tmpHome()
        val md = home.resolve(".claude/CLAUDE.md")
        val inst = Installer(home)
        inst.appendSnippet().getOrThrow()
        inst.appendSnippet().getOrThrow()
        inst.removeSnippet().getOrThrow()
        assertTrue(!md.exists())
    }

    @Test
    fun removeSnippetKeepsCreatedClaudeMdTheUserAddedTo() {
        val home = tmpHome()
        val md = home.resolve(".claude/CLAUDE.md")
        val inst = Installer(home)
        inst.appendSnippet().getOrThrow()
        md.writeText("mine\n" + md.readText())
        inst.removeSnippet().getOrThrow()
        assertEquals("mine\n", md.readText())
    }

    @Test
    fun removeSnippetMidFileDropsOneBlankLine() {
        val home = tmpHome()
        val md = home.resolve(".claude/CLAUDE.md")
        md.parent.createDirectories()
        md.writeText("top\n\n<!-- cdm:start -->\nOLD\n<!-- cdm:end -->\n\nbottom\n")
        Installer(home).removeSnippet().getOrThrow()
        assertEquals("top\n\nbottom\n", md.readText())
    }

    @Test
    fun removeSnippetNoopWhenAbsent() {
        val home = tmpHome()
        val md = home.resolve(".claude/CLAUDE.md")
        Installer(home).removeSnippet().getOrThrow()
        assertTrue(!md.exists())
        md.parent.createDirectories()
        md.writeText("plain\n")
        Installer(home).removeSnippet().getOrThrow()
        assertEquals("plain\n", md.readText())
    }

    @Test
    fun uninstallCdmOnlyDeletesOurScript() {
        val home = tmpHome()
        val inst = Installer(home)
        val ours = inst.installCdm()
        assertTrue(inst.cdmInstalled())
        inst.uninstallCdm()
        assertTrue(!ours.exists())
        assertTrue(!inst.cdmInstalled())
        // A foreign cdm is kept, both at uninstall and at install.
        ours.writeText("#!/bin/sh\necho someone else's cdm\n")
        inst.uninstallCdm()
        assertEquals("#!/bin/sh\necho someone else's cdm\n", ours.readText())
        assertFailsWith<IllegalStateException> { inst.installCdm() }
        assertEquals("#!/bin/sh\necho someone else's cdm\n", ours.readText())
        inst.uninstallCdm() // missing file: no-op
    }

    @Test
    fun installCdmReplacesAtomicallyNotInPlace() {
        val home = tmpHome()
        val inst = Installer(home)
        val p = inst.installCdm()
        val older = p.readText() + "\n# older build\n"
        p.writeText(older)
        // A second name for the same inode: an in-place write would change it too, a rename leaves it alone.
        val link = home.resolve("cdm-hardlink")
        Files.createLink(link, p)
        inst.installCdm()
        assertTrue(inst.cdmInstalled())
        assertEquals(older, link.readText())
        assertTrue(PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(p))
        assertEquals(listOf("cdm"), Files.list(p.parent).map { it.fileName.toString() }.toList())
    }

    @Test
    fun installCdmAlsoInstallsIosPinScript() {
        val home = tmpHome()
        val inst = Installer(home)
        assertTrue(!inst.iosPinInstalled())
        inst.installCdm()
        val pin = home.resolve(".claude-device-manager/ios-pin.gradle")
        assertEquals(pin, inst.iosPinPath)
        assertTrue(inst.iosPinInstalled())
        assertTrue(pin.readText().contains("CDM_IOS_UDID"))
        assertTrue(pin.readText().contains("KotlinNativeSimulatorTest"))
        // An older copy is replaced.
        pin.writeText(pin.readText() + "\n// older build\n")
        assertTrue(!inst.iosPinInstalled())
        inst.installCdm()
        assertTrue(inst.iosPinInstalled())
        assertEquals(listOf("ios-pin.gradle"), Files.list(pin.parent).map { it.fileName.toString() }.filter { !it.endsWith(".json") }.toList())
    }

    @Test
    fun uninstallCdmRemovesOnlyOurIosPinScript() {
        val home = tmpHome()
        val inst = Installer(home)
        inst.installCdm()
        val pin = inst.iosPinPath
        inst.uninstallCdm()
        assertTrue(!pin.exists())
        assertTrue(!inst.iosPinInstalled())
        inst.uninstallCdm() // missing file: no-op
        // A file at that path that isn't ours is kept.
        pin.writeText("// someone else's script\n")
        inst.uninstallCdm()
        assertEquals("// someone else's script\n", pin.readText())
        // An older copy of ours is still ours.
        inst.installCdm()
        pin.writeText(pin.readText() + "\n// older build\n")
        inst.uninstallCdm()
        assertTrue(!pin.exists())
    }

    @Test
    fun installCdmReplacesAnOlderCopyOfOurs() {
        val home = tmpHome()
        val inst = Installer(home)
        val p = inst.installCdm()
        p.writeText(p.readText() + "\n# older build\n")
        assertTrue(!inst.cdmInstalled())
        inst.installCdm()
        assertTrue(inst.cdmInstalled())
    }

    @Test
    fun pruneKeepsNewestFive() {
        val home = tmpHome()
        val dir = home.resolve(".claude").createDirectories()
        val names = listOf(
            "20261101-090000", "20261102-090000", "20261103-090000", "20261105-120000",
            "20261105-120000-1", "20261105-120000-2", "20261105-120000-10", "20261104-090000",
        ).map { "settings.json.cdm-backup-$it" }
        names.forEach { dir.resolve(it).writeText("{}") }
        dir.resolve("settings.json").writeText("{}")
        dir.resolve("settings.json.cdm-backup-junk").writeText("{}")
        Installer(home).pruneBackups()
        val left = Files.list(dir).map { it.fileName.toString() }.toList().toSet()
        assertEquals(
            setOf(
                "settings.json", "settings.json.cdm-backup-junk",
                "settings.json.cdm-backup-20261104-090000", "settings.json.cdm-backup-20261105-120000",
                "settings.json.cdm-backup-20261105-120000-1", "settings.json.cdm-backup-20261105-120000-2",
                "settings.json.cdm-backup-20261105-120000-10",
            ),
            left,
        )
        Installer(home).pruneBackups(keep = 2)
        assertTrue(dir.resolve("settings.json.cdm-backup-20261105-120000-10").exists())
        assertTrue(dir.resolve("settings.json.cdm-backup-20261105-120000-2").exists())
        assertTrue(!dir.resolve("settings.json.cdm-backup-20261105-120000-1").exists())
        Installer(tmpHome()).pruneBackups() // no .claude dir: no-op
    }

    @Test
    fun installStateLivesUnderHome() {
        val home = tmpHome()
        Installer(home).installHooks(47823).getOrThrow()
        assertTrue(home.resolve(".claude-device-manager/install-state.json").exists())
    }
}
