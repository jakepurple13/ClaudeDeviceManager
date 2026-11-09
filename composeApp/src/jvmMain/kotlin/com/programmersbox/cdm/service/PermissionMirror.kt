package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.classify.CommandClassifier
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

    /**
     * The project a hook belongs to (Ruling X): the nearest ancestor of [cwd] (itself included) holding `.git` or
     * `.claude`, never `$HOME` itself or anything above it. With no such ancestor, [cwd]. The hook's `cwd` follows
     * the Bash tool's `cd`s, so it is often a subdirectory of the project.
     */
    fun projectRoot(cwd: String): Path = projectRootOf(cwd, home)

    /**
     * deny / ask: managed, user, and `.claude/settings*.json` of every directory from [cwd] up to (not including)
     * `$HOME`. allow: user plus the project root's `.claude/settings*.json` only, so a stray ancestor can't widen
     * what is auto-allowed. Missing or unreadable files contribute nothing.
     */
    fun rulesFor(cwd: String): PermissionRules {
        val base = runCatching { Path.of(cwd).toAbsolutePath().normalize() }.getOrNull()
        val user = load(home.resolve(".claude/settings.json"))
        val managed = load(managedSettings)
        val root = base?.let { projectRootOf(it.toString(), home) }
        val projectAllow = root?.let { projectFiles(it) }.orEmpty().flatMap { load(it).allow }
        val restrictive = ancestorsBelowHome(base).flatMap(::projectFiles).map(::load)
        val all = listOf(managed, user) + restrictive
        return PermissionRules(
            allow = user.allow + projectAllow,
            ask = all.flatMap { it.ask },
            deny = all.flatMap { it.deny },
        )
    }

    private fun projectFiles(dir: Path) = listOf(dir.resolve(".claude/settings.json"), dir.resolve(".claude/settings.local.json"))

    private fun ancestorsBelowHome(start: Path?): List<Path> {
        val h = home.toAbsolutePath().normalize()
        return generateSequence(start) { it.parent }.takeWhile { it != h }.toList()
    }

    /**
     * A command with a `cdm run <name> -- ` segment is judged with those prefixes removed, so an allow rule for `cdm`
     * never auto-allows what runs inside, and cdm's own ALLOW never applies to it: the inner command gets the answer it
     * would get on its own.
     */
    fun decide(original: String, kind: RewriteKind, cwd: String, permissionMode: String?): Decision {
        if (CommandClassifier.cdmRuns(original).isNotEmpty()) {
            return judge(CommandClassifier.withoutCdmRun(original), original, cdmOnlyAllowed = false, cwd, permissionMode)
        }
        return judge(original, original, cdmOnlyAllowed = kind == RewriteKind.CDM, cwd, permissionMode)
    }

    /** Judges [original]; deny and ask rules also see [asWritten] (the command before `cdm run` prefixes were removed). */
    private fun judge(original: String, asWritten: String, cdmOnlyAllowed: Boolean, cwd: String, permissionMode: String?): Decision {
        val rules = rulesFor(cwd)
        val raw = CommandClassifier.rawSegments(original)
        // Every form a deny/ask rule might be written against: as written, wrappers stripped, quotes removed.
        val written = if (asWritten == original) emptyList() else CommandClassifier.rawSegments(asWritten)
        val forms = (raw + written + CommandClassifier.segments(original)).flatMap {
            listOf(it, CommandClassifier.unquotedWords(it).joinToString(" "))
        }.flatMap { listOf(it, withBasenameFirstWord(it)) }.distinct()
        if (forms.any { f -> rules.deny.any { it.matches(f) } }) return Decision.ASK
        if (permissionMode == "bypassPermissions") return Decision.ALLOW
        // A substitution runs code no segment rule has judged.
        if (SUBSTITUTION.containsMatchIn(original)) return Decision.ASK
        if (raw.any { hasRedirect(it) }) return Decision.ASK
        if (cdmOnlyAllowed && raw.isNotEmpty() && raw.all { rawFirstWord(it) == "cdm" }) return Decision.ALLOW
        if (forms.any { f -> rules.ask.any { it.matches(f) } }) return Decision.ASK
        val start = runCatching { Path.of(cwd).toAbsolutePath().normalize() }.getOrNull() ?: return Decision.ASK
        // In a pipeline a `cd` runs in a subshell, so later segments may still be in the old directory.
        val dirs = Dirs(projectRootOf(start.toString(), home), mutableSetOf(start), keepOld = '|' in original)
        val ok = raw.isNotEmpty() && raw.all { s ->
            val readOnly = isReadOnly(s, dirs) // always run: it tracks `cd`s for later segments
            eligible(s) && (rules.allow.any { it.matches(s) } || readOnly)
        }
        return if (ok) Decision.ALLOW else Decision.ASK
    }

    /**
     * Where the shell may be while a segment runs: every directory a `cd` so far could have left it in (a `cd` in a
     * pipeline runs in a subshell, so with [keepOld] the old ones stay possible). Null [possible] means unknown
     * (`cd`, `cd -`, `cd /x`).
     */
    private class Dirs(val root: Path, var possible: MutableSet<Path>?, val keepOld: Boolean)

    private fun withBasenameFirstWord(f: String): String {
        val t = f.trimStart()
        val first = t.substringBefore(' ')
        return if ('/' in first) first.substringAfterLast('/') + t.removePrefix(first) else f
    }

    private fun rawFirstWord(segment: String) = segment.trimStart().substringBefore(' ').substringBefore('\t')

    private fun hasRedirect(segment: String) = REDIRECT.containsMatchIn(stripHarmlessRedirects(segment))

    /** True if [segment] may be auto-allowed at all: plain command word, no expansion, grouping, redirect or exec-wrapper syntax. */
    private fun eligible(segment: String): Boolean {
        val first = segment.trim().split(Regex("\\s+")).first()
        val bare = segment.trim().split(Regex("\\s+")).size == 1
        if (first != "[" && !PLAIN_WORD.matches(first)) return false
        if (first in NEVER_ELIGIBLE && !(first == "env" && bare)) return false
        if (first.substringAfterLast('/') in NEVER_ELIGIBLE && !(first.substringAfterLast('/') == "env" && bare)) return false
        var text = stripHarmlessRedirects(segment)
        if (first == "[") text = text.trimStart().removePrefix("[")
        return quoteAwareClean(text)
    }

    /** Outside quotes every forbidden char applies; in single quotes none; in double quotes `$`, backtick, backslash. */
    private fun quoteAwareClean(text: String): Boolean {
        var q = ' '
        for (c in text) {
            when (q) {
                '\'' -> if (c == '\'') q = ' '
                '"' -> when {
                    c == '"' -> q = ' '
                    c == '$' || c == '`' || c == '\\' -> return false
                }
                else -> when {
                    c == '\'' || c == '"' -> q = c
                    c in FORBIDDEN_CHARS -> return false
                }
            }
        }
        return q == ' '
    }

    private fun stripHarmlessRedirects(segment: String) = segment.replace(HARMLESS_REDIRECT, " ")

    private fun isReadOnly(segment: String, dirs: Dirs): Boolean {
        val words = CommandClassifier.unquotedWords(segment)
        val first = words.firstOrNull() ?: return false
        if (words.firstOrNull() == "cd") trackCd(words, dirs)
        if (first !in readOnlyBuiltins || rawFirstWord(segment) != first) return false
        val ok = when (first) {
            "env" -> words.size == 1
            "printenv" -> words.drop(1).all { VAR_NAME.matches(it) }
            "cd" -> words.size == 2 && !words[1].startsWith("-")
            else -> true
        }
        return ok && words.drop(1).filter { !it.startsWith("-") }.all { insideProject(it, dirs) }
    }

    /** True if [arg], read as a path from every directory the shell may be in, stays inside the project root. */
    private fun insideProject(arg: String, dirs: Dirs): Boolean {
        if (arg.startsWith("/") || arg.startsWith("~")) return false
        val possible = dirs.possible ?: return false
        return possible.all { d -> runCatching { d.resolve(arg).normalize().startsWith(dirs.root) }.getOrDefault(false) }
    }

    private fun trackCd(words: List<String>, dirs: Dirs) {
        val possible = dirs.possible ?: return
        val target = words.getOrNull(1)
        if (words.size != 2 || target == null || target.startsWith("-") || target.startsWith("/") || target.startsWith("~")) {
            dirs.possible = null
            return
        }
        val next = possible.mapNotNull { runCatching { it.resolve(target).normalize() }.getOrNull() }
        dirs.possible = when {
            next.size != possible.size -> null
            dirs.keepOld -> (possible + next).toMutableSet()
            else -> next.toMutableSet()
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
            "bash", "sh", "zsh", "dash", "ksh", "fish", "source", ".", "trap", "setsid", "caffeinate", "doas", "watch", "builtin",
        )
        private val FORBIDDEN_CHARS = setOf('$', '\\', '`', '{', '}', '(', ')', '*', '?', '[', '!', '<', '>')
        private val VAR_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val SUBSTITUTION = Regex("""\$\(|`|<\(|>\(""")
        private val REDIRECT = Regex("[<>]")
        private val HARMLESS_REDIRECT = Regex("""(?<=^|\s)(?:2>&1|1>&2|>&2|(?:2>|>|&>) ?/dev/null)(?=\s|$)""")
        private val PLAIN_WORD = Regex("^[A-Za-z0-9_./+-]+$")

        /** See [PermissionMirror.projectRoot]. */
        fun projectRootOf(cwd: String, home: Path): Path {
            val start = Path.of(cwd).toAbsolutePath().normalize()
            val h = home.toAbsolutePath().normalize()
            return generateSequence(start) { it.parent }
                .takeWhile { it != h }
                .firstOrNull { Files.exists(it.resolve(".git")) || Files.isDirectory(it.resolve(".claude")) }
                ?: start
        }

        fun defaultManagedSettings(): Path =
            if (System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) {
                Path.of("/Library/Application Support/ClaudeCode/managed-settings.json")
            } else Path.of("/etc/claude-code/managed-settings.json")
    }
}
