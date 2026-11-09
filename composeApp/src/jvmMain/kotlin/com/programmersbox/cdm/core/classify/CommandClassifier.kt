package com.programmersbox.cdm.core.classify

import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform

sealed interface CommandKind {
    data object Other : CommandKind
    data class Cdm(val subcommand: String) : CommandKind
    data class AdbGlobalSafe(val resetsTimer: Boolean) : CommandKind
    data class AdbGlobalDangerous(val what: String) : CommandKind
    data class AdbDisconnect(val address: String) : CommandKind
    /** [bareMaestro]: `maestro` with no `--device`/`--udid`; it may target either platform (see HookDecider). */
    data class Device(
        val explicitSerial: String?,
        val usbFlag: Boolean,
        val emulatorFlag: Boolean,
        val bareMaestro: Boolean = false,
    ) : CommandKind
    data object GradleNonDevice : CommandKind
    /** `adb -t <id>`: picks a device by transport ID, which the app can't map to a held device. */
    data object AdbTransportId : CommandKind
    /** An iOS command that reaches a device or simulator. [pinnable]: it names the device itself, so a held UDID can steer it. */
    data class IosDevice(val udids: List<String>, val usesBooted: Boolean, val pinnable: Boolean) : CommandKind
    /** An iOS command that touches no device (`simctl list`, `xcodebuild -list`, ...). */
    data object IosGlobalSafe : CommandKind
    data class IosGlobalDangerous(val what: String) : CommandKind
}

/**
 * One `cdm run <name> -- <command>` segment. [start]/[end] index the raw command (as [CommandClassifier] ranges do).
 * [target] is the device name or `all` (empty if missing); [rest] is the raw text after `--`, trimmed (empty if missing).
 */
data class CdmRunSegment(val start: Int, val end: Int, val target: String, val rest: String)

object CommandClassifier {
    private val gradleDeviceTask = Regex("""^(:?[\w-]+:)*(install|uninstall|connected)\w*$""")
    /** `NAME=value` or `NAME+=value` (bash appends): both are assignment words before the command. */
    private val assignment = Regex("""^[A-Za-z_][A-Za-z0-9_]*\+?=.*""", RegexOption.DOT_MATCHES_ALL)
    private const val SERIAL_VAR = "ANDROID_SERIAL="

    /** Each command segment, trimmed, with leading `(`/`{`, VAR=value and exec wrappers (sudo, timeout, env, …) stripped. */
    fun segments(command: String): List<String> =
        ranges(command).map { (s, e) -> command.substring(s + prefixLength(command.substring(s, e)), e).trim() }
            .filter { it.isNotEmpty() }

    /** Segments exactly as written (trimmed, blank ones dropped): no wrapper or assignment stripping. */
    fun rawSegments(command: String): List<String> =
        ranges(command).map { (s, e) -> command.substring(s, e).trim() }.filter { it.isNotEmpty() }

    /** The segment's words with quotes and escapes removed, as the shell would pass them. */
    fun unquotedWords(segment: String): List<String> = words(segment)

    /** Basename of the segment's first word. */
    fun firstWord(segment: String): String =
        words(segment).firstOrNull()?.substringAfterLast('/') ?: ""

    /** One kind per non-empty segment, in order (a mixed Android+iOS Gradle segment yields two). ANDROID_SERIAL assignments/exports fill in explicitSerial. */
    fun classifyAll(command: String): List<CommandKind> {
        var exported: String? = null
        val out = mutableListOf<CommandKind>()
        for ((s, e) in ranges(command)) {
            val raw = command.substring(s, e)
            val p = prefixLength(raw)
            val seg = raw.substring(p).trim()
            if (seg.isEmpty()) continue
            val w = words(raw)
            if (w.firstOrNull() == "export") {
                w.drop(1).firstOrNull { it.startsWith(SERIAL_VAR) }?.let { exported = it.removePrefix(SERIAL_VAR) }
            }
            val inline = words(raw.substring(0, p)).lastOrNull { it.startsWith(SERIAL_VAR) }?.removePrefix(SERIAL_VAR)
            val k = classifySegment(seg)
            val envSerial = inline ?: exported
            out += if (k is CommandKind.Device && k.explicitSerial == null && envSerial != null) k.copy(explicitSerial = envSerial) else k
            // A Gradle segment naming both an Android device task and an iOS test task yields both kinds.
            if (k is CommandKind.Device) mixedGradleIos(seg)?.let { out += it }
        }
        return out
    }

    /** One `CDM_IOS_UDID=` / `CDM_IOS_PAIR_UDID=` assignment. [value] is null when it isn't a literal (`$x`, `$(…)`, backticks). */
    data class IosAssignment(val pair: Boolean, val value: String?)

    /**
     * Commands that only print or search their arguments and can't assign. A match in one of their arguments is
     * exempt, unless it sits inside `$(…)` or backticks (that runs as its own command).
     */
    private val printOrSearch = setOf(
        "echo", "printf", "grep", "egrep", "fgrep", "rg", "cat", "printenv", "test", "[", "git",
    )

    /** Commands that run text as shell code. One anywhere in the command (`echo '…' | sh`) voids every exemption. */
    private val runsShellText = setOf("sh", "bash", "zsh", "dash", "ksh", "fish", "eval", "source", ".", "xargs", "parallel")

    /** Git subcommands that only print or record text; anything else (rebase --exec, aliases, hooks) may run it. */
    private val gitReadOnly = setOf("commit", "log", "grep", "show", "diff", "status")

    /** Git tokens that can run a command: `-c` (config: aliases, sshCommand), `--exec`, `-x`, foreach, bisect. */
    private fun gitRunsCommand(t: String) =
        t == "-c" || t == "--config-env" || t.startsWith("--config-env=") || t.startsWith("--exec") || t == "-x" ||
            t == "foreach" || t == "bisect"

