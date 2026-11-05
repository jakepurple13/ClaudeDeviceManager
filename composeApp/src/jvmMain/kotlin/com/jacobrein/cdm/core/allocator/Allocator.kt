package com.jacobrein.cdm.core.allocator

import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import kotlin.time.Instant

/** Not thread-safe: the service wraps it in a Mutex. */
class Allocator(
    private val clock: () -> Instant,
    blocked: Set<HolderId> = emptySet(),
    private val onEvent: (AllocatorEvent) -> Unit = {},
) {
    private var devices: List<AllocDevice> = emptyList()
    private val known = LinkedHashMap<DeviceKey, AllocDevice>()
    private val holds = LinkedHashMap<DeviceKey, HolderId>()
    private val queue = ArrayList<HolderId>()
    private val queueWants = HashMap<HolderId, Set<Category>>()
    private val blockedHolders = LinkedHashSet(blocked)

    /** Holding and Released only; Queued and Blocked are derived. */
    private val statuses = HashMap<HolderId, HolderStatus>()

    fun snapshot(): AllocatorSnapshot {
        val all = LinkedHashMap<HolderId, HolderStatus>()
        statuses.keys.forEach { all[it] = statusOf(it) }
        queue.forEach { all[it] = statusOf(it) }
        blockedHolders.forEach { all[it] = HolderStatus.Blocked }
        return AllocatorSnapshot(devices.toList(), holds.toMap(), queue.toList(), all)
    }

    fun statusOf(holder: HolderId): HolderStatus {
        if (holder in blockedHolders) return HolderStatus.Blocked
        val i = queue.indexOf(holder)
        if (i >= 0) return HolderStatus.Queued(i + 1, queueWants[holder].orEmpty())
        return statuses[holder] ?: HolderStatus.None
    }

    fun setDevices(devices: List<AllocDevice>) {
        this.devices = devices.toList()
        devices.forEach { known[it.key] = it }
        devices.forEach { passToQueue(it.key) }
    }

    fun tryGrab(holder: HolderId, wants: Set<Category>, specific: DeviceKey? = null): GrabResult {
        if (holder in blockedHolders) return GrabResult.Blocked
        holds.entries.firstOrNull { it.value == holder }?.let { return granted(known.getValue(it.key), holder) }
        if (specific != null) {
            val d = devices.firstOrNull { it.key == specific } ?: return GrabResult.NoneFree
            if (d.dontUse) return GrabResult.DontUse
            holds[d.key]?.let { return GrabResult.HeldByOther(d, it) }
            return grant(holder, d)
        }
        val free = devices.firstOrNull { !it.dontUse && it.key !in holds && fits(wants, it) }
        return if (free != null) grant(holder, free) else GrabResult.NoneFree
    }

    /** Returns the 1-based queue position, or 0 if the holder is blocked or already holds a device. */
    fun enqueue(holder: HolderId, wants: Set<Category>): Int {
        if (holder in blockedHolders || holds.containsValue(holder)) return 0
        val existing = queue.indexOf(holder)
        if (existing >= 0) return existing + 1
        queue.add(holder)
        queueWants[holder] = wants
        statuses.remove(holder)
        emit(AllocatorEvent.Queued(clock(), holder, wants))
        return queue.size
    }

    fun release(holder: HolderId, reason: ReleaseReason) {
        val device = holds.entries.firstOrNull { it.value == holder }?.key ?: return
        free(device, holder, reason)
        passToQueue(device)
    }

    fun releaseDevice(device: DeviceKey, block: Boolean) {
        val holder = holds[device] ?: return
        if (block) removeFromQueueInternal(holder)
        free(device, holder, ReleaseReason.USER_RELEASE)
        if (block) {
            blockedHolders.add(holder)
            emit(AllocatorEvent.Blocked(clock(), holder))
        }
        passToQueue(device)
    }

    /** No-op if [to] is blocked or already holds another device, or the device is don't-use. */
    fun handOff(device: DeviceKey, to: HolderId) {
        val old = holds[device] ?: return
        if (old == to || to in blockedHolders || holds.containsValue(to)) return
        val d = known[device] ?: return
        if (d.dontUse) return
        free(device, old, ReleaseReason.USER_HANDOFF)
        grant(to, d)
    }

    fun setDontUse(device: DeviceKey, dontUse: Boolean, releaseNow: Boolean) {
        known[device]?.let { known[device] = it.copy(dontUse = dontUse) }
        devices = devices.map { if (it.key == device) it.copy(dontUse = dontUse) else it }
        if (dontUse) {
            val holder = holds[device]
            if (holder != null && releaseNow) free(device, holder, ReleaseReason.DONT_USE)
        } else {
            passToQueue(device)
        }
    }

    fun moveInQueue(holder: HolderId, newIndex: Int) {
        if (!queue.remove(holder)) return
        queue.add(newIndex.coerceIn(0, queue.size), holder)
    }

    fun removeFromQueue(holder: HolderId) = removeFromQueueInternal(holder)

    fun unblock(holder: HolderId) {
        if (blockedHolders.remove(holder)) emit(AllocatorEvent.Unblocked(clock(), holder))
    }

    fun endHolder(holder: HolderId, reason: ReleaseReason) {
        removeFromQueueInternal(holder)
        release(holder, reason)
        statuses.remove(holder)
    }

    fun endSession(sessionId: String) {
        val all = (holds.values + queue + statuses.keys).filter { it.sessionId == sessionId }.toSet()
        all.forEach { endHolder(it, ReleaseReason.SESSION_END) }
    }

    fun serialFor(holder: HolderId): String? {
        val key = holds.entries.firstOrNull { it.value == holder }?.key ?: return null
        return devices.firstOrNull { it.key == key }?.connectedSerials?.firstOrNull()
    }

    private fun fits(wants: Set<Category>, d: AllocDevice) = Category.ANY in wants || d.category in wants

    private fun granted(d: AllocDevice, holder: HolderId): GrabResult.Granted {
        val serial = devices.firstOrNull { it.key == d.key }?.connectedSerials?.firstOrNull() ?: ""
        return GrabResult.Granted(d, serial).also { check(holds[d.key] == holder) }
    }

    private fun grant(holder: HolderId, d: AllocDevice): GrabResult.Granted {
        removeFromQueueInternal(holder, emitEvent = false)
        val now = clock()
        holds[d.key] = holder
        statuses[holder] = HolderStatus.Holding(d.key, now, now, editing = false)
        emit(AllocatorEvent.Granted(now, holder, d.key))
        return granted(d, holder)
    }

    private fun free(device: DeviceKey, holder: HolderId, reason: ReleaseReason) {
        holds.remove(device)
        statuses[holder] = HolderStatus.Released(reason, device)
        emit(AllocatorEvent.Released(clock(), holder, device, reason))
    }

    private fun removeFromQueueInternal(holder: HolderId, emitEvent: Boolean = true) {
        if (queue.remove(holder)) {
            queueWants.remove(holder)
            if (emitEvent) emit(AllocatorEvent.LeftQueue(clock(), holder))
        }
    }

    /** Gives a free, connected, usable device to the first queued holder it fits. */
    private fun passToQueue(device: DeviceKey) {
        if (device in holds) return
        val d = devices.firstOrNull { it.key == device } ?: return
        if (d.dontUse) return
        val next = queue.firstOrNull { it !in blockedHolders && fits(queueWants[it].orEmpty(), d) } ?: return
        grant(next, d)
    }

    private fun emit(e: AllocatorEvent) = onEvent(e)
}
