package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.allocator.AllocDevice
import com.jacobrein.cdm.core.allocator.Allocator
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.GrabResult
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.allocator.ReleaseReason
import com.jacobrein.cdm.core.allocator.Timers
import com.jacobrein.cdm.core.classify.CommandClassifier
import com.jacobrein.cdm.core.classify.CommandKind
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.devices.isWifiSerial

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
        val req = Request(holder, cwd, command, kinds)

        // Rule 1, cdm only: identity, never a pin (so the mirror's cdm-only rule can apply).
        if (kinds.all { it is CommandKind.Cdm }) return HookReply.Rewrite(req.body, command, RewriteKind.CDM)

        // Rule 3, checked over every segment.
        val own = heldDevice(holder, allocator.snapshot())?.connectedSerials.orEmpty()
        if (kinds.any { it is CommandKind.AdbGlobalDangerous }) return HookReply.Deny(AFFECTS_OTHERS)
        if (kinds.any { it is CommandKind.AdbDisconnect && it.address !in own }) return HookReply.Deny(AFFECTS_OTHERS)

        return when (CommandClassifier.classify(command)) {
            is CommandKind.Device -> onDevice(req)
            CommandKind.GradleNonDevice -> {
                allocator.recordDeviceActivity(holder)
                req.finish(allocator.serialFor(holder))
            }
            else -> {
                if (kinds.any { it is CommandKind.AdbGlobalSafe && it.resetsTimer }) allocator.recordDeviceActivity(holder)
                req.finish(allocator.serialFor(holder))
            }
        }
    }

    /** Records the edit (switches the holder to the editing timer). Always [HookReply.NoOpinion]. */
    fun onEdit(holder: HolderId): HookReply {
        allocator.recordEdit(holder)
        return HookReply.NoOpinion
    }

    private inner class Request(val holder: HolderId, val cwd: String, val command: String, val kinds: List<CommandKind>) {
        val body: String =
            if (kinds.any { it is CommandKind.Cdm }) CommandClassifier.addCdmIdentity(command, holder, cwd) else command
        val devices: List<CommandKind.Device> = kinds.filterIsInstance<CommandKind.Device>()

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
        for (d in req.devices) {
            val wrong = (d.explicitSerial != null && d.explicitSerial !in serials) ||
                (d.usbFlag && !onlyOneWith(held, snap, ::isUsbSerial)) ||
                (d.emulatorFlag && !onlyOneWith(held, snap, ::isEmulatorSerial))
            if (wrong) {
                return HookReply.Deny(
                    "This command targets a different device, but you hold ${label(held)}. " +
                        "Drop the -s/-d/-e flag or ANDROID_SERIAL so it runs on your device, or run `cdm release` first.",
                )
            }
        }
        allocator.recordDeviceActivity(req.holder)
        return req.finish(serials.first())
    }

    private suspend fun regrab(req: Request, st: HolderStatus.Released): HookReply {
        return when (val g = allocator.tryRegrab(req.holder)) {
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
        val explicit = req.devices.mapNotNull { it.explicitSerial }.distinct()
        val usb = req.devices.any { it.usbFlag }
        val emu = req.devices.any { it.emulatorFlag }

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
                return when (val g = allocator.tryGrab(req.holder, setOf(category))) {
                    is GrabResult.Granted -> req.finish(g.serial)
                    GrabResult.Blocked -> HookReply.Deny(releasedByUser(blocked = true))
                    else -> HookReply.Deny(noneFree(category))
                }
            }
        }

        return when (val g = allocator.tryGrab(req.holder, emptySet(), target.key)) {
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

    private fun label(d: AllocDevice) = "${d.name} (${d.connectedSerials.firstOrNull() ?: d.key.hardwareSerial})"

    private fun noneFree(category: Category) =
        if (category == Category.ANY) {
            "No device is free. Run `cdm acquire --category any` to wait for one."
        } else {
            "No ${category.id} device is free. Run `cdm acquire --category ${category.id}` to wait for one."
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
        private const val MORE_THAN_ONE =
            "This command names more than one device. A holder can use only one device at a time."

        internal fun isEmulatorSerial(serial: String) = serial.startsWith("emulator-")
        internal fun isUsbSerial(serial: String) = !isWifiSerial(serial) && !isEmulatorSerial(serial)
    }
}
