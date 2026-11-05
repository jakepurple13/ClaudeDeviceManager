package com.jacobrein.cdm.install

import java.nio.file.Files
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

/** Installs hooks, the cdm script and the CLAUDE.md snippet under an explicit [home]. */
class Installer(private val home: Path) {
    private val settingsPath get() = home.resolve(".claude/settings.json")
    private val claudeMdPath get() = home.resolve(".claude/CLAUDE.md")
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
        val newHooks = LinkedHashMap<String, JsonElement>()
        strictHooks(root).forEach { (event, groups) ->
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
            if (!(changed && kept.isEmpty())) newHooks[event] = JsonArray(kept)
        }
        specs.forEach { s ->
            val cur = (newHooks[s.event] as? JsonArray).orEmpty()
            newHooks[s.event] = JsonArray(cur + buildGroup(s, port))
        }
        val newRoot = LinkedHashMap(root).apply { put("hooks", JsonObject(newHooks)) }
        settingsPath.parent.createDirectories()
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

    fun installCdm(): Path {
        val target = home.resolve(".local/bin/cdm")
        target.parent.createDirectories()
        val stream = Installer::class.java.getResourceAsStream("/cdm") ?: error("cdm resource missing")
        stream.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwxr-xr-x"))
        return target
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
            val sep = when {
                existing.isEmpty() -> ""
                existing.endsWith("\n\n") -> ""
                existing.endsWith("\n") -> "\n"
                else -> "\n\n"
            }
            existing + sep + block + "\n"
        }
        claudeMdPath.parent.createDirectories()
        atomicWrite(claudeMdPath, updated)
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
    }
}
