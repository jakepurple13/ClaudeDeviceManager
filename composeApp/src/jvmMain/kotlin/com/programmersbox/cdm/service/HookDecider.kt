package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.Allocator
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.GrabResult
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.ReleaseReason
import com.programmersbox.cdm.core.allocator.Timers
import com.programmersbox.cdm.core.classify.CommandClassifier
import com.programmersbox.cdm.core.classify.CommandKind
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.devices.isWifiSerial
import java.nio.file.Path

/**
 * Decides what to do with a holder's Bash command (spec "Bash command classification", rules 1–6, and "Pinning and the
 * hook (iOS)").
 *
 * Not thread-safe (Ruling C): only [DeviceManagerService] calls it, while holding its Mutex.
 * Every check runs over [CommandClassifier.classifyAll] so no segment escapes (Ruling G). The Android part of a command
 * uses only the holder's Android hold and the iOS part only its iOS hold; the pin exports every platform it holds.
 *
 * [iosPinScript] is the installed `ios-pin.gradle` (absolute), or null when it isn't installed.
 */
class HookDecider(
    private val allocator: Allocator,
    private val timers: () -> Timers = { Timers() },
    private val iosPinScript: () -> Path? = { null },
    private val defaultCategory: suspend (cwd: String) -> Category,
) {
    /**
     * Allocator events are held back for the whole decision, so a grab this decision undoes (a mixed command whose
     * iOS part is denied after its Android part grabbed) never shows as a grab and a release.
     */
    suspend fun onBash(holder: HolderId, cwd: String, command: String): HookReply {
        allocator.deferEvents()
        try {
            return decide(holder, cwd, command)
        } finally {
            allocator.flushEvents()
        }
    }

    private suspend fun decide(holder: HolderId, cwd: String, command: String): HookReply {
        var kinds = CommandClassifier.classifyAll(command)
        if (kinds.isEmpty()) return HookReply.NoOpinion
        // KMP Gradle iOS tests are pinned by the init script (it reads CDM_IOS_UDID), when it is installed.
        var text = command
        if (kinds.any { it is CommandKind.IosDevice && !it.pinnable }) {
            iosPinScript()?.let { path ->
                text = CommandClassifier.addIosInitScript(command, path.toAbsolutePath().toString())
                if (text != command) kinds = CommandClassifier.classifyAll(text)
            }
        }
        val req = Request(holder, cwd, command, text, kinds, CommandClassifier.serialAssignments(command))

        // Rule 1, cdm only: identity, never a pin (so the mirror's cdm-only rule can apply).
        if (kinds.all { it is CommandKind.Cdm }) return HookReply.Rewrite(req.body, command, RewriteKind.CDM)

        // Rule 3, checked over every segment.
        val snap = allocator.snapshot()
        val held = heldDevice(holder, Platform.ANDROID, snap)
        val own = held?.connectedSerials.orEmpty()
        if (kinds.any { it is CommandKind.AdbGlobalDangerous }) return HookReply.Deny(AFFECTS_OTHERS)
        if (kinds.any { it is CommandKind.AdbDisconnect && it.address !in own }) return HookReply.Deny(AFFECTS_OTHERS)
        if (kinds.any { it == CommandKind.AdbTransportId }) return HookReply.Deny(TRANSPORT_ID)

        // iOS rules 1–4: checks only, nothing is grabbed yet.
        val ios = when (val c = iosChecks(req, snap)) {
            is IosCheck.Stop -> return c.deny
            is IosCheck.Go -> c
        }

        // Maestro without --device from a holder with only an iOS hold: the export can't steer Maestro, and grabbing
        // an Android device would likely be the wrong platform. Ask for the device instead.
        val holdsAndroid = allocator.holdingsOf(holder).any { it.platform == Platform.ANDROID }
        val holdsIos = allocator.holdingsOf(holder).any { it.platform == Platform.IOS }
        if (holdsIos && !holdsAndroid && req.devices.any { it.bareMaestro && it.explicitSerial == null }) {
            return HookReply.Deny(MAESTRO_WHICH_DEVICE)
        }

        // Ruling V: an acquire chained with other work would run that work before (or without) the grant.
        if (kinds.any { it == CommandKind.Cdm("acquire") }) {
            val holding = allocator.holdingsOf(holder).map { it.platform }.toSet()
            val touched = CommandClassifier.platformsOf(text) +
                (if (req.assigned.isNotEmpty()) setOf(Platform.ANDROID) else emptySet()) +
                (if (req.iosAssigned.isNotEmpty()) setOf(Platform.IOS) else emptySet())
            if (holding.isEmpty() || !holding.containsAll(touched)) return HookReply.Deny(ACQUIRE_ALONE)
        }

        // ANDROID_SERIAL set anywhere in the command (any command, not only adb) beats the pin, so check it up front.
        if (held != null && req.assigned.any { it !in own }) return HookReply.Deny(differentDevice(held))

        val hadAndroid = holdsAndroid
        val androidSerial = when (
            val part = when {
                // Holding nothing, an ANDROID_SERIAL=X on any command names a device just like `adb -s X`.
                held == null && req.assigned.isNotEmpty() -> onDevice(req)
                req.devices.isNotEmpty() -> onDevice(req)
                kinds.any { it == CommandKind.GradleNonDevice } -> {
                    allocator.recordDeviceActivity(holder, Platform.ANDROID)
                    Part.Pin(allocator.serialFor(holder, Platform.ANDROID))
                }
                else -> {
                    if (kinds.any { it is CommandKind.AdbGlobalSafe && it.resetsTimer }) {
                        allocator.recordDeviceActivity(holder, Platform.ANDROID)
                    }
                    Part.Pin(allocator.serialFor(holder, Platform.ANDROID))
                }
            }
        ) {
            is Part.Stop -> return part.deny
            is Part.Pin -> part.serial
        }

        // iOS rule 5: grab on first use.
        if (ios.needsDevice) {
            iosGrab(req, ios.named)?.let { deny ->
                // The command won't run: give back an Android device grabbed for it in this decision (never an older hold).
                if (!hadAndroid) allocator.undoGrab(holder, Platform.ANDROID)
                return deny
            }
        }

        // iOS rule 6: rewrite `booted` to the held UDID, then export every platform the holder holds.
        val iosHold = allocator.holdingsOf(holder).firstOrNull { it.platform == Platform.IOS }
        val udid = iosHold?.device?.hardwareSerial
        var body = req.body
        if (udid != null && req.ios.any { it.second.usesBooted }) body = CommandClassifier.rewriteBooted(body, udid)
        val prefix = pinPrefix(androidSerial, udid, iosHold?.partner?.hardwareSerial)
        return when {
            prefix.isNotEmpty() -> HookReply.Rewrite(prefix + body, command, RewriteKind.PIN)
            body != command -> HookReply.Rewrite(body, command, RewriteKind.CDM)
            else -> HookReply.NoOpinion
        }
    }

    /** Records the edit (switches the holder to the editing timer). Always [HookReply.NoOpinion]. */
    fun onEdit(holder: HolderId): HookReply {
        allocator.recordEdit(holder)
        return HookReply.NoOpinion
    }

    /** The Android part's outcome: the serial to pin (null: no Android pin) or a denial. */
    private sealed interface Part {
        data class Pin(val serial: String?) : Part
        data class Stop(val deny: HookReply.Deny) : Part
    }

    private sealed interface IosCheck {
        data class Stop(val deny: HookReply.Deny) : IosCheck

        /** [needsDevice]: some segment reaches an iOS device. [named]: the free tracked device the command names. */
        data class Go(val needsDevice: Boolean, val named: AllocDevice?) : IosCheck
    }

    private inner class Request(
        val holder: HolderId,
        val cwd: String,
        /** The command as the agent wrote it. */
        val command: String,
        /** [command] with the iOS init script added where it applies; [body] and [ios] come from it. */
        text: String,
        val kinds: List<CommandKind>,
        /** Every ANDROID_SERIAL=<value> in the raw command text. */
        val assigned: List<String>,
    ) {
        /** Every CDM_IOS_UDID= / CDM_IOS_PAIR_UDID= in the raw command text. */
        val iosAssigned = CommandClassifier.iosUdidAssignments(command)

        val body: String =
            if (kinds.any { it is CommandKind.Cdm }) CommandClassifier.addCdmIdentity(text, holder, cwd) else text
        val devices: List<CommandKind.Device> = kinds.filterIsInstance<CommandKind.Device>()

        /** Each iOS device kind with the segment it came from (a mixed Gradle segment also yields an Android kind). */
        val ios: List<Pair<String, CommandKind.IosDevice>> = CommandClassifier.segments(text).flatMap { seg ->
            CommandClassifier.classifyAll(seg).filterIsInstance<CommandKind.IosDevice>().map { seg to it }
        }

        /** Every serial the command names: `adb -s`, and ANDROID_SERIAL assignments anywhere. */
        val explicit: List<String> = (devices.mapNotNull { it.explicitSerial } + assigned).distinct()
        val usb = devices.any { it.usbFlag }
        val emu = devices.any { it.emulatorFlag }

        /** True if any segment would run on a device other than [target] (-s / ANDROID_SERIAL / -d / -e). */
        fun conflictsWith(target: AllocDevice, snap: AllocatorSnapshot): Boolean =
            explicit.any { it !in target.connectedSerials } ||
                (usb && !onlyOneWith(target, snap, ::isUsbSerial)) ||
                (emu && !onlyOneWith(target, snap, ::isEmulatorSerial))

        fun pin(serial: String?) = Part.Pin(serial)
    }

    // ---- iOS ----

    /** iOS rules 1–4 (spec "Pinning and the hook (iOS)"), in order. Grabs nothing. */
    private fun iosChecks(req: Request, snap: AllocatorSnapshot): IosCheck {
        val holder = req.holder
        // 1. A global iOS command while another session holds an iOS device.
        req.kinds.firstNotNullOfOrNull { it as? CommandKind.IosGlobalDangerous }?.let { d ->
            val othersHoldIos = snap.holdings.any { (other, hs) -> other != holder && hs.any { it.platform == Platform.IOS } }
            if (othersHoldIos) {
                return IosCheck.Stop(HookReply.Deny("`${d.what}` affects other sessions' iOS devices. Ask the user to do it."))
            }
        }
        val iosHold = allocator.holdingsOf(holder).firstOrNull { it.platform == Platform.IOS }
        val ownKeys = listOfNotNull(iosHold?.device, iosHold?.partner)
        val named = LinkedHashSet<AllocDevice>()
        var needsDevice = false

        /** Rules 2 and 3 for one named UDID: null if it is the holder's own or free (then added to [named]). */
        fun checkUdid(u: String, bootAllowed: Boolean): IosCheck.Stop? {
            val d = snap.devices.firstOrNull { it.platform == Platform.IOS && it.key.hardwareSerial.equals(u, ignoreCase = true) }
            // The holder's own device stays its own while offline, even if it has dropped off the device list.
            val key = d?.key ?: ownKeys.firstOrNull { it.hardwareSerial.equals(u, ignoreCase = true) }
            // 3. A UDID CDM doesn't track.
            if (key == null) return if (bootAllowed) null else IosCheck.Stop(HookReply.Deny(untrackedUdid(u)))
            if (key in ownKeys) return null
            // 2. A tracked device that isn't the holder's.
            if (iosHold != null) return IosCheck.Stop(HookReply.Deny(differentIosDevice(iosHold.device, snap)))
            val other = snap.holds[key]
            if (other != null && d != null) {
                return IosCheck.Stop(
                    HookReply.Deny(
                        "${label(d)} is in use by ${other.key}. " +
                            "Run `cdm acquire --category ${d.category.id} --ios` to wait for a device, " +
                            "or `cdm acquire --device ${d.key.hardwareSerial}` to wait for this one.",
                    ),
                )
            }
            d?.let(named::add)
            return null
        }

        // CDM_IOS_UDID / CDM_IOS_PAIR_UDID set anywhere in the command beats the exported pin, like ANDROID_SERIAL.
        for (a in req.iosAssigned) {
            val v = a.value ?: return IosCheck.Stop(HookReply.Deny(DYNAMIC_UDID))
            if (iosHold != null) {
                val mine = if (a.pair) iosHold.partner?.hardwareSerial else iosHold.device.hardwareSerial
                if (!v.equals(mine, ignoreCase = true)) return IosCheck.Stop(HookReply.Deny(differentIosDevice(iosHold.device, snap)))
            } else {
                // Holding nothing on iOS, the assignment names a device just like `simctl … <udid>`.
                checkUdid(v, bootAllowed = false)?.let { return it }
                needsDevice = true
            }
        }
        for ((seg, k) in req.ios) {
            // `simctl boot <untracked>` reaches no device CDM hands out: it grabs nothing (the sim then shows up free).
            var untrackedBootOnly = k.udids.isNotEmpty()
            val boot = CommandClassifier.isSimctlBoot(seg)
            for (u in k.udids) {
                checkUdid(u, bootAllowed = boot)?.let { return it }
                val tracked = snap.devices.any { it.platform == Platform.IOS && it.key.hardwareSerial.equals(u, ignoreCase = true) } ||
                    ownKeys.any { it.hardwareSerial.equals(u, ignoreCase = true) }
                if (tracked) untrackedBootOnly = false
            }
            // 4. A device command that can't be pinned.
            if (!k.pinnable) return IosCheck.Stop(HookReply.Deny(PASS_IOS_DEVICE))
            if (!untrackedBootOnly) needsDevice = true
        }
        // A pair is one hold: naming both of its sides names one device.
        val distinct = named.distinctBy { setOfNotNull(it.key, it.partner) }
        if (distinct.size > 1) return IosCheck.Stop(HookReply.Deny(MORE_THAN_ONE))
        return IosCheck.Go(needsDevice, distinct.firstOrNull())
    }

    /** iOS rule 5: null when the holder holds (or has just been granted) an iOS device, else the denial. */
    private suspend fun iosGrab(req: Request, named: AllocDevice?): HookReply.Deny? {
        val holder = req.holder
        when (val st = allocator.statusOf(holder, Platform.IOS)) {
            is HolderStatus.Holding, is HolderStatus.HoldingSet -> return null
            HolderStatus.Blocked -> return HookReply.Deny(releasedByUser(blocked = true))
            is HolderStatus.Released -> when (st.reason) {
                ReleaseReason.USER_RELEASE, ReleaseReason.USER_HANDOFF, ReleaseReason.DONT_USE ->
                    return HookReply.Deny(releasedByUser(blocked = false, ios = true))
                // Like Android: after a timeout, only the same device comes back; otherwise say what happened.
                ReleaseReason.IDLE_TIMEOUT, ReleaseReason.EDIT_TIMEOUT -> return when (val g = allocator.tryRegrab(holder, Platform.IOS)) {
                    is GrabResult.Granted ->
                        if (named == null || named.key == g.device.key || named.key == g.device.partner) null
                        else HookReply.Deny(differentIosDevice(g.device.key, allocator.snapshot()))
                    GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
                    else -> HookReply.Deny(releasedAfterTimeout(st, g, req.cwd, ios = true))
                }
                else -> {}
            }
            else -> {}
        }
        // A queued holder's first-use grab asks for what it is queued for, not the project default.
        val queued = queuedFor(holder, Platform.IOS)
        if (named == null && queued != null) {
            return when (allocator.tryGrab(holder, queued.second.copy(platform = Platform.IOS))) {
                is GrabResult.Granted -> null
                GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
                else -> HookReply.Deny(stillQueued(queued.first, queued.second))
            }
        }
        val category = named?.category ?: defaultCategory(req.cwd)
        val g = if (named != null) {
            allocator.tryGrab(holder, Wants(emptySet()), named.key)
        } else {
            allocator.tryGrab(holder, Wants(category.members(), Platform.IOS))
        }
        val acquire = "`cdm acquire --category ${category.id} --ios`"
        return when (g) {
            is GrabResult.Granted -> null
            GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
            GrabResult.DontUse -> HookReply.Deny(
                "${named?.let(::label) ?: "That device"} is marked don't use in Device Manager. Ask the user, or use another device.",
            )
            is GrabResult.HeldByOther -> HookReply.Deny(
                "${label(g.device)} is in use by ${g.holder.key}. Run $acquire to wait for a device, " +
                    "or `cdm acquire --device ${g.device.key.hardwareSerial}` to wait for this one.",
            )
            GrabResult.NoneFree -> HookReply.Deny(
                if (named != null) "${label(named)} is offline. Boot it, or run $acquire to wait for a device."
                else noneFree(category, ios = true),
            )
        }
    }

    private fun differentIosDevice(heldKey: DeviceKey, snap: AllocatorSnapshot): String {
        val name = snap.devices.firstOrNull { it.key == heldKey }?.let(::label) ?: heldKey.hardwareSerial
        return "This command targets a different device, but you hold $name. " +
            "Use \$CDM_IOS_UDID (or `booted`) so it runs on your device, or run `cdm release --ios` first."
    }

    private fun untrackedUdid(udid: String) =
        "No device or booted simulator that Device Manager tracks has UDID $udid. " +
            "Boot it with `xcrun simctl boot $udid` and run `cdm acquire --ios`, or use \$CDM_IOS_UDID."

    // ---- rule 4: Android device commands ----

    private suspend fun onDevice(req: Request): Part {
        return when (val st = allocator.statusOf(req.holder, Platform.ANDROID)) {
            is HolderStatus.Holding, is HolderStatus.HoldingSet -> whileHolding(req) // set handling refined in a later task
            HolderStatus.Blocked -> Part.Stop(HookReply.Deny(releasedByUser(blocked = true)))
            is HolderStatus.Released -> when (st.reason) {
                ReleaseReason.IDLE_TIMEOUT, ReleaseReason.EDIT_TIMEOUT -> regrab(req, st)
                ReleaseReason.USER_RELEASE, ReleaseReason.USER_HANDOFF, ReleaseReason.DONT_USE ->
                    Part.Stop(HookReply.Deny(releasedByUser(blocked = false, ios = false)))
                else -> freshGrab(req)
            }
            is HolderStatus.Queued, HolderStatus.None -> freshGrab(req)
        }
    }

    private fun whileHolding(req: Request): Part {
        val snap = allocator.snapshot()
        val held = heldDevice(req.holder, Platform.ANDROID, snap)
            ?: return Part.Stop(HookReply.Deny(releasedByUser(blocked = false, ios = false)))
        val serials = held.connectedSerials
        if (serials.isEmpty()) {
            return Part.Stop(
                HookReply.Deny(
                    "You hold ${label(held)}, but it is offline. It stays yours; reconnect it " +
                        "(for example `adb connect <address>`) and try again.",
                ),
            )
        }
        if (req.conflictsWith(held, snap)) return Part.Stop(HookReply.Deny(differentDevice(held)))
        allocator.recordDeviceActivity(req.holder, Platform.ANDROID)
        return req.pin(serials.first())
    }

    private suspend fun regrab(req: Request, st: HolderStatus.Released): Part {
        return when (val g = allocator.tryRegrab(req.holder, Platform.ANDROID)) {
            is GrabResult.Granted -> whileHolding(req)
            GrabResult.Blocked -> Part.Stop(HookReply.Deny(releasedByUser(blocked = true)))
            else -> Part.Stop(HookReply.Deny(releasedAfterTimeout(st, g, req.cwd, ios = false)))
        }
    }

    /** Why a timed-out hold couldn't be taken back ([g] is the failed regrab). */
    private suspend fun releasedAfterTimeout(st: HolderStatus.Released, g: GrabResult, cwd: String, ios: Boolean): String {
        val last = allocator.snapshot().devices.firstOrNull { it.key == st.lastDevice }
        val name = last?.let(::label) ?: st.lastDevice.hardwareSerial
        val category = last?.category ?: defaultCategory(cwd)
        val minutes = if (st.reason == ReleaseReason.EDIT_TIMEOUT) {
            "${timers().editing.inWholeMinutes} min idle (while editing)"
        } else "${timers().idle.inWholeMinutes} min idle"
        val now = when (g) {
            GrabResult.DontUse -> "is now marked don't use"
            GrabResult.NoneFree -> "is no longer available"
            else -> "is now in use"
        }
        val flag = if (ios) " --ios" else " --android"
        return "Your $name was released after $minutes and $now. " +
            "Run `cdm acquire --category ${category.id}$flag` to wait for a device."
    }

    private suspend fun freshGrab(req: Request): Part {
        val snap = allocator.snapshot()
        val androids = snap.devices.filter { it.platform == Platform.ANDROID }
        val explicit = req.explicit
        val usb = req.usb
        val emu = req.emu
        fun deny(reason: String) = Part.Stop(HookReply.Deny(reason))

        val target: AllocDevice
        val pin: String
        when {
            explicit.isNotEmpty() -> {
                val targets = explicit.map { s ->
                    androids.firstOrNull { s in it.connectedSerials }
                        ?: return deny(
                            "No connected device has serial $s. Check `adb devices`, or run " +
                                "`cdm acquire --category ${defaultCategory(req.cwd).id} --android` to get a device.",
                        )
                }.distinct()
                if (targets.size > 1) return deny(MORE_THAN_ONE)
                target = targets.single()
                pin = explicit.first()
            }
            usb && emu -> return deny(MORE_THAN_ONE)
            usb || emu -> {
                val pred: (String) -> Boolean = if (usb) ::isUsbSerial else ::isEmulatorSerial
                val what = if (usb) "USB-connected" else "emulator"
                val flag = if (usb) "-d" else "-e"
                // Same sole-device rule as whileHolding: adb only resolves -d / -e when exactly one device matches.
                val matching = androids.filter { it.connectedSerials.any(pred) }
                val acquire = "`cdm acquire --category ${defaultCategory(req.cwd).id} --android`"
                when {
                    matching.isEmpty() -> return deny("No $what device is connected. Run $acquire to wait for a device.")
                    matching.size > 1 -> return deny(
                        "Multiple $what devices are connected (${matching.joinToString { label(it) }}), so `adb $flag` " +
                            "can't pick one. Use -s <serial> or run $acquire.",
                    )
                }
                target = matching.single()
                pin = target.connectedSerials.first(pred)
            }
            else -> {
                // A queued holder's first-use grab asks for what it is queued for, not the project default.
                queuedFor(req.holder, Platform.ANDROID)?.let { (position, wants) ->
                    return when (val g = allocator.tryGrab(req.holder, wants.copy(platform = Platform.ANDROID))) {
                        is GrabResult.Granted -> req.pin(g.serial)
                        GrabResult.Blocked -> deny(releasedByUser(blocked = true))
                        else -> deny(stillQueued(position, wants))
                    }
                }
                val category = defaultCategory(req.cwd)
                return when (val g = allocator.tryGrab(req.holder, Wants(category.members(), Platform.ANDROID))) {
                    is GrabResult.Granted -> req.pin(g.serial)
                    GrabResult.Blocked -> deny(releasedByUser(blocked = true))
                    else -> deny(noneFree(category, ios = false))
                }
            }
        }

        // Every segment must land on the chosen device, not only the one that picked it.
        if (req.conflictsWith(target, snap)) return deny(MORE_THAN_ONE)

        return when (val g = allocator.tryGrab(req.holder, Wants(emptySet()), target.key)) {
            is GrabResult.Granted -> req.pin(pin)
            is GrabResult.HeldByOther -> deny(
                "${label(g.device)} is in use by ${g.holder.key}. " +
                    "Run `cdm acquire --category ${g.device.category.id} --android` to wait for a device, " +
                    "or `cdm acquire --device $pin` to wait for this one.",
            )
            GrabResult.DontUse -> deny(
                "${label(target)} is marked don't use in Device Manager. Ask the user, or use another device.",
            )
            GrabResult.Blocked -> deny(releasedByUser(blocked = true))
            GrabResult.NoneFree -> deny("${label(target)} is offline.")
        }
    }

    // ---- helpers ----

    /** The primary device of the holder's [platform] hold (a pair's partner is [HolderStatus.Holding.partner]). */
    private fun heldDevice(holder: HolderId, platform: Platform, snap: AllocatorSnapshot): AllocDevice? {
        val key = allocator.holdingsOf(holder).firstOrNull { it.platform == platform }?.device ?: return null
        return snap.devices.firstOrNull { it.key == key }
    }

    /** True if [held] has a serial matching [pred] and no other current Android device does (so -d / -e lands on it). */
    private fun onlyOneWith(held: AllocDevice, snap: AllocatorSnapshot, pred: (String) -> Boolean) =
        held.connectedSerials.any(pred) &&
            snap.devices.none { it.platform == Platform.ANDROID && it.key != held.key && it.connectedSerials.any(pred) }

    private fun differentDevice(held: AllocDevice) =
        "This command targets a different device, but you hold ${label(held)}. " +
            "Drop the -s/-d/-e flag or ANDROID_SERIAL so it runs on your device, or run `cdm release --android` first."

    /** The holder's queue position and request, when its queue entry covers [platform] (that platform, or none named). */
    private fun queuedFor(holder: HolderId, platform: Platform): Pair<Int, Wants>? {
        val st = allocator.statusOf(holder) as? HolderStatus.Queued ?: return null
        return if (st.wants.platform == null || st.wants.platform == platform) st.position to st.wants else null
    }

    /** Denial for a queued holder that nothing free fits; worded with [Wants.limits], like the queue in the app. */
    private fun stillQueued(position: Int, wants: Wants): String {
        val cats = wants.categories.takeUnless { it.isEmpty() || Category.ANY in it }
        val what = cats?.joinToString(" or ") { it.id }?.let { "a $it device" } ?: "a device"
        val limits = wants.limits.takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty()
        val category = cats?.joinToString(",") { it.id } ?: Category.ANY.id
        // Repeat exactly the request the holder is queued with: a platform flag only if it named one.
        val flags = when (wants.platform) {
            Platform.IOS -> " --ios"
            Platform.ANDROID -> " --android"
            null -> ""
        } + when (wants.virtual) {
            true -> " --virtual"
            false -> " --real"
            null -> ""
        } + wants.device?.let { " --device ${it.hardwareSerial}" }.orEmpty()
        return "You're waiting for $what$limits, #$position in line. " +
            "Run `cdm acquire --category $category$flags` to keep waiting, " +
            "`cdm acquire --category <other>` to wait for something else, or `cdm release` to leave the queue."
    }

    private fun label(d: AllocDevice) = "${d.name} (${d.connectedSerials.firstOrNull() ?: d.key.hardwareSerial})"

    private fun noneFree(category: Category, ios: Boolean): String {
        val flag = if (ios) " --ios" else " --android"
        val kind = if (ios) "iOS device" else "device"
        return if (category == Category.ANY) {
            "No $kind is free. Run `cdm acquire --category any$flag` to wait for one."
        } else {
            "No ${category.id.replace(",", " or ")} $kind is free. " +
                "Run `cdm acquire --category ${category.id}$flag` to wait for one."
        }
    }

    /** [ios] picks the platform flag of the rejoin hint; the blocked text gives no command to run. */
    private fun releasedByUser(blocked: Boolean, ios: Boolean = false) =
        if (blocked) {
            "Your device was released from Device Manager and this holder is blocked. " +
                "`cdm acquire` will refuse until the user unblocks it in Device Manager."
        } else {
            "Your device was released from Device Manager. Run `cdm acquire ${if (ios) "--ios" else "--android"}` to rejoin the queue."
        }

    companion object {
        const val AFFECTS_OTHERS = "This affects other sessions' devices. Ask the user to do it."
        const val TRANSPORT_ID =
            "`adb -t` picks a device by transport ID, which Device Manager can't check. Use -s <serial> or no flag."
        const val ACQUIRE_ALONE = "Run `cdm acquire …` on its own first, then your device commands."
        const val DYNAMIC_UDID =
            "Don't set CDM_IOS_UDID or CDM_IOS_PAIR_UDID to a computed value; Device Manager exports them for the " +
                "device you hold. Use \$CDM_IOS_UDID as it is, or a literal UDID."
        const val MAESTRO_WHICH_DEVICE =
            "You hold an iOS device and no Android device, so pass Maestro the device: " +
                "`maestro --device \$CDM_IOS_UDID …` for your iOS device, or run `cdm acquire --android` first " +
                "for an Android one."
        const val PASS_IOS_DEVICE = "Pass the device explicitly: -destination id=\$CDM_IOS_UDID / --device \$CDM_IOS_UDID"
        private const val MORE_THAN_ONE =
            "This command names more than one device. A holder can use only one device at a time."

        internal fun isEmulatorSerial(serial: String) = com.programmersbox.cdm.devices.isEmulatorSerial(serial)
        internal fun isUsbSerial(serial: String) = !isWifiSerial(serial) && !isEmulatorSerial(serial)
    }
}
