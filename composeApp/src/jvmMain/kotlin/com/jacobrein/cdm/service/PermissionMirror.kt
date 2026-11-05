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
import kotlinx.serialization.json.jsonObject

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
 */
class PermissionMirror(
    private val home: Path,
    private val readOnlyBuiltins: Set<String> = DEFAULT_READ_ONLY,
) {
    private data class Cached(val modified: Long, val size: Long, val rules: PermissionRules)

    private val cache = ConcurrentHashMap<Path, Cached>()

    /** Merges user, project and local settings. Missing or unreadable files contribute nothing. */
    fun rulesFor(cwd: String): PermissionRules {
        val files = buildList {
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
        val segments = CommandClassifier.segments(original)
        if (segments.any { s -> rules.deny.any { it.matches(s) } }) return Decision.ASK
        if (permissionMode == "bypassPermissions") return Decision.ALLOW
        // A substitution runs code no segment rule has judged.
        if (SUBSTITUTION.containsMatchIn(original)) return Decision.ASK
        if (kind == RewriteKind.CDM && segments.isNotEmpty() && segments.all { rawFirstWord(it) == "cdm" }) return Decision.ALLOW
        if (segments.any { s -> rules.ask.any { it.matches(s) } }) return Decision.ASK
        val ok = segments.all { s -> rules.allow.any { it.matches(s) } || isReadOnly(s) }
        return if (ok) Decision.ALLOW else Decision.ASK
    }

    /** The first token, unquoted-looking and with its path kept: `/tmp/x/ls` is not `ls`. */
    private fun rawFirstWord(segment: String) = segment.trimStart().substringBefore(' ').substringBefore('\t')

    private fun isReadOnly(segment: String): Boolean {
        val words = segment.trim().split(Regex("\\s+"))
        val first = words.first()
        if (first !in readOnlyBuiltins) return false
        val args = words.drop(1)
        if (REDIRECT.containsMatchIn(segment.replace(HARMLESS_REDIRECT, ""))) return false
        return when (first) {
            "find" -> args.none { it in FIND_WRITERS }
            "sort" -> args.none { it == "-o" || it.startsWith("--output") || (it.startsWith("-") && !it.startsWith("--") && it.drop(1).contains('o')) }
            "env", "printenv" -> args.all { ASSIGNMENT.matches(it) }
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
        private val FIND_WRITERS = setOf("-exec", "-execdir", "-delete", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
        private val SUBSTITUTION = Regex("""\$\(|`|<\(|>\(""")
        private val REDIRECT = Regex("[<>]")
        private val HARMLESS_REDIRECT = Regex("""\d?>&\d|\d?>\s?/dev/null""")
        private val ASSIGNMENT = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""")
    }
}
