package com.programmersbox.cdm.install

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface InstallStatus {
    data object NotInstalled : InstallStatus
    data object Installed : InstallStatus
    /** Our entries exist but the port or entry count is wrong. */
    data class Outdated(val installedPort: Int?) : InstallStatus
    data object SettingsUnreadable : InstallStatus
}

/**
 * Installs and removes hooks, the cdm script and the CLAUDE.md snippet under an explicit [home]. Whether we created
 * settings.json / CLAUDE.md ourselves is kept in `~/.claude-device-manager/install-state.json`, so removal can delete
 * a file only we ever wrote to, even across a crash and restart.
 */
class Installer(private val home: Path) {
    private val settingsPath get() = home.resolve(".claude/settings.json")
    private val claudeMdPath get() = home.resolve(".claude/CLAUDE.md")
    private val cdmPath get() = home.resolve(".local/bin/cdm")
    private val statePath get() = home.resolve(".claude-device-manager/install-state.json")
    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private data class Spec(val event: String, val matcher: String?, val path: String, val timeout: Int)

    private val specs = listOf(
        Spec("PreToolUse", "Bash", "/hooks/pre-tool-use", 2),
        Spec("PreToolUse", "Edit|Write|MultiEdit|NotebookEdit", "/hooks/pre-tool-use", 2),
        Spec("SubagentStop", null, "/hooks/subagent-stop", 5),
        Spec("SessionEnd", null, "/hooks/session-end", 5),
    )

    private fun url(port: Int, path: String) = "http://127.0.0.1:$port$path"

    private fun isOurHook(h: JsonElement): Boolean =
        (h as? JsonObject)?.get("statusMessage")?.let { it as? JsonPrimitive }?.content == MARKER

    /** Strict view of the hooks section: event -> groups. Throws if the shape is not object-of-arrays. */
    private fun strictHooks(root: JsonObject): Map<String, JsonArray> {
        val h = root["hooks"] ?: return emptyMap()
        val obj = h as? JsonObject ?: error("settings.json: \"hooks\" is not an object")
        return obj.mapValues { (k, v) -> v as? JsonArray ?: error("settings.json: hooks.$k is not an array") }
    }

    private fun readRoot(): Result<JsonObject> = runCatching {
        val root = if (!settingsPath.exists()) JsonObject(emptyMap())
        else Json.parseToJsonElement(settingsPath.readText()).jsonObject
        strictHooks(root)
        root
    }

    /** Our hook entries inside [group], as a group holding only them (null if none). */
    private fun ourPart(group: JsonElement): JsonObject? {
        val g = group as? JsonObject ?: return null
        val ours = (g["hooks"] as? JsonArray).orEmpty().filter(::isOurHook)
        if (ours.isEmpty()) return null
        return JsonObject(LinkedHashMap(g).apply { put("hooks", JsonArray(ours)) })
    }

    /** [hooks] without our entries: groups left empty by that go, and so do events left empty by that. */
    private fun stripOurs(hooks: Map<String, JsonArray>): Map<String, JsonArray> {
        val out = LinkedHashMap<String, JsonArray>()
        hooks.forEach { (event, groups) ->
            var changed = false
            val kept = groups.mapNotNull { g ->
                val obj = g as? JsonObject
                val arr = obj?.get("hooks") as? JsonArray
                if (obj == null || arr == null || arr.none(::isOurHook)) return@mapNotNull g
                changed = true
                val rest = arr.filterNot(::isOurHook)
                if (rest.isEmpty()) null
                else JsonObject(LinkedHashMap(obj).apply { put("hooks", JsonArray(rest)) })
            }
            if (!(changed && kept.isEmpty())) out[event] = JsonArray(kept)
        }
        return out
    }