    /** True when a `git …` segment's words ([w], `git` first) are exempt: a read-only subcommand, no runner token. */
    private fun gitExempt(w: List<String>): Boolean {
        if (w.any(::gitRunsCommand)) return false
        var i = 1
        while (i < w.size && w[i].startsWith("-")) {
            // Global options with a separate value.
            if (w[i] == "-C" || w[i] == "--git-dir" || w[i] == "--work-tree" || w[i] == "--namespace") i++
            i++
        }
        return w.getOrNull(i) in gitReadOnly
    }

    /**
     * Every `CDM_IOS_UDID=<value>` and `CDM_IOS_PAIR_UDID=<value>`, plus every `${CDM_IOS_UDID=…}` /
     * `${CDM_IOS_UDID:=…}` expansion, as [pinVarWrites] finds them (split names included). Fails closed: it
     * over-matches (`bash -c '…'`, `eval`, `$(…)` and heredocs fed to a shell all count). A `+=` gives a null value.
     * Other writes (`read`, `unset`, ...) aren't listed here; [writesPinVarOtherwise] reports them.
     */
    fun iosUdidAssignments(command: String): List<IosAssignment> =
        pinVarWrites(command).filter { (it.name == "CDM_IOS_UDID" || it.name == "CDM_IOS_PAIR_UDID") && it.value != null }
            .map { IosAssignment(it.name == "CDM_IOS_PAIR_UDID", if (it.dynamic || it.append) null else it.value) }

    /** True when [command] writes ANDROID_SERIAL, CDM_IOS_UDID or CDM_IOS_PAIR_UDID other than as a literal `NAME=value`. */
    fun writesPinVarOtherwise(command: String): Boolean =
        pinVarWrites(command).any { it.name in SINGLE_PIN_VARS && (it.value == null || (it.append && it.name == "ANDROID_SERIAL")) }

    private val SINGLE_PIN_VARS = setOf("ANDROID_SERIAL", "CDM_IOS_UDID", "CDM_IOS_PAIR_UDID")

    /**
     * The argument spans (offsets in [command]) of top-level segments whose command word is in [printOrSearch],
     * minus anything nested in `$(…)`, `<(…)`, `>(…)` or backticks. The assignment prefix and the command word are
     * never exempt. Nothing is exempt when any segment runs shell text ([runsShellText], `find -exec`): the printed
     * text may be piped or written into it.
     */
    private fun exemptRanges(command: String): List<IntRange> {
        val nested = nestedPositions(command)
        val out = mutableListOf<IntRange>()
        for ((s, e) in ranges(command)) {
            val seg = command.substring(s, e)
            val p = prefixLength(seg)
            if (p >= seg.length) continue
            val w = words(seg.substring(p))
            val word = w.firstOrNull()?.substringAfterLast('/') ?: continue
            if (word in runsShellText) return emptyList()
            if (word == "find" && w.any { it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" }) {
                return emptyList()
            }
            if (word !in printOrSearch) continue
            if (word == "git" && !gitExempt(w)) continue
            // `printf -v NAME` assigns its output to NAME.
            if (word == "printf" && w.drop(1).any { it.startsWith("-v") }) continue
            val wordEnd = tokenEnd(seg, p)
            var start = -1
            for (i in s + wordEnd until e) {
                if (nested[i]) {
                    if (start >= 0) out += start until i
                    start = -1
                } else if (start < 0) {
                    start = i
                }
            }
            if (start >= 0) out += start until e
        }
        return out
    }

    /**
     * True for every position inside `$(…)` (or `$((…))`), `<(…)`, `>(…)` or backticks, outside single quotes. Approximate on
     * purpose: quotes inside a substitution aren't tracked, so it errs towards "nested" (which is never exempt).
     */
    private fun nestedPositions(c: String): BooleanArray {
        val out = BooleanArray(c.length)
        var single = false
        var double = false
        var depth = 0
        var tick = false
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            val inside = depth > 0 || tick
            if (inside) out[i] = true
            when {
                inside -> when {
                    ch == '\\' -> {
                        if (i + 1 < c.length) out[i + 1] = true
                        i++
                    }
                    ch == '`' && depth == 0 -> tick = false
                    (ch == '$' || ch == '<' || ch == '>') && c.getOrNull(i + 1) == '(' -> {
                        depth++
                        out[i + 1] = true
                        i++
                    }
                    ch == '(' -> if (depth > 0) depth++
                    ch == ')' -> if (depth > 0) depth--
                }
                single -> if (ch == '\'') single = false
                ch == '\\' -> i++
                ch == '\'' && !double -> single = true
                ch == '"' -> double = !double
                ch == '`' -> {
                    tick = true
                    out[i] = true
                }
                // `$(…)`, and process substitution `<(…)` / `>(…)` (not inside double quotes, where it is literal).
                (ch == '$' || (!double && (ch == '<' || ch == '>'))) && c.getOrNull(i + 1) == '(' -> {
                    depth = 1
                    out[i] = true
                    out[i + 1] = true
                    i++
                }
            }
            i++
        }
        return out
    }

    /**
     * Every `ANDROID_SERIAL=<value>`, wherever it appears (inline, `export`, `env`, inside a subshell, split as
     * `ANDROID_"SERIAL"=`), as [pinVarWrites] finds them. Print/search arguments are exempt; anything else over-matches.
     */
    fun serialAssignments(command: String): List<String> =
        pinVarWrites(command).filter { it.name == "ANDROID_SERIAL" && !it.append }.mapNotNull { it.value }

