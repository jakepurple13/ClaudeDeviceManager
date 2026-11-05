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
    private val managedSettings: Path = defaultManagedSettings(),
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
        }.flatMap { listOf(it, withBasenameFirstWord(it)) }.distinct()
        if (forms.any { f -> rules.deny.any { it.matches(f) } }) return Decision.ASK
        if (permissionMode == "bypassPermissions") return Decision.ALLOW
        // A substitution runs code no segment rule has judged.
        if (SUBSTITUTION.containsMatchIn(original)) return Decision.ASK
        if (raw.any { hasRedirect(it) }) return Decision.ASK
        if (kind == RewriteKind.CDM && raw.isNotEmpty() && raw.all { rawFirstWord(it) == "cdm" }) return Decision.ALLOW
        if (forms.any { f -> rules.ask.any { it.matches(f) } }) return Decision.ASK
        val ok = raw.isNotEmpty() && raw.all { s ->
            eligible(s) && (rules.allow.any { it.matches(s) } || isReadOnly(s))
        }
        return if (ok) Decision.ALLOW else Decision.ASK
    }

    private fun withBasenameFirstWord(f: String): String {
        val t = f.trimStart()
        val first = t.substringBefore(' ')
        return if ('/' in first) first.substringAfterLast('/') + t.removePrefix(first) else f
    }

    private fun rawFirstWord(segment: String) = segment.trimStart().substringBefore(' ').substringBefore('\t')

    private fun hasRedirect(segment: String) = REDIRECT.containsMatchIn(segment.replace(HARMLESS_REDIRECT, ""))

    /** True if [segment] may be auto-allowed at all: no expansion, grouping, redirect or exec-wrapper syntax. */
    private fun eligible(segment: String): Boolean {
        val words = segment.trim().split(Regex("\\s+"))
        val first = words.first()
        if (ASSIGNMENT.matches(first)) return false
        if (first in NEVER_ELIGIBLE && !(first == "env" && words.size == 1)) return false
        var text = segment.replace(HARMLESS_REDIRECT, "").replace(SINGLE_QUOTED, "''")
        if (first == "[") text = text.trimStart().removePrefix("[")
        return text.none { it in FORBIDDEN_CHARS }
    }

    private fun isReadOnly(segment: String): Boolean {
        val words = CommandClassifier.unquotedWords(segment)
        val first = words.firstOrNull() ?: return false
        if (first !in readOnlyBuiltins || rawFirstWord(segment) != first) return false
        return when (first) {
            "env" -> words.size == 1
            "printenv" -> words.drop(1).all { VAR_NAME.matches(it) }
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
            "cd", "ls", "cat", "head", "tail", "pwd", "echo", "grep", "wc", "which", "stat", "du", "df",
            "tr", "cut", "true", "false", "test", "[", "env", "printenv",
        )
        private val NEVER_ELIGIBLE = setOf(
            "if", "then", "else", "elif", "fi", "do", "done", "while", "until", "for", "case", "esac", "function", "select",
            "command", "exec", "eval", "nohup", "nice", "timeout", "stdbuf", "xargs", "time", "sudo", "env",
            "bash", "sh", "zsh", "dash", "ksh", "fish", "source", ".",
        )
        private val FORBIDDEN_CHARS = setOf('$', '\\', '`', '{', '}', '(', ')', '*', '?', '[', '!', '<', '>')
        private val SINGLE_QUOTED = Regex("'[^']*'")
        private val VAR_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val SUBSTITUTION = Regex("""\$\(|`|<\(|>\(""")
        private val REDIRECT = Regex("[<>]")
        private val HARMLESS_REDIRECT = Regex("""\d?>&\d|\d?>\s?/dev/null""")
        private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")

        fun defaultManagedSettings(): Path =
            if (System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) {
                Path.of("/Library/Application Support/ClaudeCode/managed-settings.json")
            } else Path.of("/etc/claude-code/managed-settings.json")
    }
}