    fun hookStatus(port: Int): InstallStatus {
        val root = readRoot().getOrElse { return InstallStatus.SettingsUnreadable }
        val found = strictHooks(root).flatMap { (event, groups) ->
            groups.mapNotNull { g -> ourPart(g)?.let { event to it } }
        }
        if (found.isEmpty()) return InstallStatus.NotInstalled
        val expected = specs.map { it.event to buildGroup(it, port) }
        val installedPort = found.firstNotNullOfOrNull { (_, g) ->
            g["hooks"]?.jsonArray?.firstNotNullOfOrNull { h ->
                h.jsonObject["url"]?.jsonPrimitive?.content?.let { PORT_RE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            }
        }
        return if (found.size == expected.size && found.toSet() == expected.toSet()) InstallStatus.Installed
        else InstallStatus.Outdated(installedPort)
    }

    private fun buildGroup(s: Spec, port: Int): JsonObject = buildJsonObject {
        s.matcher?.let { put("matcher", it) }
        put("hooks", JsonArray(listOf(buildJsonObject {
            put("type", "http")
            put("url", url(port, s.path))
            put("timeout", s.timeout)
            put("statusMessage", MARKER)
        })))
    }

    /** Returns the backup path, or the new settings path when there was nothing to back up. */
    fun installHooks(port: Int): Result<Path> = runCatching {
        val root = readRoot().getOrThrow()
        val existed = settingsPath.exists()
        val newHooks = LinkedHashMap<String, JsonElement>(stripOurs(strictHooks(root)))
        specs.forEach { s ->
            val cur = (newHooks[s.event] as? JsonArray).orEmpty()
            newHooks[s.event] = JsonArray(cur + buildGroup(s, port))
        }
        val newRoot = LinkedHashMap(root).apply { put("hooks", JsonObject(newHooks)) }
        settingsPath.parent.createDirectories()
        if (!existed) setCreated(SETTINGS_CREATED, true)
        val backup = if (existed) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            var n = 0
            while (true) {
                val cand = settingsPath.resolveSibling("settings.json.cdm-backup-$stamp" + if (n == 0) "" else "-$n")
                try { Files.copy(settingsPath, cand); break } catch (_: java.nio.file.FileAlreadyExistsException) { n++ }
            }
            settingsPath.resolveSibling("settings.json.cdm-backup-$stamp" + if (n == 0) "" else "-$n")
        } else settingsPath
        atomicWrite(settingsPath, json.encodeToString(JsonObject.serializer(), JsonObject(newRoot)) + "\n")
        backup
    }

    /**
     * Removes only our marked hook entries (and the groups and events that leaves empty, and a "hooks" object left
     * empty). No backup is restored, so other edits made meanwhile survive. A settings.json we created that ends up
     * `{}` is deleted. No-op (file untouched) when none of our entries are there.
     */
    fun uninstallHooks(): Result<Unit> = runCatching {
        if (!settingsPath.exists()) return@runCatching
        val root = readRoot().getOrThrow()
        val hooks = strictHooks(root)
        if (hooks.values.none { groups -> groups.any { ourPart(it) != null } }) return@runCatching
        val newHooks = stripOurs(hooks)
        val newRoot = LinkedHashMap(root).apply {
            if (newHooks.isEmpty()) remove("hooks") else put("hooks", JsonObject(newHooks))
        }
        if (newRoot.isEmpty() && isCreated(SETTINGS_CREATED)) Files.delete(settingsPath)
        else atomicWrite(settingsPath, json.encodeToString(JsonObject.serializer(), JsonObject(newRoot)) + "\n")
        setCreated(SETTINGS_CREATED, false)
    }

    private fun bundledCdm(): ByteArray =
        (Installer::class.java.getResourceAsStream("/cdm") ?: error("cdm resource missing")).use { it.readAllBytes() }

    /** ~/.local/bin/cdm is a regular file with exactly the bundled script's bytes. */
    fun cdmInstalled(): Boolean =
        Files.isRegularFile(cdmPath, LinkOption.NOFOLLOW_LINKS) && Files.readAllBytes(cdmPath).contentEquals(bundledCdm())