    /**
     * One write to a pin variable (ANDROID_SERIAL, CDM_IOS_UDID, CDM_IOS_PAIR_UDID, or a set's per-member
     * CDM_ANDROID<N>_SERIAL / CDM_IOS<N>_UDID / CDM_IOS<N>_PAIR_UDID). [value] is the assigned text for a `NAME=value`
     * (or `${NAME:=value}`); null for any other mention that isn't a plain read (`read NAME`, `printf -v NAME`,
     * `for NAME in`, `declare -n r=NAME`, `unset NAME`, ...). [dynamic]: the value expands at run time.
     * [append]: `NAME+=value`, which adds to the current value (so [value] isn't what the variable ends up holding).
     */
    data class PinVarWrite(val name: String, val value: String?, val dynamic: Boolean, val append: Boolean = false)

    private val pinVarName =
        Regex("""(?:(?<![A-Za-z0-9_])|(?<=-v))(ANDROID_SERIAL|CDM_IOS_UDID|CDM_IOS_PAIR_UDID|CDM_(?:ANDROID|IOS)\d+_(?:SERIAL|UDID|PAIR_UDID))(?![A-Za-z0-9_])""")

    /** `${NAME=…}` / `${NAME:=…}`: these expansions assign wherever they appear, even in a printed argument. */
    private val pinVarExpansion =
        Regex("""\$\{(ANDROID_SERIAL|CDM_IOS_UDID|CDM_IOS_PAIR_UDID|CDM_(?:ANDROID|IOS)\d+_(?:SERIAL|UDID|PAIR_UDID)):?=([^}]*)}""")

    /**
     * [text] with quotes dropped and `\x` turned into `x`, so a split name (`ANDROID_"SERIAL"`, `ANDROID_\SERIAL`)
     * reads as the name the shell sees. [quoted]: the char was inside quotes or escaped (so whitespace in it doesn't
     * end a word); [literal]: single-quoted or escaped (a `$` there never expands). [from]: the original offset.
     */
    private class Flat(val text: String, val quoted: BooleanArray, val literal: BooleanArray, val from: IntArray)

