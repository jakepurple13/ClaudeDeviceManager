package com.programmersbox.cdm.core.classify

import com.programmersbox.cdm.core.model.HolderId

sealed interface CommandKind {
    data object Other : CommandKind
    data class Cdm(val subcommand: String) : CommandKind
    data class AdbGlobalSafe(val resetsTimer: Boolean) : CommandKind
    data class AdbGlobalDangerous(val what: String) : CommandKind
    data class AdbDisconnect(val address: String) : CommandKind
    data class Device(val explicitSerial: String?, val usbFlag: Boolean, val emulatorFlag: Boolean) : CommandKind
    data object GradleNonDevice : CommandKind
    /** `adb -t <id>`: picks a device by transport ID, which the app can't map to a held device. */
    data object AdbTransportId : CommandKind
}

object CommandClassifier {
    private val gradleDeviceTask = Regex("""^(:?[\w-]+:)*(install|uninstall|connected)\w*$""")
    private val assignment = Regex("""^[A-Za-z_][A-Za-z0-9_]*=.*""", RegexOption.DOT_MATCHES_ALL)
    private const val SERIAL_VAR = "ANDROID_SERIAL="
    private val serialAssignment =
        Regex("""(?<![A-Za-z0-9_])ANDROID_SERIAL=("(?:[^"\\]|\\.)*"|'[^']*'|[^\s;&|()<>]*)""")

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

    /** One kind per non-empty segment, in order. ANDROID_SERIAL assignments/exports fill in explicitSerial. */
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
        }
        return out
    }

    /**
     * Every `ANDROID_SERIAL=<value>` in the raw text, wherever it appears (inline, `export`, `env`, inside a subshell).
     * A textual scan on purpose: it over-matches (`echo ANDROID_SERIAL=x`) rather than miss a re-targeting.
     */
    fun serialAssignments(command: String): List<String> =
        serialAssignment.findAll(command).map { unquote(it.groupValues[1]) }.toList()

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
            else -> a
        }
    }

    private fun specific(d: CommandKind.Device) = d.explicitSerial != null || d.usbFlag || d.emulatorFlag

    fun addCdmIdentity(command: String, holder: HolderId, cwd: String): String {
        val quoted = "'" + cwd.replace("'", "'\\''") + "'"
        val insert = " --holder ${holder.key} --cwd $quoted"
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

    private fun rank(k: CommandKind) = when (k) {
        is CommandKind.AdbGlobalDangerous -> 6
        is CommandKind.AdbDisconnect -> 5
        is CommandKind.Device -> 4
        CommandKind.GradleNonDevice -> 3
        is CommandKind.AdbGlobalSafe -> 2
        is CommandKind.Cdm -> 1
        CommandKind.AdbTransportId -> 7
        CommandKind.Other -> 0
    }

    private fun classifySegment(seg: String): CommandKind {
        val w = words(seg)
        if (w.isEmpty()) return CommandKind.Other
        return when (firstWord(seg)) {
            "cdm" -> CommandKind.Cdm(w.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: "")
            "gradle", "gradlew" ->
                if (w.drop(1).any { !it.startsWith("-") && gradleDeviceTask.matches(it) }) {
                    CommandKind.Device(null, false, false)
                } else CommandKind.GradleNonDevice
            "adb" -> classifyAdb(w.drop(1))
            else -> CommandKind.Other
        }
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

    private fun unquote(t: String): String {
        val sb = StringBuilder()
        var q = 0.toChar()
        var i = 0
        while (i < t.length) {
            val ch = t[i]
            if (q != 0.toChar()) {
                if (ch == q) q = 0.toChar()
                else if (ch == '\\' && q == '"' && i + 1 < t.length) sb.append(t[++i])
                else sb.append(ch)
            } else if (ch == '\'' || ch == '"') q = ch
            else if (ch == '\\' && i + 1 < t.length) sb.append(t[++i])
            else sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun words(seg: String): List<String> {
        val out = mutableListOf<String>()
        var i = skipWs(seg, 0)
        while (i < seg.length) {
            val e = tokenEnd(seg, i)
            out += unquote(seg.substring(i, e))
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
