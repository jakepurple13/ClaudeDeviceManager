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
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.devices.isWifiSerial

/**
 * Decides what to do with a holder's Bash command (spec "Bash command classification", rules 1–6).
 *
 * Not thread-safe (Ruling C): only [DeviceManagerService] calls it, while holding its Mutex.
 * [CommandClassifier.classify] picks the rule; every serial / -d / -e / disconnect check runs over
 * [CommandClassifier.classifyAll] so no segment escapes (Ruling G).
 */
class HookDecider(
    private val allocator: Allocator,
    private val timers: () -> Timers = { Timers() },
    private val defaultCategory: suspend (cwd: String) -> Category,
) {
    suspend fun onBash(holder: HolderId, cwd: String, command: String): HookReply {
        val kinds = CommandClassifier.classifyAll(command)
        if (kinds.isEmpty()) return HookReply.NoOpinion
        val req = Request(holder, cwd, command, kinds, CommandClassifier.serialAssignments(command))

        // Rule 1, cdm only: identity, never a pin (so the mirror's cdm-only rule can apply).
        if (kinds.all { it is CommandKind.Cdm }) return HookReply.Rewrite(req.body, command, RewriteKind.CDM)

        // Rule 3, checked over every segment.
        val held = heldDevice(holder, allocator.snapshot())
        val own = held?.connectedSerials.orEmpty()
        if (kinds.any { it is CommandKind.AdbGlobalDangerous }) return HookReply.Deny(AFFECTS_OTHERS)
        if (kinds.any { it is CommandKind.AdbDisconnect && it.address !in own }) return HookReply.Deny(AFFECTS_OTHERS)
        if (kinds.any { it == CommandKind.AdbTransportId }) return HookReply.Deny(TRANSPORT_ID)

        // Ruling V: an acquire chained with other work would run that work before (or without) the grant.
        if (held == null && kinds.any { it == CommandKind.Cdm("acquire") }) return HookReply.Deny(ACQUIRE_ALONE)

        // ANDROID_SERIAL set anywhere in the command (any command, not only adb) beats the pin, so check it up front.
        if (held != null && req.assigned.any { it !in own }) return HookReply.Deny(differentDevice(held))

        // Holding nothing, an ANDROID_SERIAL=X on any command names a device just like `adb -s X`.
        if (held == null && req.assigned.isNotEmpty()) return onDevice(req)

        return when (CommandClassifier.classify(command)) {
            is CommandKind.Device -> onDevice(req)
            CommandKind.GradleNonDevice -> {
                allocator.recordDeviceActivity(holder, Platform.ANDROID)
                req.finish(allocator.serialFor(holder, Platform.ANDROID))
            }
            else -> {
                if (kinds.any { it is CommandKind.AdbGlobalSafe && it.resetsTimer }) allocator.recordDeviceActivity(holder, Platform.ANDROID)
                req.finish(allocator.serialFor(holder, Platform.ANDROID))
            }
        }
    }

    /** Records the edit (switches the holder to the editing timer). Always [HookReply.NoOpinion]. */
    fun onEdit(holder: HolderId): HookReply {
        allocator.recordEdit(holder)
        return HookReply.NoOpinion
    }

    private inner class Request(
        val holder: HolderId,
        val cwd: String,
        val command: String,
        val kinds: List<CommandKind>,
        /** Every ANDROID_SERIAL=<value> in the raw command text. */
        val assigned: List<String>,
    ) {
        val body: String =
            if (kinds.any { it is CommandKind.Cdm }) CommandClassifier.addCdmIdentity(command, holder, cwd) else command
        val devices: List<CommandKind.Device> = kinds.filterIsInstance<CommandKind.Device>()

        /** Every serial the command names: `adb -s`, and ANDROID_SERIAL assignments anywhere. */
        val explicit: List<String> = (devices.mapNotNull { it.explicitSerial } + assigned).distinct()
        val usb = devices.any { it.usbFlag }
        val emu = devices.any { it.emulatorFlag }

        /** True if any segment would run on a device other than [target] (-s / ANDROID_SERIAL / -d / -e). */
        fun conflictsWith(target: AllocDevice, snap: AllocatorSnapshot): Boolean =
            explicit.any { it !in target.connectedSerials } ||
                (usb && !onlyOneWith(target, snap, ::isUsbSerial)) ||
                (emu && !onlyOneWith(target, snap, ::isEmulatorSerial))

        fun finish(serial: String?): HookReply = when {
            serial != null -> HookReply.Rewrite(pinPrefix(serial) + body, command, RewriteKind.PIN)
            body != command -> HookReply.Rewrite(body, command, RewriteKind.CDM)
            else -> HookReply.NoOpinion
        }
    }

    // ---- rule 4: device commands ----

    private suspend fun onDevice(req: Request): HookReply {
        return when (val st = allocator.statusOf(req.holder)) {
            is HolderStatus.Holding -> whileHolding(req)
            HolderStatus.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
            is HolderStatus.Released -> when (st.reason) {
                ReleaseReason.IDLE_TIMEOUT, ReleaseReason.EDIT_TIMEOUT -> regrab(req, st)
                ReleaseReason.USER_RELEASE, ReleaseReason.USER_HANDOFF, ReleaseReason.DONT_USE ->
                    HookReply.Deny(releasedByUser(blocked = false))
                else -> freshGrab(req)
            }
            is HolderStatus.Queued, HolderStatus.None -> freshGrab(req)
        }
    }

    private fun whileHolding(req: Request): HookReply {
        val snap = allocator.snapshot()
        val held = heldDevice(req.holder, snap) ?: return HookReply.Deny(releasedByUser(blocked = false))
        val serials = held.connectedSerials
        if (serials.isEmpty()) {
            return HookReply.Deny(
                "You hold ${label(held)}, but it is offline. It stays yours; reconnect it " +
                    "(for example `adb connect <address>`) and try again.",
            )
        }
        if (req.conflictsWith(held, snap)) return HookReply.Deny(differentDevice(held))
        allocator.recordDeviceActivity(req.holder, Platform.ANDROID)
        return req.finish(serials.first())
    }

    private suspend fun regrab(req: Request, st: HolderStatus.Released): HookReply {
        return when (val g = allocator.tryRegrab(req.holder, Platform.ANDROID)) {
            is GrabResult.Granted -> whileHolding(req)
            GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
            else -> {
                val last = allocator.snapshot().devices.firstOrNull { it.key == st.lastDevice }
                val name = last?.let(::label) ?: st.lastDevice.hardwareSerial
                val category = last?.category ?: defaultCategory(req.cwd)
                val minutes = if (st.reason == ReleaseReason.EDIT_TIMEOUT) {
                    "${timers().editing.inWholeMinutes} min idle (while editing)"
                } else "${timers().idle.inWholeMinutes} min idle"
                val now = when (g) {
                    GrabResult.DontUse -> "is now marked don't use"
                    GrabResult.NoneFree -> "is no longer available"
                    else -> "is now in use"
                }
                HookReply.Deny(
                    "Your $name was released after $minutes and $now. " +
                        "Run `cdm acquire --category ${category.id}` to wait for a device.",
                )
            }
        }
    }

    private suspend fun freshGrab(req: Request): HookReply {
        val snap = allocator.snapshot()
        val explicit = req.explicit
        val usb = req.usb
        val emu = req.emu

        val target: AllocDevice
        val pin: String
        when {
            explicit.isNotEmpty() -> {
                val targets = explicit.map { s ->
                    snap.devices.firstOrNull { s in it.connectedSerials }
                        ?: return HookReply.Deny(
                            "No connected device has serial $s. Check `adb devices`, or run " +
                                "`cdm acquire --category ${defaultCategory(req.cwd).id}` to get a device.",
                        )
                }.distinct()
                if (targets.size > 1) return HookReply.Deny(MORE_THAN_ONE)
                target = targets.single()
                pin = explicit.first()
            }
            usb && emu -> return HookReply.Deny(MORE_THAN_ONE)
            usb || emu -> {
                val pred: (String) -> Boolean = if (usb) ::isUsbSerial else ::isEmulatorSerial
                val what = if (usb) "USB-connected" else "emulator"
                val flag = if (usb) "-d" else "-e"
                // Same sole-device rule as whileHolding: adb only resolves -d / -e when exactly one device matches.
                val matching = snap.devices.filter { it.connectedSerials.any(pred) }
                val acquire = "`cdm acquire --category ${defaultCategory(req.cwd).id}`"
                when {
                    matching.isEmpty() -> return HookReply.Deny("No $what device is connected. Run $acquire to wait for a device.")
                    matching.size > 1 -> return HookReply.Deny(
                        "Multiple $what devices are connected (${matching.joinToString { label(it) }}), so `adb $flag` " +
                            "can't pick one. Use -s <serial> or run $acquire.",
                    )
                }
                target = matching.single()
                pin = target.connectedSerials.first(pred)
            }
            else -> {
                val category = defaultCategory(req.cwd)
                return when (val g = allocator.tryGrab(req.holder, Wants(category.members()))) {
                    is GrabResult.Granted -> req.finish(g.serial)
                    GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
                    else -> HookReply.Deny(noneFree(category))
                }
            }
        }

        // Every segment must land on the chosen device, not only the one that picked it.
        if (req.conflictsWith(target, snap)) return HookReply.Deny(MORE_THAN_ONE)

        return when (val g = allocator.tryGrab(req.holder, Wants(emptySet()), target.key)) {
            is GrabResult.Granted -> req.finish(pin)
            is GrabResult.HeldByOther -> HookReply.Deny(
                "${label(g.device)} is in use by ${g.holder.key}. " +
                    "Run `cdm acquire --category ${g.device.category.id}` to wait for a device.",
            )
            GrabResult.DontUse -> HookReply.Deny(
                "${label(target)} is marked don't use in Device Manager. Ask the user, or use another device.",
            )
            GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
            GrabResult.NoneFree -> HookReply.Deny("${label(target)} is offline.")
        }
    }

    // ---- helpers ----

    private fun heldDevice(holder: HolderId, snap: AllocatorSnapshot): AllocDevice? {
        val key = snap.holds.entries.firstOrNull { it.value == holder }?.key ?: return null
        return snap.devices.firstOrNull { it.key == key }
    }

    /** True if [held] has a serial matching [pred] and no other current device does (so -d / -e lands on it). */
    private fun onlyOneWith(held: AllocDevice, snap: AllocatorSnapshot, pred: (String) -> Boolean) =
        held.connectedSerials.any(pred) && snap.devices.none { it.key != held.key && it.connectedSerials.any(pred) }

    private fun differentDevice(held: AllocDevice) =
        "This command targets a different device, but you hold ${label(held)}. " +
            "Drop the -s/-d/-e flag or ANDROID_SERIAL so it runs on your device, or run `cdm release` first."

    private fun label(d: AllocDevice) = "${d.name} (${d.connectedSerials.firstOrNull() ?: d.key.hardwareSerial})"

    private fun noneFree(category: Category) =
        if (category == Category.ANY) {
            "No device is free. Run `cdm acquire --category any` to wait for one."
        } else {
            "No ${category.id.replace(",", " or ")} device is free. Run `cdm acquire --category ${category.id}` to wait for one."
        }

    private fun releasedByUser(blocked: Boolean) =
        if (blocked) {
            "Your device was released from Device Manager and this holder is blocked. " +
                "`cdm acquire` will refuse until the user unblocks it in Device Manager."
        } else {
            "Your device was released from Device Manager. Run `cdm acquire` to rejoin the queue."
        }

    companion object {
        const val AFFECTS_OTHERS = "This affects other sessions' devices. Ask the user to do it."
        const val TRANSPORT_ID =
            "`adb -t` picks a device by transport ID, which Device Manager can't check. Use -s <serial> or no flag."
        const val ACQUIRE_ALONE = "Run `cdm acquire …` on its own first, then your device commands."
        private const val MORE_THAN_ONE =
            "This command names more than one device. A holder can use only one device at a time."

        internal fun isEmulatorSerial(serial: String) = com.programmersbox.cdm.devices.isEmulatorSerial(serial)
        internal fun isUsbSerial(serial: String) = !isWifiSerial(serial) && !isEmulatorSerial(serial)
    }
}
