package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.classify.CommandClassifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class Decision { ALLOW, ASK }

data class PermissionRules(val allow: List<BashRule>, val ask: List<BashRule>, val deny: List<BashRule>) {
    operator fun plus(o: PermissionRules) = PermissionRules(allow + o.allow, ask + o.ask, deny + o.deny)

    companion object { val EMPTY = PermissionRules(emptyList(), emptyList(), emptyList()) }
}

/** A hook reply plus, for a [HookReply.Rewrite], the allow/ask the hook must attach (null otherwise). */
data class HookDecision(val reply: HookReply, val permission: Decision?)

/**
 * Claude Code stops matching allow rules on a rewritten command, so the rewrite is answered here instead:
 * the user's own Bash rules are applied to the ORIGINAL command. When in doubt the answer is [Decision.ASK].
 * Stripping (wrappers, assignments, quotes) is only ever used to find more deny/ask matches, never to allow (Ruling M).
 */
class PermissionMirror(
    private val home: Path,
    private val readOnlyBuiltins: Set<String> = DEFAULT_READ_ONLY,
    private val managedSettings: Path = Path.of("/Library/Application Support/ClaudeCode/managed-settings.json"),
) {
    private data class Cached(val modified: Long, val size: Long, val rules: PermissionRules)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** Merges managed, user, project and local settings. Missing or unreadable files contribute nothing. */
    fun rulesFor(cwd: String): PermissionRules {
        val files = buildList {
            add(managedSettings)
            add(home.resolve(".claude/settings.json"))
            val base = runCatching { Path.of(cwd) }.getOrNull()
            if (base != null) {
                add(base.resolve(".claude/settings.json"))
                add(base.resolve(".claude/settings.local.json"))
            }
        }
        return files.map(::load).fold(PermissionRules.EMPTY, PermissionRules::plus)
    }

    fun decide(original: String, kind: RewriteKind, cwd: String, permissionMode: String?): Decision {
        val rules = rulesFor(cwd)
        val raw = CommandClassifier.rawSegments(original)
        // Every form a deny/ask rule might be written against: as written, wrappers stripped, quotes removed.
        val forms = (raw + CommandClassifier.segments(original)).flatMap {
            listOf(it, CommandClassifier.unquotedWords(it).joinToString(" "))
        }.distinct()
        if (forms.any { f -> rules.deny.any { it.matches(f) } }) return Decision.ASK
        if (permissionMode == "bypassPermissions") return Decision.ALLOW
        // A substitution runs code no segment rule has judged.
        if (SUBSTITUTION.containsMatchIn(original)) return Decision.ASK
        if (raw.any { hasRedirect(it) }) return Decision.ASK
        if (kind == RewriteKind.CDM && raw.isNotEmpty() && raw.all { rawFirstWord(it) == "cdm" }) return Decision.ALLOW
        if (forms.any { f -> rules.ask.any { it.matches(f) } }) return Decision.ASK
        val ok = raw.isNotEmpty() && raw.all { s ->
            !hasWrapperOrAssignment(s) && (rules.allow.any { it.matches(s) } || isReadOnly(s))
        }
        return if (ok) Decision.ALLOW else Decision.ASK
    }

    private fun rawFirstWord(segment: String) = segment.trimStart().substringBefore(' ').substringBefore('\t')

    private fun hasRedirect(segment: String) = REDIRECT.containsMatchIn(segment.replace(HARMLESS_REDIRECT, ""))

    /** A leading VAR=value or sudo/time/env: the real command is hidden behind it, so it never auto-allows. */
    private fun hasWrapperOrAssignment(segment: String): Boolean {
        val w = segment.trim().split(Regex("\\s+"))
        val first = w.first()
        if (ASSIGNMENT.matches(first)) return true
        return first in WRAPPERS && !(first == "env" && w.size == 1)
    }

    private fun isReadOnly(segment: String): Boolean {
        if ('\\' in segment) return false
        val words = CommandClassifier.unquotedWords(segment)
        val first = words.firstOrNull() ?: return false
        if (first !in readOnlyBuiltins || rawFirstWord(segment) != first) return false
        val args = words.drop(1)
        val flags = args.filter { it.startsWith("-") }
        fun shortHas(vararg c: Char) = flags.any { !it.startsWith("--") && it.drop(1).any { ch -> ch in c } }
        return when (first) {
            "find" -> args.none { it in FIND_WRITERS }
            "sort" -> !shortHas('o', 'T') && flags.none { it.startsWith("--output") || it.startsWith("--compress-program") || it.startsWith("--temporary-directory") }
            "uniq" -> args.count { !it.startsWith("-") } <= 1
            "rg" -> flags.none { it.startsWith("--pre") || it.startsWith("--hostname-bin") }
            "file" -> !shortHas('C', 'm') && flags.none { it.startsWith("--compile") || it.startsWith("--magic-file") }
            "date" -> !shortHas('s') && flags.none { it.startsWith("--set") } && args.none { !it.startsWith("-") && !it.startsWith("+") }
            "env", "printenv" -> args.isEmpty()
            else -> true
        }
    }

    private fun load(path: Path): PermissionRules {
        val (modified, size) = try {
            Files.getLastModifiedTime(path).toMillis() to Files.size(path)
        } catch (e: Exception) {
            cache.remove(path)
            return PermissionRules.EMPTY
        }
        cache[path]?.takeIf { it.modified == modified && it.size == size }?.let { return it.rules }
        val rules = try {
            parse(Files.readString(path))
        } catch (e: Exception) {
            System.err.println("cdm: ignoring unreadable permissions in $path: $e")
            PermissionRules.EMPTY
        }
        cache[path] = Cached(modified, size, rules)
        return rules
    }

    private fun parse(text: String): PermissionRules {
        val perms = (Json.parseToJsonElement(text) as? JsonObject)?.get("permissions") as? JsonObject
            ?: return PermissionRules.EMPTY
        fun rules(key: String) = (perms[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            .mapNotNull(BashRule::parse)
        return PermissionRules(rules("allow"), rules("ask"), rules("deny"))
    }

    companion object {
        val DEFAULT_READ_ONLY = setOf(
            "cd", "ls", "cat", "head", "tail", "pwd", "echo", "grep", "rg", "wc", "which", "file", "stat", "du", "df",
            "find", "sort", "uniq", "tr", "cut", "date", "env", "printenv", "true", "false", "test", "[",
        )
        private val WRAPPERS = setOf("sudo", "time", "env")
        private val FIND_WRITERS = setOf("-exec", "-execdir", "-delete", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
        private val SUBSTITUTION = Regex("""\$\(|`|<\(|>\(""")
        private val REDIRECT = Regex("[<>]")
        private val HARMLESS_REDIRECT = Regex("""\d?>&\d|\d?>\s?/dev/null""")
        private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
    }
}