    private fun flatten(s: String): Flat {
        val sb = StringBuilder()
        val quoted = ArrayList<Boolean>()
        val literal = ArrayList<Boolean>()
        val from = ArrayList<Int>()
        fun add(c: Char, q: Boolean, l: Boolean, at: Int) { sb.append(c); quoted += q; literal += l; from += at }
        var q = 0.toChar()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' && i + 1 < s.length -> { add(s[i + 1], true, true, i + 1); i++ }
                q == '\'' -> if (ch == '\'') q = 0.toChar() else add(ch, true, true, i)
                q == '"' -> if (ch == '"') q = 0.toChar() else add(ch, true, false, i)
                ch == '\'' || ch == '"' -> q = ch
                else -> add(ch, false, false, i)
            }
            i++
        }
        return Flat(sb.toString(), quoted.toBooleanArray(), literal.toBooleanArray(), from.toIntArray())
    }

    /** Builtins and keywords that can write a variable named by a plain word argument (`read NAME`, `for NAME in`, …). */
    private val varWriters = setOf(
        "read", "printf", "declare", "typeset", "local", "export", "readonly", "unset", "mapfile", "readarray",
        "getopts", "for", "select", "let", "wait", "coproc",
    )

    /** Words that come before a command without being it (`while read X`, `if ! read X`, `command read X`). */
    private val beforeCommand = setOf("while", "until", "if", "elif", "then", "do", "else", "!", "time", "builtin", "command")

    /**
     * The body ranges of heredocs whose delimiter is quoted (`<<'EOF'`, `<<"EOF"`, `<<\EOF`): their text is passed
     * as is, with no expansion. A `<<` counts outside quotes, or inside `$(…)` (Claude Code's
     * `git commit -m "$(cat <<'EOF' … EOF)"`). Heredocs with a bare delimiter expand `$(…)`, so they aren't listed.
     */
    private fun quotedHeredocBodies(c: String): List<IntRange> {
        val nested = nestedPositions(c)
        val out = mutableListOf<IntRange>()
        val pending = mutableListOf<Pair<String, Boolean>>() // delimiter, strip leading tabs
        var q = 0.toChar()
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            when {
                ch == '\n' && pending.isNotEmpty() -> {
                    var line = i + 1
                    for ((delim, tabs) in pending) {
                        val start = line
                        var end = -1
                        while (line < c.length) {
                            val nl = c.indexOf('\n', line).let { if (it < 0) c.length else it }
                            val text = c.substring(line, nl).let { if (tabs) it.trimStart('\t') else it }
                            if (text == delim) { end = line; line = minOf(nl + 1, c.length); break }
                            line = nl + 1
                        }
                        if (end < 0) end = c.length
                        if (end > start) out += start until end
                    }
                    pending.clear()
                    i = line
                    continue
                }
                q == '\'' -> if (ch == '\'') q = 0.toChar()
                ch == '\\' -> i++
                q == '"' && !nested[i] -> if (ch == '"') q = 0.toChar()
                q == 0.toChar() && (ch == '\'' || ch == '"') -> q = ch
                ch == '<' && c.startsWith("<<", i) && !c.startsWith("<<<", i) -> {
                    var j = i + 2
                    val tabs = c.getOrNull(j) == '-'
                    if (tabs) j++
                    j = skipWs(c, j)
                    val e = tokenEnd(c, j)
                    val word = c.substring(j, e)
                    if (word.any { it == '\'' || it == '"' || it == '\\' }) pending += unquote(word) to tabs
                    i = maxOf(e, i + 2)
                    continue
                }
            }
            i++
        }
        return out
    }

    /** True when some segment of [command] runs shell text ([runsShellText], `find -exec`). */
    private fun runsShell(command: String): Boolean = ranges(command).any { (s, e) ->
        val seg = command.substring(s, e)
        val p = prefixLength(seg)
        val w = words(seg.substring(minOf(p, seg.length)))
        val word = w.firstOrNull()?.substringAfterLast('/')
        word in runsShellText || (word == "find" && w.any { it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" })
    }

    /**
     * True when a mention at [a] until [b] of the flattened text [f] is a plain word argument of a command that can
     * write a variable it names (`read NAME`, `for NAME in`, `unset NAME`, `printf -v NAME`), a `-vNAME` / `r=NAME`
     * word, or sits in an arithmetic `((…))`. A mention inside a longer word or a quoted string is text.
     */
    private fun namedForWrite(f: Flat, a: Int, b: Int): Boolean {
        val t = f.text
        fun sep(i: Int) = i < 0 || i >= t.length || (!f.quoted[i] && (t[i].isWhitespace() || t[i] in ";&|()<>"))
        if (!sep(b)) return false
        // `-vNAME` (printf/read) and `r=NAME` (declare -n): word-bounded.
        if (a >= 2 && t.startsWith("-v", a - 2) && sep(a - 3)) return true
        if (a >= 1 && t[a - 1] == '=') {
            var k = a - 2
            while (k >= 0 && (t[k].isLetterOrDigit() || t[k] == '_')) k--
            return k < a - 2 && sep(k)
        }
        if (!sep(a - 1)) return false
        // The command this word belongs to: back to the nearest unquoted command boundary.
        var k = a - 1
        fun boundary(i: Int) = !f.quoted[i] && (t[i] in ";&|()`\n" || (t[i] == '{' && t.getOrNull(i - 1) != '$'))
        while (k >= 0 && !boundary(k)) k--
        if (k >= 1 && t[k] == '(' && t[k - 1] == '(') return true // arithmetic can assign
        val words = t.substring(k + 1, a).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cmd = words.dropWhile { it in beforeCommand || assignment.matches(it) }.firstOrNull()?.substringAfterLast('/')
        return cmd in varWriters
    }

    /**
     * Every write to a pin variable in [command], in order (see [PinVarWrite]). Fails closed for real writes:
     * print/search arguments ([exemptRanges]) and quoted-delimiter heredoc bodies (unless the command runs shell
     * text) are skipped, then quotes and escapes are flattened away. A mention followed by `=` / `+=`, or a
     * `${NAME:=…}` expansion, gives its value (checked like today's assignments). A mention that is a plain word
     * argument of a variable-writing builtin ([namedForWrite]) gives a null value (deny it). `$NAME` and `${NAME…}`
     * expansions other than `=` / `:=` are reads; any other mention is text.
     */
    fun pinVarWrites(command: String): List<PinVarWrite> {
        val noBodies = StringBuilder(command)
        for (r in quotedHeredocBodies(command)) for (i in r) if (command[i] != '\n') noBodies.setCharAt(i, ' ')
        val base = if (runsShell(noBodies.toString())) command else noBodies.toString()
        val blanked = StringBuilder(base)
        for (r in exemptRanges(base)) for (i in r) if (base[i] != '\'' && base[i] != '"' && base[i] != '\\') blanked.setCharAt(i, ' ')
        val f = flatten(blanked.toString())
        val t = f.text
        val out = mutableListOf<Pair<Int, PinVarWrite>>()
        for (m in pinVarName.findAll(t)) {
            val a = m.range.first
            val b = m.range.last + 1
            val name = m.value
            val before = t.getOrNull(a - 1)
            if (before == '$') continue
            // `${NAME…}`, `${#NAME}`, `${!NAME}`: reads, except `${NAME=…}` / `${NAME:=…}` (counted on the raw text below).
            val brace = when {
                before == '{' && t.getOrNull(a - 2) == '$' -> true
                (before == '#' || before == '!') && t.getOrNull(a - 2) == '{' && t.getOrNull(a - 3) == '$' -> true
                else -> false
            }
            if (brace) continue
            val append = t.startsWith("+=", b)
            if (t.getOrNull(b) != '=' && !append) {
                if (namedForWrite(f, a, b)) out += f.from[a] to PinVarWrite(name, null, false)
                continue
            }
            val vs = b + if (append) 2 else 1
            var e = vs
            while (e < t.length && (f.quoted[e] || (!t[e].isWhitespace() && t[e] !in ";&|()<>"))) e++
            val value = t.substring(vs, e)
            val dynamic = (vs until e).any { (t[it] == '$' || t[it] == '`') && !f.literal[it] }
            out += f.from[a] to PinVarWrite(name, value, dynamic, append)
        }
        for (m in pinVarExpansion.findAll(command)) {
            val raw = m.groupValues[2]
            val dynamic = '$' in raw || '`' in raw
            out += m.range.first to PinVarWrite(m.groupValues[1], unquote(raw), dynamic)
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    fun classify(command: String): CommandKind =
        classifyAll(command).fold(CommandKind.Other as CommandKind, ::merge)

    private fun merge(a: CommandKind, b: CommandKind): CommandKind {
        if (rank(b) != rank(a)) return if (rank(b) > rank(a)) b else a
        return when {
            a is CommandKind.Device && b is CommandKind.Device ->
                if (!specific(a) && specific(b)) b else a
            a is CommandKind.AdbGlobalSafe && b is CommandKind.AdbGlobalSafe ->
                CommandKind.AdbGlobalSafe(a.resetsTimer || b.resetsTimer)
            a is CommandKind.AdbDisconnect && b is CommandKind.AdbDisconnect ->
                if (a.address == b.address) a else CommandKind.AdbGlobalDangerous("disconnect")
            a is CommandKind.IosDevice && b is CommandKind.IosDevice ->
                CommandKind.IosDevice((a.udids + b.udids).distinct(), a.usesBooted || b.usesBooted, a.pinnable && b.pinnable)
            else -> a
        }
    }

    /** Platforms whose devices the command touches: ANDROID for [CommandKind.Device], IOS for iOS device/dangerous kinds. */
    fun platformsOf(command: String): Set<Platform> {
        val out = mutableSetOf<Platform>()
        for (k in classifyAll(command)) when (k) {
            is CommandKind.Device -> out += Platform.ANDROID
            is CommandKind.IosDevice, is CommandKind.IosGlobalDangerous -> out += Platform.IOS
            else -> {}
        }
        return out
    }

    /** Replaces the `booted` device argument of `xcrun simctl <sub> … booted …` with [udid]; nothing else changes. */
    fun rewriteBooted(command: String, udid: String): String {
        val sb = StringBuilder()
        var last = 0
        for ((s, e) in ranges(command)) {
            val raw = command.substring(s, e)
            val toks = mutableListOf<IntRange>()
            var i = skipWs(raw, prefixLength(raw))
            while (i < raw.length) {
                val te = tokenEnd(raw, i)
                toks += i until te
                i = skipWs(raw, te)
            }
            val w = toks.map { unquote(raw.substring(it.first, it.last + 1)) }
            val bi = simctl(w)?.bootedIndex ?: continue
            sb.append(command, last, s + toks[bi].first).append(udid)
            last = s + toks[bi].last + 1
        }
        sb.append(command, last, command.length)
        return sb.toString()
    }

    /**
     * Adds `--init-script <path>` right after `gradle`/`gradlew` in every segment that runs a Gradle iOS test task and
     * doesn't already load an `ios-pin.gradle` init script. Other segments are left exactly as written.
     */
    fun addIosInitScript(command: String, path: String): String {
        val insert = " --init-script " + shellQuote(path)
        val sb = StringBuilder()
        var last = 0
        for ((s, e) in ranges(command)) {
            val seg = command.substring(s, e)
            val start = prefixLength(seg)
            val w = words(seg.substring(start))
            if (!runsGradleIosTest(w) || loadsIosPin(w)) continue
            val at = tokenEnd(seg, skipWs(seg, start))
            sb.append(command, last, s + at).append(insert)
            last = s + at
        }
        sb.append(command, last, command.length)
        return sb.toString()
    }

    private fun specific(d: CommandKind.Device) = d.explicitSerial != null || d.usbFlag || d.emulatorFlag

    fun addCdmIdentity(command: String, holder: HolderId, cwd: String): String {
        val insert = " --holder ${holder.key} --cwd ${shellQuote(cwd)}"
        val sb = StringBuilder()
        var last = 0
        for ((s, e) in ranges(command)) {
            val seg = command.substring(s, e)
            val start = prefixLength(seg)
            if (firstWord(seg.substring(start)) != "cdm") continue
            val cdmEnd = tokenEnd(seg, skipWs(seg, start))
            val subStart = skipWs(seg, cdmEnd)
            var at = cdmEnd
            if (subStart < seg.length && seg[subStart] != '-') at = tokenEnd(seg, subStart)
            sb.append(command, last, s + at).append(insert)
            last = s + at
        }
        sb.append(command, last, command.length)
        return sb.toString()
    }

    private class CdmRunParse(
        val start: Int,
        val end: Int,
        val prefixEnd: Int,
        val restStart: Int?,
        val target: String,
        val rest: String,
    )

    private fun parseCdmRuns(command: String): List<CdmRunParse> {
        val out = mutableListOf<CdmRunParse>()
        for ((s, e) in ranges(command)) {
            val seg = command.substring(s, e)
            val start = prefixLength(seg)
            val cdmEnd = tokenEnd(seg, start)
            // `cdm`, or any path to it (`/usr/local/bin/cdm`), like the identity rewrite.
            if (unquote(seg.substring(start, cdmEnd)).substringAfterLast('/') != "cdm") continue
            val runStart = skipWs(seg, cdmEnd)
            val runEnd = tokenEnd(seg, runStart)
            if (unquote(seg.substring(runStart, runEnd)) != "run") continue
            val nameStart = skipWs(seg, runEnd)
            val nameEnd = tokenEnd(seg, nameStart)
            val name = unquote(seg.substring(nameStart, nameEnd))
            val target = if (name == "--") "" else name
            val dashStart = if (name == "--") nameStart else skipWs(seg, nameEnd)
            val dashEnd = tokenEnd(seg, dashStart)
            if (dashStart >= seg.length || seg.substring(dashStart, dashEnd) != "--") {
                out += CdmRunParse(s, e, s + start, null, target, "")
                continue
            }
            val restStart = skipWs(seg, dashEnd)
            out += CdmRunParse(s, e, s + start, s + restStart, target, seg.substring(restStart).trim())
        }
        return out
    }

    /** One entry per `cdm run` segment; a missing name or `--` yields an empty [CdmRunSegment.target] or [CdmRunSegment.rest]. */
    fun cdmRuns(command: String): List<CdmRunSegment> =
        parseCdmRuns(command).map { CdmRunSegment(it.start, it.end, it.target, it.rest) }

    /** Removes each well-formed `cdm run <name> -- ` prefix; every other byte stays. */
    fun withoutCdmRun(command: String): String {
        val sb = StringBuilder()
        var last = 0
        for (r in parseCdmRuns(command)) {
            val rs = r.restStart ?: continue
            sb.append(command, last, r.prefixEnd)
            last = rs
        }
        sb.append(command, last, command.length)
        return sb.toString()
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun rank(k: CommandKind) = when (k) {
        is CommandKind.AdbGlobalDangerous -> 6
        is CommandKind.AdbDisconnect -> 5
        is CommandKind.Device -> 4
        CommandKind.GradleNonDevice -> 3
        is CommandKind.AdbGlobalSafe -> 2
        is CommandKind.Cdm -> 1
        CommandKind.AdbTransportId -> 7
        is CommandKind.IosGlobalDangerous -> 6
        is CommandKind.IosDevice -> 4
        CommandKind.IosGlobalSafe -> 2
        CommandKind.Other -> 0
    }

    private fun classifySegment(seg: String): CommandKind = classifyWords(words(seg), words(seg, protectSingle = true))

    private fun mixedGradleIos(seg: String): CommandKind? {
        val w = words(seg)
        return if (runsGradleIosTest(w)) gradleIos(w, words(seg, protectSingle = true)) else null
    }

    /** [w] are the words as the shell passes them; [wi] the same but with `$` in single quotes made inert (never expanded). */
    private fun classifyWords(w: List<String>, wi: List<String> = w): CommandKind {
        if (w.isEmpty()) return CommandKind.Other
        return when (w[0].substringAfterLast('/')) {
            "cdm" -> CommandKind.Cdm(w.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: "")
            "gradle", "gradlew" ->
                if (w.drop(1).any { !it.startsWith("-") && gradleDeviceTask.matches(it) }) {
                    CommandKind.Device(null, false, false)
                } else if (runsGradleIosTest(w)) gradleIos(w, wi) else CommandKind.GradleNonDevice
            "adb" -> classifyAdb(w.drop(1))
            "xcodebuild" -> classifyXcodebuild(w, wi)
            "xcrun" -> classifyXcrun(w, wi)
            "maestro" -> classifyMaestro(w, wi)
            "fastlane" ->
                if (w.drop(1).any { it in fastlaneDeviceActions }) iosDevice(emptyList(), booted = false) else CommandKind.Other
            "killall" -> if ("Simulator" in w.drop(1)) CommandKind.IosGlobalDangerous("killall Simulator") else CommandKind.Other
            else -> if (w[0].substringAfterLast('/').startsWith("idevice")) iosDevice(valuesAfter(wi, "-u", "--udid"), booted = false) else CommandKind.Other
        }
    }

    // ---- iOS ----

    private val gradleIosTask = Regex("""^(:?[\w-]+:)*(ios\w*Test|\w*Simulator\w*Test)$""")
    private const val IOS_PIN_SCRIPT = "ios-pin.gradle"

    private fun runsGradleIosTest(w: List<String>): Boolean =
        w.firstOrNull()?.substringAfterLast('/') in setOf("gradle", "gradlew") &&
            w.drop(1).any { !it.startsWith("-") && gradleIosTask.matches(it) }

    /** The Gradle words load an init script named `ios-pin.gradle` (`--init-script p`, `--init-script=p`, `-I p`, `-Ip`). */
    private fun loadsIosPin(w: List<String>): Boolean {
        for (i in 1 until w.size) {
            val t = w[i]
            val script = when {
                t == "--init-script" || t == "-I" -> w.getOrNull(i + 1)
                t.startsWith("--init-script=") -> t.removePrefix("--init-script=")
                t.startsWith("-I") -> t.substring(2)
                else -> null
            }
            if (script?.substringAfterLast('/') == IOS_PIN_SCRIPT) return true
        }
        return false
    }

    /**
     * A Gradle iOS test run. The simulator test task's `--device` option picks the simulator and wins over the init
     * script, so with it the usual selector rules apply; without it the run is pinned only by `ios-pin.gradle`.
     */
    private fun gradleIos(w: List<String>, wi: List<String>): CommandKind.IosDevice {
        val devices = valuesAfter(wi, "--device")
        return if (devices.isNotEmpty()) iosDevice(devices, booted = false)
        else CommandKind.IosDevice(emptyList(), usesBooted = false, pinnable = loadsIosPin(w))
    }

    private val fastlaneDeviceActions = setOf("scan", "snapshot", "run_tests")
    private val udidRegex = Regex(
        "(?<![0-9A-Fa-f-])([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}|[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16})(?![0-9A-Fa-f])",
    )
    /** A whole selector value that is `$CDM_IOS_UDID` / `${CDM_IOS_UDID}`: `id=foo$CDM_IOS_UDID` names another device. */
    private val cdmUdidRef = Regex("""\$(CDM_IOS_UDID|\{CDM_IOS_UDID\})""")
    /** xcrun options that take a value. `-l` / `--log` / `-log` take none. */
    private val xcrunOptsWithArg = setOf("-sdk", "--sdk", "-toolchain", "--toolchain")

    /**
     * Maestro picks the device with `--device` (`--udid` is its old name). A UDID or `$CDM_IOS_UDID` selects an iOS
     * device; any other value is an Android serial (`Device(explicitSerial)`, checked like `adb -s`). With no flag it
     * is Android, marked [CommandKind.Device.bareMaestro] so the hook can tell when the holder holds only iOS.
     */
    private fun classifyMaestro(w: List<String>, wi: List<String>): CommandKind {
        val raw = valuesAfter(w, "--device", "--udid")
        if (raw.isEmpty()) return CommandKind.Device(null, false, false, bareMaestro = true)
        if (raw.any { udidRegex.containsMatchIn(it) || cdmUdidRef.containsMatchIn(it) }) {
            return iosDevice(valuesAfter(wi, "--device", "--udid"), booted = false)
        }
        return CommandKind.Device(raw.first(), false, false)
    }

    /** Values of `flag value` / `flag=value` pairs in [w]. */
    private fun valuesAfter(w: List<String>, vararg flags: String): List<String> {
        val out = mutableListOf<String>()
        for (i in 1 until w.size) {
            val t = w[i]
            if (t in flags) out += w.getOrNull(i + 1) ?: ""
            else flags.firstOrNull { t.startsWith("$it=") }?.let { out += t.removePrefix("$it=") }
        }
        return out
    }

    /**
     * [selectors] are the argument values that select a device (empty: the command names none). It is pinnable when it
     * uses `booted`, or when there are selectors and every one is a UDID or exactly `$CDM_IOS_UDID`.
     */
    private fun iosDevice(selectors: List<String>, booted: Boolean, allowPin: Boolean = true): CommandKind.IosDevice {
        val udids = selectors.flatMap { t -> udidRegex.findAll(t).map { it.value }.toList() }.distinct()
        val pinned = selectors.isNotEmpty() && selectors.all { t -> udidRegex.containsMatchIn(t) || cdmUdidRef.matches(t) }
        return CommandKind.IosDevice(udids, booted, allowPin && (booted || pinned))
    }

    private fun classifyXcodebuild(w: List<String>, wi: List<String>): CommandKind {
        val dests = valuesAfter(w, "-destination")
        val destsI = valuesAfter(wi, "-destination")
        // macOS and Mac Catalyst destinations run on this Mac, not on a device CDM hands out.
        val ios = dests.indices.filter { !isMacDestination(dests[it]) }
        if (dests.isNotEmpty() && ios.isEmpty()) return CommandKind.IosGlobalSafe
        val deviceDest = ios.any { !dests[it].startsWith("generic/") }
        val tests = w.drop(1).any { it == "test" || it == "test-without-building" }
        if (!tests && !deviceDest) return CommandKind.IosGlobalSafe
        // Only the value of the `id=` part of each iOS destination selects a device.
        val ids = ios.map { i -> destsI[i].split(',').firstOrNull { it.startsWith("id=") }?.removePrefix("id=") ?: "" }
        return iosDevice(ids, booted = false)
    }

    /** `platform=macOS[,…]`, `generic/platform=macOS`, or any destination with `variant=Mac Catalyst`. */
    private fun isMacDestination(d: String): Boolean = d.split(',').map { it.trim().removePrefix("generic/") }
        .any { it == "platform=macOS" || it == "variant=Mac Catalyst" }

    /** True if [segment] (as from [segments]) is `xcrun simctl boot <device>`. */
    fun isSimctlBoot(segment: String): Boolean = simctl(words(segment))?.sub == "boot"

    private class Simctl(val sub: String?, val subIndex: Int, val devIndex: Int?, val bootedIndex: Int?)

    /** Parses `xcrun [opts] simctl [opts] <sub> …` from [w] (w[0] is xcrun); null if it is not simctl. */
    private fun simctl(w: List<String>): Simctl? {
        if (w.firstOrNull()?.substringAfterLast('/') != "xcrun") return null
        var i = 1
        while (i < w.size && w[i].startsWith("-")) i += if (w[i] in xcrunOptsWithArg) 2 else 1
        if (w.getOrNull(i) != "simctl") return null
        i++
        while (i < w.size && w[i].startsWith("-")) i += if (w[i] == "--set") 2 else 1
        val sub = w.getOrNull(i) ?: return Simctl(null, i, null, null)
        // The device is the first positional argument. `list`/`help` take `booted` as a filter, not a device.
        val dev = (i + 1 until w.size).firstOrNull { !w[it].startsWith("-") }
        val booted = if (sub == "list" || sub == "help" || dev == null || w[dev] != "booted") null else dev
        return Simctl(sub, i, dev, booted)
    }

    private fun classifyXcrun(w: List<String>, wi: List<String>): CommandKind {
        simctl(wi)?.let { sc ->
            val sub = sc.sub ?: return CommandKind.IosGlobalSafe
            val first = w.getOrNull(sc.subIndex + 1)
            val device = sc.devIndex?.let { wi[it] }
            return when {
                sub == "list" || sub == "help" -> CommandKind.IosGlobalSafe
                sub == "shutdown" && first == "all" -> CommandKind.IosGlobalDangerous("simctl shutdown all")
                sub == "erase" && first == "all" -> CommandKind.IosGlobalDangerous("simctl erase all")
                sub == "delete" -> CommandKind.IosGlobalDangerous("simctl delete")
                else -> iosDevice(listOfNotNull(device).filter { it != "booted" }, booted = sc.bootedIndex != null)
            }
        }
        var i = 1
        while (i < w.size && w[i].startsWith("-")) {
            val o = w[i]
            if (o == "--find" || o == "-f" || o == "-find" || o.startsWith("--show") || o == "--help" || o == "--version") {
                return CommandKind.IosGlobalSafe
            }
            i += if (o in xcrunOptsWithArg) 2 else 1
        }
        val tool = w.getOrNull(i) ?: return CommandKind.IosGlobalSafe
        if (tool == "devicectl") {
            val group = w.drop(i + 1).firstOrNull { !it.startsWith("-") }
            return if (group == null || group == "list") CommandKind.IosGlobalSafe
            else iosDevice(valuesAfter(wi, "--device", "-d"), booted = false)
        }
        return classifyWords(w.drop(i), wi.drop(i))
    }

    private fun classifyAdb(args: List<String>): CommandKind {
        var serial: String? = null
        var usb = false
        var emu = false
        var i = 0
        while (i < args.size && args[i].startsWith("-") && args[i] != "--version") {
            val a = args[i]
            when {
                a == "-s" -> { serial = args.getOrNull(i + 1); i++ }
                a.startsWith("-s") && !a.startsWith("--") -> serial = a.substring(2)
                a == "-d" -> usb = true
                a == "-e" -> emu = true
                a.startsWith("-t") && !a.startsWith("--") || a.startsWith("--transport-id") -> return CommandKind.AdbTransportId
                a == "-H" || a == "-P" || a == "-L" -> i++
            }
            i++
        }
        val sub = args.getOrNull(i) ?: return CommandKind.AdbGlobalSafe(false)
        return when (sub) {
            "devices", "version", "--version", "help", "pair" -> CommandKind.AdbGlobalSafe(false)
            "connect", "reconnect" -> CommandKind.AdbGlobalSafe(true)
            "kill-server", "start-server" -> CommandKind.AdbGlobalDangerous(sub)
            "disconnect" -> args.getOrNull(i + 1)?.let { CommandKind.AdbDisconnect(it) }
                ?: CommandKind.AdbGlobalDangerous("disconnect")
            else -> CommandKind.Device(serial, usb, emu)
        }
    }

    // ---- lexing ----

    /** Raw [start, end) ranges of segments split on && || ; | & newline, outside quotes. */
    private fun ranges(c: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var q = 0.toChar()
        var i = 0
        while (i < c.length) {
            val ch = c[i]
            when {
                q != 0.toChar() -> {
                    if (ch == '\\' && q == '"') i++ else if (ch == q) q = 0.toChar()
                }
                ch == '\\' -> i++
                ch == '\'' || ch == '"' -> q = ch
                ch == ';' || ch == '\n' || ch == '|' || (ch == '&' && c.getOrNull(i + 1) != '>' && c.getOrNull(i - 1) != '>') -> {
                    out += start to i
                    while (i + 1 < c.length && (c[i + 1] == '|' || c[i + 1] == '&') && (ch == '|' || ch == '&')) i++
                    start = i + 1
                }
            }
            i++
        }
        out += start to c.length
        return out
    }

    private fun skipWs(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    /** End index of the (quote-aware) token beginning at [from]. */
    private fun tokenEnd(s: String, from: Int): Int {
        var i = from
        var q = 0.toChar()
        while (i < s.length) {
            val ch = s[i]
            if (q != 0.toChar()) {
                if (ch == '\\' && q == '"') i++ else if (ch == q) q = 0.toChar()
            } else if (ch == '\\') i++
            else if (ch == '\'' || ch == '"') q = ch
            else if (ch.isWhitespace()) break
            i++
        }
        return minOf(i, s.length)
    }

    /** With [protect], a `$` the shell would not expand (single-quoted or backslash-escaped) becomes an inert U+0001. */
    private fun unquote(t: String, protect: Boolean = false): String {
        val sb = StringBuilder()
        fun add(c: Char, literal: Boolean) { sb.append(if (protect && literal && c == '$') '\u0001' else c) }
        var q = 0.toChar()
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (q != 0.toChar()) {
                if (ch == q) q = 0.toChar()
                else if (ch == '\\' && q == '"' && i + 1 < t.length) add(t[++i], true)
                else add(ch, q == '\'')
            } else if (ch == '\'' || ch == '"') q = ch
            else if (ch == '\\' && i + 1 < t.length) add(t[++i], true)
            else sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun words(seg: String, protectSingle: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        var i = skipWs(seg, 0)
        while (i < seg.length) {
            val e = tokenEnd(seg, i)
            out += unquote(seg.substring(i, e), protectSingle)
            i = skipWs(seg, e)
        }
        return out
    }

    /**
     * Length of the leading prefix of a raw segment that only sets up how the real command runs: grouping `(` / `{`,
     * VAR=value, and exec wrappers with their options (`sudo`, `time -p`, `env -i -u X`, `timeout -s SIG 30`,
     * `nice -n 5`, `stdbuf -oL`, `nohup`, `command`, `exec`).
     */
    private fun prefixLength(seg: String): Int {
        var i = skipGrouping(seg, 0)
        while (i < seg.length) {
            val e = tokenEnd(seg, i)
            val t = seg.substring(i, e)
            if (assignment.matches(t)) {
                i = skipWs(seg, e)
                continue
            }
            val opts = wrapperOptions[t] ?: break
            i = skipWs(seg, e)
            // Options: dash tokens; some take a separate argument.
            while (i < seg.length) {
                val oe = tokenEnd(seg, i)
                val o = seg.substring(i, oe)
                if (!o.startsWith("-") || o == "-" && t != "env") break
                i = skipWs(seg, oe)
                if (o in opts.withArg) i = skipWs(seg, tokenEnd(seg, i))
            }
            // A positional argument before the command (timeout's DURATION).
            repeat(opts.positional) { if (i < seg.length) i = skipWs(seg, tokenEnd(seg, i)) }
            i = skipGrouping(seg, i)
        }
        return i
    }

    private fun skipGrouping(seg: String, from: Int): Int {
        var i = skipWs(seg, from)
        while (i < seg.length && (seg[i] == '(' || seg[i] == '{')) i = skipWs(seg, i + 1)
        return i
    }

    private class WrapperOpts(val withArg: Set<String> = emptySet(), val positional: Int = 0)

    private val wrapperOptions: Map<String, WrapperOpts> = run {
        val timeout = WrapperOpts(setOf("-s", "-k", "--signal", "--kill-after"), positional = 1)
        mapOf(
            "sudo" to WrapperOpts(setOf("-u", "-g", "-C", "-D", "-h", "-p", "-r", "-t", "-U")),
            "time" to WrapperOpts(),
            "env" to WrapperOpts(setOf("-u", "--unset", "-C", "--chdir", "-S", "--split-string", "-P")),
            "timeout" to timeout,
            "gtimeout" to timeout,
            "nice" to WrapperOpts(setOf("-n", "--adjustment")),
            "stdbuf" to WrapperOpts(setOf("-i", "-o", "-e")),
            "nohup" to WrapperOpts(),
            "command" to WrapperOpts(),
            "exec" to WrapperOpts(setOf("-a")),
        )
    }
}
