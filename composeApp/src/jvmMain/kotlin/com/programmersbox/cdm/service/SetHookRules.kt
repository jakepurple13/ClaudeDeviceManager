package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.Allocator
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.GrabResult
import com.programmersbox.cdm.core.allocator.GrabSetResult
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.ReleaseReason
import com.programmersbox.cdm.core.allocator.SetMember
import com.programmersbox.cdm.core.allocator.Timers
import com.programmersbox.cdm.core.classify.CdmRunSegment
import com.programmersbox.cdm.core.classify.CommandClassifier
import com.programmersbox.cdm.core.classify.CommandKind
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import java.nio.file.Path

/**
 * The hook rules for `cdm run <name> -- <command>` and for every command from a set holder (spec "`cdm run`" and
 * "Commands without `cdm run`").
 *
 * [decide] returns null when the command has no `cdm run` segment and the holder holds no set (nor a timed-out set it
 * can take back for a device command); [HookDecider] then decides it as before. Otherwise every reply is a
 * [HookReply.Deny] or a [RewriteKind.PIN] rewrite.
 *
 * Not thread-safe: [HookDecider] calls it inside its decision, under the service Mutex.
 */
class SetHookRules(
    private val allocator: Allocator,
    private val iosPinScript: () -> Path? = { null },
    private val timers: () -> Timers = { Timers() },
    private val defaultCategory: suspend (cwd: String) -> Category = { Category.ANY },
) {
    /** How to give back what this decision took back after a timeout, newest last; run when it ends in a denial. */
    private val undos = ArrayList<() -> Unit>()

    suspend fun decide(holder: HolderId, cwd: String, command: String): HookReply? {
        undos.clear()
        val reply = decideInner(holder, cwd, command)
        // A denied command doesn't run, so it keeps nothing it took back on the way.
        if (reply is HookReply.Deny) undos.asReversed().forEach { it() }
        undos.clear()
        return reply
    }

    private suspend fun decideInner(holder: HolderId, cwd: String, command: String): HookReply? {
        val runs = CommandClassifier.cdmRuns(command)
        // A `cdm run` the parser didn't take as one: the script would run it unpinned.
        val runKinds = CommandClassifier.classifyAll(command).count { it == CommandKind.Cdm("run") }
        if (runKinds != runs.size) return HookReply.Deny(USAGE)
        val setHolder = allocator.setOf(holder) != null || allocator.releasedSetOf(holder) != null
        // The per-member variables are only ever read: a command that sets them could point one at another device.
        if ((runs.isNotEmpty() || setHolder) && setsMemberVariable(command)) return HookReply.Deny(MEMBER_VARIABLE)
        // A holder waiting for a set never grabs a single device on first use (that would drop its place in line).
        val queued = allocator.statusOf(holder) as? HolderStatus.Queued
        if (queued?.set != null && holdsNothing(holder)) {
            val kinds = CommandClassifier.classifyAll(command)
            if (runs.isNotEmpty() || kinds.any { it is CommandKind.Device || it is CommandKind.IosDevice }) {
                return HookReply.Deny(waitingForSet(queued.position))
            }
        }
        return if (runs.isEmpty()) withoutRun(holder, cwd, command) else withRuns(holder, cwd, command, runs)
    }

    /** One device the holder holds under [name]. [pin]: the Android serial or iOS UDID to export (null: offline). */
    private class Member(
        val name: String,
        val platform: Platform,
        val device: AllocDevice?,
        val udid: String,
        val pairUdid: String?,
        /** Every serial or UDID that reaches this member (both sides of a pair). */
        val serials: List<String>,
        val pin: String?,
    ) {
        val label: String get() = device?.let(::label) ?: udid
    }

    /** [isSet]: a set, so the per-member variables are exported; otherwise a single holder's `android1`/`ios1`. */
    private class Held(val members: List<Member>, val isSet: Boolean) {
        fun on(p: Platform) = members.filter { it.platform == p }
        val names get() = members.joinToString(", ") { it.name }

        /** `CDM_ANDROID1_SERIAL`, `CDM_IOS1_UDID`, `CDM_IOS1_PAIR_UDID`, ... in member order (sets only). */
        val vars: Map<String, String> = if (!isSet) emptyMap() else buildMap {
            for (m in members) {
                val n = m.name.uppercase()
                if (m.platform == Platform.ANDROID) {
                    m.pin?.let { put("CDM_${n}_SERIAL", it) }
                } else {
                    put("CDM_${n}_UDID", m.udid)
                    m.pairUdid?.let { put("CDM_${n}_PAIR_UDID", it) }
                }
            }
        }
        val varsPrefix = vars.entries.joinToString("") { "export ${it.key}=${it.value}; " }
    }

    private fun member(m: SetMember, snap: AllocatorSnapshot): Member {
        val d = snap.devices.firstOrNull { it.key == m.device }
        val partner = m.partner?.let { k -> snap.devices.firstOrNull { it.key == k } }
        return if (m.platform == Platform.ANDROID) {
            val serials = d?.connectedSerials.orEmpty() + partner?.connectedSerials.orEmpty()
            Member(m.name, m.platform, d, m.device.hardwareSerial, null, serials, d?.connectedSerials?.firstOrNull())
        } else {
            val udids = listOfNotNull(m.device.hardwareSerial, m.partner?.hardwareSerial)
            Member(m.name, m.platform, d, m.device.hardwareSerial, m.partner?.hardwareSerial, udids, m.device.hardwareSerial)
        }
    }

    /** The holder's members: its set, or its single holds as `android1` / `ios1`. Null when it holds nothing. */
    private fun held(holder: HolderId): Held? {
        val snap = allocator.snapshot()
        allocator.setOf(holder)?.let { set -> return Held(set.members.map { member(it, snap) }, isSet = true) }
        val singles = allocator.holdingsOf(holder).map { h ->
            val name = if (h.platform == Platform.ANDROID) "android1" else "ios1"
            member(SetMember(name, h.platform, h.device, h.partner), snap)
        }
        return if (singles.isEmpty()) null else Held(singles, isSet = false)
    }

    private fun holdsNothing(holder: HolderId) = allocator.setOf(holder) == null && allocator.holdingsOf(holder).isEmpty()

    /** Takes back a timed-out set (only when the holder holds nothing at all). Null on success, else the denial. */
    private fun regrabSet(holder: HolderId): HookReply.Deny? {
        val was = allocator.releasedSetOf(holder)
        return when (allocator.tryRegrabSet(holder)) {
            is GrabSetResult.Granted -> {
                if (was != null) undos += { allocator.undoRegrabSet(holder, was) }
                null
            }
            GrabSetResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
            else -> HookReply.Deny(SET_TAKEN)
        }
    }

    // ---- commands with `cdm run` ----

    private suspend fun withRuns(holder: HolderId, cwd: String, command: String, runs: List<CdmRunSegment>): HookReply {
        // Rule 1: everything outside the `cdm run` segments must stay off devices.
        val othersText = withoutRunSegments(command, runs)
        val others = CommandClassifier.classifyAll(othersText)
        others.firstNotNullOfOrNull(::globalDeny)?.let { return it }
        if (others.any { it is CommandKind.Device || it is CommandKind.IosDevice }) return HookReply.Deny(OWN_RUN)
        if (others.any { it == CommandKind.Cdm("acquire") }) return HookReply.Deny(HookDecider.ACQUIRE_ALONE)

        for (r in runs) {
            val raw = command.substring(r.start, r.end).trim()
            if (raw.startsWith("(") || raw.startsWith("{") || unbalancedClose(r.rest)) return HookReply.Deny(GROUPED)
            if (CommandClassifier.unquotedWords(raw).firstOrNull()?.substringAfterLast('/') != "cdm") return HookReply.Deny(WRAPPED)
            if (r.target.isEmpty() || r.rest.isEmpty()) return HookReply.Deny(USAGE)
            // A comment comments out the rest of its line, so the subshell closes on a new line. Text after the segment
            // on that line was part of the comment, and would run after the rewrite.
            if (hasComment(r.rest) && command.substring(r.end).substringBefore('\n').isNotBlank()) {
                return HookReply.Deny(COMMENT)
            }
        }

        val held = when (val e = ensureHeld(holder, cwd, runs)) {
            is Ensured.Stop -> return e.deny
            is Ensured.Got -> e.held
        }

        val snap = allocator.snapshot()
        // The other segments: `adb disconnect` of a non-member, an ANDROID_SERIAL / CDM_IOS_UDID assignment, a global iOS command.
        check(holder, othersText, others, emptyList(), held.members.flatMap { it.serials }, "your `cdm run` commands", snap)
            ?.let { return it }
        val pieces = ArrayList<String>()
        for (r in runs) {
            val targets = if (r.target == ALL) held.members else {
                listOf(held.members.firstOrNull { it.name == r.target } ?: return HookReply.Deny(unknownName(r.target, held)))
            }
            pieces += when (val d = runSegment(holder, r, targets, all = r.target == ALL, held, snap)) {
                is Decided.Stop -> return d.deny
                is Decided.Text -> d.text
            }
        }

        // Splice each rewritten segment in place; separators and the other segments stay as written.
        val sb = StringBuilder()
        var last = 0
        runs.forEachIndexed { i, r ->
            val raw = command.substring(r.start, r.end)
            val from = r.start + (raw.length - raw.trimStart().length)
            val to = r.end - (raw.length - raw.trimEnd().length)
            sb.append(command, last, from).append(pieces[i])
            last = to
        }
        sb.append(command, last, command.length)
        // The other segments' member references become literal too (the pieces already are).
        var body = substitute(sb.toString(), held.vars)
        if (others.any { it is CommandKind.Cdm }) body = CommandClassifier.addCdmIdentity(body, holder, cwd)
        // Other segments get what they'd get on their own; each `cdm run` subshell overrides it for its own command.
        val ambient = if (others.any { it !is CommandKind.Cdm }) ambientPrefix(held) else ""

        if (held.isSet) {
            allocator.recordSetActivity(holder)
        } else {
            runs.flatMap { r -> held.members.filter { r.target == ALL || it.name == r.target } }.map { it.platform }.distinct()
                .forEach { allocator.recordDeviceActivity(holder, it) }
        }
        return HookReply.Rewrite(ambient + body, command, RewriteKind.PIN)
    }

    /** [command] with each `cdm run` segment blanked out. */
    private fun withoutRunSegments(command: String, runs: List<CdmRunSegment>): String {
        val sb = StringBuilder()
        var last = 0
        for (r in runs) {
            sb.append(command, last, r.start).append(' ')
            last = r.end
        }
        sb.append(command, last, command.length)
        return sb.toString()
    }

    /**
     * The holder's members for [runs], taking back what timed out: a whole timed-out set, or a single holder's timed-out
     * `android1` / `ios1`. A denial when it holds nothing (and nothing came back) or a regrab failed.
     */
    private suspend fun ensureHeld(holder: HolderId, cwd: String, runs: List<CdmRunSegment>): Ensured {
        if (allocator.statusOf(holder) == HolderStatus.Blocked) return Ensured.Stop(HookReply.Deny(releasedByUser(blocked = true)))
        // Only a holder that holds nothing at all takes back a timed-out set.
        if (holdsNothing(holder) && allocator.releasedSetOf(holder) != null) {
            regrabSet(holder)?.let { return Ensured.Stop(it) }
        }
        if (allocator.setOf(holder) == null) {
            // Today's per-platform regrab for a single holder whose `android1` / `ios1` timed out.
            for (p in runs.mapNotNull { singleName[it.target] }.distinct()) {
                if (allocator.holdingsOf(holder).any { it.platform == p }) continue
                val st = allocator.statusOf(holder, p) as? HolderStatus.Released ?: continue
                if (st.reason != ReleaseReason.IDLE_TIMEOUT && st.reason != ReleaseReason.EDIT_TIMEOUT) continue
                when (val g = allocator.tryRegrab(holder, p)) {
                    is GrabResult.Granted -> undos += { allocator.undoRegrab(holder, p, st) }
                    GrabResult.Blocked -> return Ensured.Stop(HookReply.Deny(releasedByUser(blocked = true)))
                    else -> return Ensured.Stop(
                        HookReply.Deny(
                            releasedAfterTimeout(allocator.snapshot(), timers(), st, g, ios = p == Platform.IOS) { defaultCategory(cwd) },
                        ),
                    )
                }
            }
        }
        return held(holder)?.let { Ensured.Got(it) } ?: Ensured.Stop(HookReply.Deny(NO_DEVICES))
    }

    private sealed interface Ensured {
        data class Got(val held: Held) : Ensured
        data class Stop(val deny: HookReply.Deny) : Ensured
    }

    private sealed interface Decided {
        data class Text(val text: String) : Decided
        data class Stop(val deny: HookReply.Deny) : Decided
    }

    /** Decides one `cdm run` segment's command against [targets] and returns its `( export …; <rest> )` rewrite. */
    private fun runSegment(
        holder: HolderId,
        r: CdmRunSegment,
        targets: List<Member>,
        all: Boolean,
        held: Held,
        snap: AllocatorSnapshot,
    ): Decided {
        fun stop(reason: String) = Decided.Stop(HookReply.Deny(reason))
        // The command that runs is the text the checks saw: member references are literal values in both.
        var out = substitute(r.rest, held.vars)
        var sub = out
        var kinds = CommandClassifier.classifyAll(sub)
        if (kinds.isEmpty()) return stop(USAGE)
        if (kinds.any { it is CommandKind.Cdm }) return stop(NESTED_CDM)
        if (all && kinds.any { it is CommandKind.IosDevice } &&
            CommandClassifier.segments(sub).any { CommandClassifier.firstWord(it) in GRADLE }
        ) return stop(ALL_KMP)
        if (!all && kinds.any { it is CommandKind.IosDevice && !it.pinnable }) {
            iosPinScript()?.let { path ->
                val p = path.toAbsolutePath().toString()
                out = CommandClassifier.addIosInitScript(out, p)
                sub = CommandClassifier.addIosInitScript(sub, p)
                kinds = CommandClassifier.classifyAll(sub)
            }
        }
        if (all && kinds.any { it is CommandKind.IosDevice && (it.udids.isEmpty() || it.usesBooted) }) return stop(ALL_NAME_IOS)
        // Maestro reads no ANDROID_SERIAL comma list: without --device it would pick a device on its own.
        if (all && kinds.any { it is CommandKind.Device && it.bareMaestro && it.explicitSerial == null }) return stop(ALL_MAESTRO)

        val what = if (all) "your devices (${held.names})" else "${targets.single().name}, ${targets.single().label}"
        check(holder, sub, kinds, targets, targets.flatMap { it.serials }, what, snap)?.let { return Decided.Stop(it) }

        val android = targets.filter { it.platform == Platform.ANDROID }
        val ios = targets.filter { it.platform == Platform.IOS }
        val touchesAndroid = kinds.any { it is CommandKind.Device } || CommandClassifier.serialAssignments(sub).isNotEmpty()
        android.firstOrNull { it.pin == null && (touchesAndroid || !all) }?.let { m -> return stop(m.device?.let(::heldOffline) ?: offline(m)) }

        val exports = StringBuilder()
        // `cdm run all` never has a CDM_IOS_UDID, even when the surrounding prefix exports one.
        if (all) exports.append("unset CDM_IOS_UDID CDM_IOS_PAIR_UDID; ")
        val serials = android.mapNotNull { it.pin }
        if (serials.isNotEmpty()) exports.append("export ANDROID_SERIAL=").append(serials.joinToString(",")).append("; ")
        if (!all) ios.singleOrNull()?.let { m ->
            exports.append(pinPrefix(null, m.udid, m.pairUdid))
            if (kinds.any { it is CommandKind.IosDevice && it.usesBooted }) out = CommandClassifier.rewriteBooted(out, m.udid)
        }
        val close = if (hasComment(out)) "\n)" else " )"
        return Decided.Text("( $exports${held.varsPrefix}$out$close")
    }

    // ---- set holders' commands without `cdm run` ----

    private fun withoutRun(holder: HolderId, cwd: String, command: String): HookReply? {
        val raw = CommandClassifier.classifyAll(command)
        if (raw.isEmpty() || raw.all { it is CommandKind.Cdm }) return null
        if (allocator.setOf(holder) == null) {
            // A timed-out set comes back on its next device command (spec: "or explicit-serial command for an old member").
            if (!holdsNothing(holder) || allocator.releasedSetOf(holder) == null || !touchesDevices(command, raw)) return null
            regrabSet(holder)?.let { return it }
        }
        val held = held(holder) ?: return null
        if (raw.any { it == CommandKind.Cdm("acquire") }) return HookReply.Deny(HookDecider.ACQUIRE_ALONE)

        // The command that runs is the text the checks saw: member references are literal values in both.
        var out = substitute(command, held.vars)
        var sub = out
        var kinds = CommandClassifier.classifyAll(sub)
        if (kinds.any { it is CommandKind.IosDevice && !it.pinnable }) {
            iosPinScript()?.let { path ->
                val p = path.toAbsolutePath().toString()
                out = CommandClassifier.addIosInitScript(out, p)
                sub = CommandClassifier.addIosInitScript(sub, p)
                kinds = CommandClassifier.classifyAll(sub)
            }
        }
        val snap = allocator.snapshot()
        kinds.firstNotNullOfOrNull(::globalDeny)?.let { return it }

        // Android: the one member, or the member every explicit serial names.
        val androids = held.on(Platform.ANDROID)
        val assigned = CommandClassifier.serialAssignments(sub)
        val devices = kinds.filterIsInstance<CommandKind.Device>()
        val usesAndroid = devices.isNotEmpty() || assigned.isNotEmpty()
        val chosenAndroid = if (!usesAndroid) null else {
            val explicit = devices.mapNotNull { it.explicitSerial } + assigned
            when {
                androids.isEmpty() -> return HookReply.Deny(noMember("Android", held))
                androids.size == 1 -> androids.single()
                else -> androids.filter { m -> explicit.any { it in m.serials } }.singleOrNull()
                    ?: return HookReply.Deny(several("Android", androids))
            }
        }
        chosenAndroid?.let { m -> if (m.pin == null) return HookReply.Deny(m.device?.let(::heldOffline) ?: offline(m)) }

        // iOS: the same, with UDIDs.
        val iosMembers = held.on(Platform.IOS)
        val iosAssigned = CommandClassifier.iosUdidAssignments(sub)
        if (iosAssigned.any { it.value == null }) return HookReply.Deny(HookDecider.DYNAMIC_UDID)
        val iosKinds = kinds.filterIsInstance<CommandKind.IosDevice>()
        val usesIos = iosKinds.isNotEmpty() || iosAssigned.isNotEmpty()
        val chosenIos = if (!usesIos) null else {
            val explicit = iosKinds.flatMap { it.udids } + iosAssigned.mapNotNull { it.value }
            when {
                iosMembers.isEmpty() -> return HookReply.Deny(noMember("iOS", held))
                iosMembers.size == 1 -> iosMembers.single()
                else -> iosMembers.filter { m -> explicit.any { u -> m.serials.any { it.equals(u, ignoreCase = true) } } }
                    .singleOrNull() ?: return HookReply.Deny(several("iOS", iosMembers))
            }
        }

        val allowed = listOfNotNull(chosenAndroid, chosenIos)
        val what = allowed.joinToString(" and ") { "${it.name}, ${it.label}" }
        check(holder, sub, kinds, allowed, held.on(Platform.ANDROID).flatMap { it.serials }, what, snap)?.let { return it }

        val androidPin = chosenAndroid?.pin ?: platformPin(androids)?.first
        val iosPin = chosenIos?.let { it.udid to it.pairUdid } ?: platformPin(iosMembers)
        if (chosenIos != null && iosKinds.any { it.usesBooted }) out = CommandClassifier.rewriteBooted(out, chosenIos.udid)
        if (kinds.any { it is CommandKind.Cdm }) out = CommandClassifier.addCdmIdentity(out, holder, cwd)

        if (usesAndroid || usesIos || kinds.any { it == CommandKind.GradleNonDevice || (it is CommandKind.AdbGlobalSafe && it.resetsTimer) }) {
            allocator.recordSetActivity(holder)
        }
        val prefix = pinPrefix(androidPin, iosPin?.first, iosPin?.second) + held.varsPrefix
        return HookReply.Rewrite(prefix + out, command, RewriteKind.PIN)
    }

    private fun touchesDevices(command: String, kinds: List<CommandKind>) =
        kinds.any { it is CommandKind.Device || it is CommandKind.IosDevice } ||
            CommandClassifier.serialAssignments(command).isNotEmpty() || CommandClassifier.iosUdidAssignments(command).isNotEmpty()

    /** What a command that names no device of a platform gets: the one member's pin, the sentinel, or nothing. */
    private fun platformPin(members: List<Member>): Pair<String?, String?>? = when (members.size) {
        0 -> null
        1 -> members.single().let { it.pin to it.pairUdid }
        else -> SENTINEL to null
    }

    /** The prefix for segments that aren't `cdm run`: as [withoutRun] would give a command with no device in it. */
    private fun ambientPrefix(held: Held): String {
        val a = platformPin(held.on(Platform.ANDROID))?.first
        val i = platformPin(held.on(Platform.IOS))
        return pinPrefix(a, i?.first, i?.second) + held.varsPrefix
    }

    // ---- checks ----

    private fun globalDeny(k: CommandKind): HookReply.Deny? = when (k) {
        CommandKind.AdbTransportId -> HookReply.Deny(HookDecider.TRANSPORT_ID)
        is CommandKind.AdbGlobalDangerous -> HookReply.Deny(HookDecider.AFFECTS_OTHERS)
        else -> null
    }

    /**
     * Today's checks, with only [allowed] as the holder's devices: every serial, UDID, `-d` / `-e`, ANDROID_SERIAL and
     * CDM_IOS_UDID in [sub] must land on one of them, and each platform's command needs an allowed member of it.
     * [ownSerials] are the addresses `adb disconnect` may name. [what] names the allowed devices in a denial.
     */
    private fun check(
        holder: HolderId,
        sub: String,
        kinds: List<CommandKind>,
        allowed: List<Member>,
        ownSerials: List<String>,
        what: String,
        snap: AllocatorSnapshot,
    ): HookReply.Deny? {
        kinds.firstNotNullOfOrNull(::globalDeny)?.let { return it }
        if (kinds.any { it is CommandKind.AdbDisconnect && it.address !in ownSerials }) return HookReply.Deny(HookDecider.AFFECTS_OTHERS)
        kinds.firstNotNullOfOrNull { it as? CommandKind.IosGlobalDangerous }?.let { d ->
            if (othersHoldIos(snap, holder)) return HookReply.Deny("`${d.what}` affects other sessions' iOS devices. Ask the user to do it.")
        }

        val androids = allowed.filter { it.platform == Platform.ANDROID }
        val androidSerials = androids.flatMap { it.serials }
        val devices = kinds.filterIsInstance<CommandKind.Device>()
        val assigned = CommandClassifier.serialAssignments(sub)
        if (devices.isNotEmpty() && androids.isEmpty()) return HookReply.Deny(wrongPlatform(what, "an Android"))
        if ((devices.mapNotNull { it.explicitSerial } + assigned).any { it !in androidSerials }) return HookReply.Deny(different(what))
        fun soleMember(pred: (String) -> Boolean): Boolean {
            val matching = snap.devices.filter { it.platform == Platform.ANDROID && it.connectedSerials.any(pred) }
            return matching.size == 1 && androids.any { it.device?.key == matching.single().key }
        }
        if (devices.any { it.usbFlag } && !soleMember(::isUsbSerial)) return HookReply.Deny(different(what))
        if (devices.any { it.emulatorFlag } && !soleMember(::isEmulatorSerial)) return HookReply.Deny(different(what))

        val ios = allowed.filter { it.platform == Platform.IOS }
        fun isOwn(u: String, pair: Boolean? = null) = ios.any { m ->
            val keys = when (pair) {
                null -> m.serials
                true -> listOfNotNull(m.pairUdid)
                false -> listOf(m.udid)
            }
            keys.any { it.equals(u, ignoreCase = true) }
        }
        for (a in CommandClassifier.iosUdidAssignments(sub)) {
            val v = a.value ?: return HookReply.Deny(HookDecider.DYNAMIC_UDID)
            if (!isOwn(v, a.pair)) return HookReply.Deny(different(what))
        }
        val iosKinds = kinds.filterIsInstance<CommandKind.IosDevice>()
        if (iosKinds.isNotEmpty() && ios.isEmpty()) return HookReply.Deny(wrongPlatform(what, "an iOS"))
        if (iosKinds.any { k -> k.udids.any { !isOwn(it) } }) return HookReply.Deny(different(what))
        if (iosKinds.any { !it.pinnable }) return HookReply.Deny(HookDecider.PASS_IOS_DEVICE)
        return null
    }

    companion object {
        const val ALL = "all"
        const val SENTINEL = "use-cdm-run"
        const val USAGE = "Usage: `cdm run <name> -- <command>`, or `cdm run all -- <command>` for every device you hold."
        const val NO_DEVICES = "You hold no devices. Run `cdm acquire` first."
        const val SET_TAKEN = "Your devices timed out and one was taken; run `cdm acquire` again."
        const val OWN_RUN = "Put each device command in its own `cdm run <name> -- …`."
        const val ALL_KMP = "Inside `cdm run all`, KMP iOS tests can't pick a device. Use `cdm run ios<N> -- …`."
        const val ALL_NAME_IOS =
            "Inside `cdm run all`, name the iOS device: use \$CDM_IOS<N>_UDID (for example `-destination id=\$CDM_IOS1_UDID`), " +
                "or run it with `cdm run ios<N> -- …`."
        const val ALL_MAESTRO = "Name a member: `maestro --device \$CDM_ANDROID<N>_SERIAL` (or `\$CDM_IOS<N>_UDID`)."
        const val GROUPED = "Don't wrap `cdm run` in ( ) or { }; run it as its own command."
        const val WRAPPED = "Run `cdm run` directly, not through sudo/env or a VAR= prefix."
        const val MEMBER_VARIABLE =
            "Don't set the CDM_ANDROID<N>/CDM_IOS<N> variables; Device Manager exports them for your devices."
        const val COMMENT = "Put a # comment at the end of the line, after every command, not inside an earlier `cdm run`."

        fun waitingForSet(position: Int) =
            "You're waiting for a device set (#$position in line). Run `cdm acquire` again to keep waiting, " +
                "or `cdm release` to leave the queue."

        /** Any mention of a per-member variable name. */
        private val memberName = Regex("""(?<![A-Za-z0-9_])CDM_(?:ANDROID|IOS)\d+_(?:PAIR_UDID|SERIAL|UDID)(?![A-Za-z0-9_])""")

        /**
         * True when a per-member variable name appears other than as a plain `$NAME` / `${NAME}` read: `NAME=`, `NAME+=`,
         * `${NAME:=…}`, `${NAME:-…}`, `export NAME`, `read NAME`, `unset NAME`, … Fails closed (`echo NAME` counts too).
         * [CommandClassifier.pinVarWrites] adds names split by quotes or escapes (`CDM_ANDROID"1"_SERIAL=`).
         */
        private fun setsMemberVariable(command: String): Boolean = memberName.findAll(command).any { m ->
            val before = command.substring(0, m.range.first)
            val after = command.substring(m.range.last + 1)
            val plain = before.endsWith("$") && !before.endsWith("\\$")
            val braced = before.endsWith("\${") && after.startsWith("}")
            !(plain || braced)
        } || CommandClassifier.pinVarWrites(command).any { memberName.matches(it.name) }

        /** True when [text] has a `#` comment: an unquoted, unescaped `#` that starts a word. */
        private fun hasComment(text: String): Boolean {
            var q = 0.toChar()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                when {
                    q != 0.toChar() -> if (ch == '\\' && q == '"') i++ else if (ch == q) q = 0.toChar()
                    ch == '\\' -> i++
                    ch == '\'' || ch == '"' -> q = ch
                    ch == '#' && (i == 0 || text[i - 1].isWhitespace()) -> return true
                }
                i++
            }
            return false
        }

        const val NESTED_CDM = "Run `cdm` commands on their own, not inside `cdm run`."

        private val GRADLE = setOf("gradle", "gradlew")
        private val singleName = mapOf("android1" to Platform.ANDROID, "ios1" to Platform.IOS)

        /** `$CDM_ANDROID2_SERIAL` / `${CDM_IOS1_UDID}` / `$CDM_IOS1_PAIR_UDID` references. */
        private val memberRef = Regex(
            """\$(?:\{(CDM_(?:ANDROID|IOS)\d+_(?:SERIAL|UDID|PAIR_UDID))}|(CDM_(?:ANDROID|IOS)\d+_(?:SERIAL|UDID|PAIR_UDID))(?![A-Za-z0-9_]))""",
        )

        /**
         * [text] with each per-member variable reference the shell would expand replaced by its literal value. The checks
         * and the rewritten command both use it, so rebinding a variable at run time (however its name is spelled)
         * can't change the device that was checked. References in single quotes or after a backslash stay as written.
         */
        private fun substitute(text: String, vars: Map<String, String>): String {
            if (vars.isEmpty()) return text
            val expands = expandable(text)
            return memberRef.replace(text) { m ->
                val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
                if (expands[m.range.first]) vars[name] ?: m.value else m.value
            }
        }

        /** For each index of [text], true when a `$` there would be expanded: not in single quotes, not escaped. */
        private fun expandable(text: String): BooleanArray {
            val ok = BooleanArray(text.length)
            var q = 0.toChar()
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                when {
                    q == '\'' -> if (ch == '\'') q = 0.toChar()
                    ch == '\\' -> i++
                    q == '"' -> if (ch == '"') q = 0.toChar() else ok[i] = true
                    ch == '\'' || ch == '"' -> q = ch
                    else -> ok[i] = true
                }
                i++
            }
            return ok
        }

        /** True when [rest] closes a `(` or `{` it didn't open (the `cdm run` sits inside a group). Quote-aware. */
        private fun unbalancedClose(rest: String): Boolean {
            var depth = 0
            var q = 0.toChar()
            var i = 0
            while (i < rest.length) {
                val ch = rest[i]
                when {
                    q != 0.toChar() -> if (ch == '\\' && q == '"') i++ else if (ch == q) q = 0.toChar()
                    ch == '\\' -> i++
                    ch == '\'' || ch == '"' -> q = ch
                    ch == '(' || ch == '{' -> depth++
                    ch == ')' || ch == '}' -> if (--depth < 0) return true
                }
                i++
            }
            return false
        }

        private fun unknownName(name: String, held: Held) =
            "You hold no device named $name. Your devices: ${held.names}. Use `cdm run <name> -- <command>`."

        private fun noMember(platform: String, held: Held) =
            "You hold no $platform device (your devices: ${held.names}). Run `cdm release`, then `cdm acquire` " +
                "the devices you need."

        private fun several(platform: String, members: List<Member>) =
            "You hold several $platform devices (${members.joinToString(", ") { it.name }}), so this command can't " +
                "pick one. Run it with `cdm run <name> -- <command>` (for example `cdm run ${members.first().name} -- …`), " +
                "or `cdm run all -- <command>` for all of them."

        private fun different(what: String) =
            "This command targets a device other than $what. Drop the -s/-d/-e flag, ANDROID_SERIAL or UDID so it runs " +
                "there, or use `cdm run <name> -- …` with the device's name."

        private fun wrongPlatform(what: String, kind: String) =
            "This is $kind command, but it would run on $what. Use `cdm run <name> -- …` with $kind device's name."

        private fun offline(m: Member) = "You hold ${m.label} (${m.name}), but it is offline. It stays yours; reconnect it and try again."
    }
}