    /** Copies the script to ~/.local/bin/cdm. Refuses to replace a `cdm` that isn't some version of ours. */
    fun installCdm(): Path {
        val target = cdmPath
        target.parent.createDirectories()
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            val ours = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) &&
                CDM_SIGNATURE in String(Files.readAllBytes(target), Charsets.UTF_8)
            check(ours) { "$target exists and isn't Claude Device Manager's cdm; move it away, then Repair." }
        }
        if (!cdmInstalled()) {
            // Temp file + rename: a running `cdm` never sees a half-written script.
            val tmp = Files.createTempFile(target.parent, "cdm", ".tmp")
            try {
                Files.write(tmp, bundledCdm())
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"))
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        installIosPin()
        return target
    }

    /** Deletes ~/.local/bin/cdm only if it is our script, byte for byte, and our iOS pin script if it is one. */
    fun uninstallCdm() {
        if (cdmInstalled()) Files.deleteIfExists(cdmPath)
        if (Files.isRegularFile(iosPinPath, LinkOption.NOFOLLOW_LINKS) &&
            IOS_PIN_SIGNATURE in String(Files.readAllBytes(iosPinPath), Charsets.UTF_8)
        ) Files.deleteIfExists(iosPinPath)
    }

    /** The Gradle init script that pins KMP iOS simulator tests to `CDM_IOS_UDID`, installed with `cdm`. */
    val iosPinPath: Path get() = home.resolve(".claude-device-manager/ios-pin.gradle")

    private fun bundledIosPin(): ByteArray =
        (Installer::class.java.getResourceAsStream("/ios-pin.gradle") ?: error("ios-pin.gradle resource missing"))
            .use { it.readAllBytes() }

    /** [iosPinPath] is a regular file with exactly the bundled script's bytes. */
    fun iosPinInstalled(): Boolean =
        Files.isRegularFile(iosPinPath, LinkOption.NOFOLLOW_LINKS) &&
            Files.readAllBytes(iosPinPath).contentEquals(bundledIosPin())

    /** Writes the bundled init script to [iosPinPath]; the directory is ours, so whatever is there is replaced. */
    private fun installIosPin() {
        if (iosPinInstalled()) return
        val target = iosPinPath
        target.parent.createDirectories()
        // Temp file + rename: a Gradle run that is starting never reads a half-written script.
        val tmp = Files.createTempFile(target.parent, "ios-pin", ".tmp")
        try {
            Files.write(tmp, bundledIosPin())
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Keeps the newest [keep] `settings.json.cdm-backup-*` files (by their timestamp and counter). */
    fun pruneBackups(keep: Int = 5) {
        val dir = settingsPath.parent
        if (!Files.isDirectory(dir)) return
        val backups = Files.list(dir).use { files ->
            files.toList().mapNotNull { p -> BACKUP_RE.matchEntire(p.fileName.toString())?.let { m ->
                Triple(p, m.groupValues[1], m.groupValues[2].ifEmpty { "0" }.toLong())
            } }
        }
        backups.sortedWith(compareByDescending<Triple<Path, String, Long>> { it.second }.thenByDescending { it.third })
            .drop(keep.coerceAtLeast(0))
            .forEach { Files.deleteIfExists(it.first) }
    }

    fun localBinOnPath(pathEnv: String = System.getenv("PATH") ?: ""): Boolean {
        val bin = home.resolve(".local/bin").normalize()
        return pathEnv.split(':').filter { it.isNotBlank() }.any { runCatching { Path.of(it).normalize() == bin }.getOrDefault(false) }
    }

    val claudeMdSnippet: String = """
        ## Android devices (Claude Device Manager)
        Before ANY work that touches an Android device or emulator (adb, Gradle install/connected tasks, test scripts, scrcpy), run `cdm acquire --category <phone|watch|phone,watch>` and wait for it to finish. It may pause for several minutes while another session uses the device — that is expected; do not work around it. If it says "Still queued", run it again. When you are done testing, run `cdm release`. Subagents must acquire their own device. Never pass a different `-s <serial>` than the one you were given.
    """.trimIndent()

    fun appendSnippet(): Result<Unit> = runCatching {
        val block = "$START\n$claudeMdSnippet\n$END"
        val existing = if (claudeMdPath.exists()) claudeMdPath.readText() else ""
        val s = existing.indexOf(START)
        val e = if (s >= 0) existing.indexOf(END, s) else -1
        if (s >= 0 && e < 0) error("CLAUDE.md has a \"$START\" marker without \"$END\"; fix it by hand")
        val updated = if (s >= 0 && e >= 0) {
            existing.substring(0, s) + block + existing.substring(e + END.length)
        } else {
            // Always exactly "\n\n" before the block, so removeSnippet can restore the file byte for byte.
            existing + (if (existing.isEmpty()) "" else "\n\n") + block + "\n"
        }
        claudeMdPath.parent.createDirectories()
        if (!claudeMdPath.exists()) setCreated(CLAUDE_MD_CREATED, true)
        atomicWrite(claudeMdPath, updated)
    }

    /** CLAUDE.md holds a complete cdm block. */
    fun snippetInstalled(): Boolean {
        if (!claudeMdPath.exists()) return false
        val text = claudeMdPath.readText()
        val s = text.indexOf(START)
        return s >= 0 && text.indexOf(END, s) >= 0
    }

    /**
     * Removes the cdm block and its line break. At the end of the file it also drops the "\n\n" appendSnippet put
     * before it, restoring the exact earlier bytes; in mid-file it drops one of the blank lines around it. A CLAUDE.md
     * we created that ends up empty is deleted. No-op if there is no block.
     */
    fun removeSnippet(): Result<Unit> = runCatching {
        if (!claudeMdPath.exists()) return@runCatching
        val text = claudeMdPath.readText()
        val s = text.indexOf(START)
        if (s < 0) return@runCatching
        val e = text.indexOf(END, s)
        if (e < 0) error("CLAUDE.md has a \"$START\" marker without \"$END\"; fix it by hand")
        var end = e + END.length
        if (text.startsWith("\n", end)) end++
        var before = text.substring(0, s)
        var after = text.substring(end)
        if (after.isEmpty()) {
            if (before.endsWith("\n\n")) before = before.dropLast(2)
        } else if (before.endsWith("\n\n") && after.startsWith("\n")) {
            after = after.drop(1)
        }
        val updated = before + after
        if (updated.isEmpty() && isCreated(CLAUDE_MD_CREATED)) Files.delete(claudeMdPath)
        else atomicWrite(claudeMdPath, updated)
        setCreated(CLAUDE_MD_CREATED, false)
    }

    private fun readState(): JsonObject =
        runCatching { Json.parseToJsonElement(statePath.readText()).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun isCreated(key: String): Boolean = (readState()[key] as? JsonPrimitive)?.content == "true"

    private fun setCreated(key: String, value: Boolean) {
        val cur = readState()
        if (isCreated(key) == value && statePath.exists()) return
        statePath.parent.createDirectories()
        atomicWrite(statePath, JsonObject(LinkedHashMap(cur).apply { put(key, JsonPrimitive(value)) }).toString() + "\n")
    }

    private fun atomicWrite(requested: Path, text: String) {
        val target = if (requested.exists()) requested.toRealPath() else requested
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            Files.writeString(tmp, text)
            if (Files.exists(target)) runCatching {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target))
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private companion object {
        const val MARKER = "claude-device-manager"
        const val START = "<!-- cdm:start -->"
        const val END = "<!-- cdm:end -->"
        val PORT_RE = Regex("""127\.0\.0\.1:(\d+)""")
        val BACKUP_RE = Regex("""settings\.json\.cdm-backup-(\d{8}-\d{6})(?:-(\d+))?""")
        const val CDM_SIGNATURE = "cdm: Claude Device Manager CLI"
        const val IOS_PIN_SIGNATURE = "ios-pin.gradle: Claude Device Manager init script"
        const val SETTINGS_CREATED = "settingsCreated"
        const val CLAUDE_MD_CREATED = "claudeMdCreated"
    }
}
