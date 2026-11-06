package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class Timers(val idle: Duration = 15.minutes, val editing: Duration = 5.minutes, val queueGrace: Duration = 2.minutes)

/**
 * Not thread-safe: the service wraps it in a Mutex.
 *
 * A holder holds at most one device per [Platform]. Each hold has its own idle/editing timer and its own
 * Released record, so a timeout on one platform can be regrabbed without touching the other.
 */
class Allocator(
    private val clock: () -> Instant,
    blocked: Set<HolderId> = emptySet(),
    private val timers: () -> Timers = { Timers() },
    private val onEvent: (AllocatorEvent) -> Unit = {},
) {
    private var devices: List<AllocDevice> = emptyList()
    private val known = LinkedHashMap<DeviceKey, AllocDevice>()
    private val holds = LinkedHashMap<DeviceKey, HolderId>()
    private val queue = ArrayList<HolderId>()
    private val queueWants = HashMap<HolderId, Wants>()
    private val blockedHolders = LinkedHashSet(blocked)

    /** When a queued holder's waiter detached. Absent means attached (or never detached). */
    private val detachedAt = HashMap<HolderId, Instant>()

    /** Live holds per holder, in grant order. Queued and Blocked are derived. */
    private val holdings = LinkedHashMap<HolderId, LinkedHashMap<Platform, HolderStatus.Holding>>()

    /** Last release per holder and platform; the most recent release is last. */
    private val released = LinkedHashMap<HolderId, LinkedHashMap<Platform, HolderStatus.Released>>()

    fun snapshot(): AllocatorSnapshot {
        val all = LinkedHashMap<HolderId, HolderStatus>()
        (holdings.keys + released.keys).forEach { all[it] = statusOf(it) }
        queue.forEach { all[it] = statusOf(it) }
        blockedHolders.forEach { all[it] = HolderStatus.Blocked }
        val held = holdings.filterValues { it.isNotEmpty() }.mapValues { it.value.values.toList() }
        return AllocatorSnapshot(devices.toList(), holds.toMap(), queue.toList(), all, held)
    }

    /** Blocked, then Queued, then the first live hold, then the most recent release, then None. */
    fun statusOf(holder: HolderId): HolderStatus {
        if (holder in blockedHolders) return HolderStatus.Blocked
        val i = queue.indexOf(holder)
        if (i >= 0) return HolderStatus.Queued(i + 1, queueWants[holder] ?: Wants(emptySet()))
        holdings[holder]?.values?.firstOrNull()?.let { return it }
        return released[holder]?.values?.lastOrNull() ?: HolderStatus.None
    }

    fun holdingsOf(holder: HolderId): List<HolderStatus.Holding> = holdings[holder]?.values?.toList().orEmpty()

    fun setDevices(devices: List<AllocDevice>) {
        this.devices = devices.toList()
        devices.forEach { known[it.key] = it }
        devices.forEach { passToQueue(it.key) }
    }

    /**
     * With [specific], grabs that device unless the holder already holds one of its platform ([GrabResult.NoneFree]).
     *
     * Otherwise grants a free device that fits [wants] on a platform the holder doesn't hold yet. If [wants] names
     * a platform the holder already holds (or no new platform is free), returns the existing hold that fits [wants].
     */
    fun tryGrab(holder: HolderId, wants: Wants, specific: DeviceKey? = null): GrabResult {
        if (holder in blockedHolders) return GrabResult.Blocked
        if (specific != null) {
            val d = devices.firstOrNull { it.key == specific } ?: return GrabResult.NoneFree
            if (holds[d.key] == holder) return granted(known.getValue(d.key), holder)
            if (holds(holder, d.platform)) return GrabResult.NoneFree
            if (d.dontUse) return GrabResult.DontUse
            holds[d.key]?.let { return GrabResult.HeldByOther(d, it) }
            if (d.connectedSerials.isEmpty()) return GrabResult.NoneFree
            return grant(holder, d)
        }
        val existing = holdingsOf(holder).map { known.getValue(it.device) }.firstOrNull { wants.fits(it) }
        if (existing != null && wants.platform != null) return granted(existing, holder)
        val free = devices.firstOrNull {
            !it.dontUse && it.connectedSerials.isNotEmpty() && it.key !in holds && wants.fits(it) && !holds(holder, it.platform)
        }
        return when {
            free != null -> grant(holder, free)
            existing != null -> granted(existing, holder)
            else -> GrabResult.NoneFree
        }
    }

    /**
     * Returns the 1-based queue position, or 0 if the holder is blocked or already holds every platform [wants]
     * allows. A queued holder is only ever granted a device of a platform it doesn't hold yet.
     * An already-queued holder keeps its place; its wanted categories are replaced with [wants].
     */
    fun enqueue(holder: HolderId, wants: Wants): Int {
        if (holder in blockedHolders || platformsOf(wants).all { holds(holder, it) }) return 0
        val existing = queue.indexOf(holder)
        if (existing >= 0) {
            queueWants[holder] = wants
            return existing + 1
        }
        queue.add(holder)
        queueWants[holder] = wants
        released[holder]?.let { r -> platformsOf(wants).forEach { r.remove(it) } }
        emit(AllocatorEvent.Queued(clock(), holder, wants))
        return queue.size
    }

    /** Releases the holder's hold on [platform], or every hold when [platform] is null. */
    fun release(holder: HolderId, reason: ReleaseReason, platform: Platform? = null) {
        holdingsOf(holder).filter { platform == null || it.platform == platform }.forEach { h ->
            free(h.device, holder, reason)
            passToQueue(h.device, except = holder)
        }
    }

    /** Releases [device]. With [block], the holder's other holds are released too and the holder is blocked. */
    fun releaseDevice(device: DeviceKey, block: Boolean) {
        val holder = holds[device] ?: return
        if (block) removeFromQueueInternal(holder)
        free(device, holder, ReleaseReason.USER_RELEASE)
        val others = if (block) holdingsOf(holder).map { it.device } else emptyList()
        others.forEach { free(it, holder, ReleaseReason.USER_RELEASE) }
        if (block) {
            blockedHolders.add(holder)
            emit(AllocatorEvent.Blocked(clock(), holder))
        }
        passToQueue(device, except = holder)
        others.forEach { passToQueue(it, except = holder) }
    }

    /**
     * No-op if [to] is blocked or already holds a device of this device's platform, or the device is don't-use or
     * offline (no connected serials).
     */
    fun handOff(device: DeviceKey, to: HolderId) {
        val old = holds[device] ?: return
        val d = known[device] ?: return
        if (old == to || to in blockedHolders || holds(to, d.platform)) return
        if (d.dontUse || devices.firstOrNull { it.key == device }?.connectedSerials.isNullOrEmpty()) return
        free(device, old, ReleaseReason.USER_HANDOFF)
        grant(to, d)
    }

    /** Resets the idle timer of the holder's [platform] hold only, and clears editing on that hold. */
    fun recordDeviceActivity(holder: HolderId, platform: Platform) {
        val m = holdings[holder] ?: return
        m[platform]?.let { m[platform] = it.copy(lastActivity = clock(), editing = false) }
    }

    /** Switches every hold of the holder to the editing timer. */
    fun recordEdit(holder: HolderId) {
        val m = holdings[holder] ?: return
        m.replaceAll { _, h -> h.copy(editing = true) }
    }

    fun attachWaiter(holder: HolderId) {
        detachedAt.remove(holder)
    }

    /** Starts the queue-grace clock for a queued holder. */
    fun detachWaiter(holder: HolderId) {
        if (holder in queue) detachedAt[holder] = clock()
    }

    /** Expires detached queue spots, then releases holds past their idle/editing timeout. */
    fun tick() {
        val now = clock()
        val t = timers()
        detachedAt.filter { (h, at) -> now - at > t.queueGrace && h in queue }.keys.forEach { removeFromQueueInternal(it) }
        holdings.flatMap { (holder, m) -> m.values.map { holder to it } }.forEach { (holder, st) ->
            val limit = if (st.editing) t.editing else t.idle
            if (now - st.lastActivity > limit) {
                free(st.device, holder, if (st.editing) ReleaseReason.EDIT_TIMEOUT else ReleaseReason.IDLE_TIMEOUT)
                passToQueue(st.device, except = holder)
            }
        }
    }

    /** Only valid after an idle/edit timeout of the [platform] hold; anything else is [GrabResult.NoneFree]. */
    fun tryRegrab(holder: HolderId, platform: Platform): GrabResult {
        if (holder in blockedHolders) return GrabResult.Blocked
        if (holds(holder, platform)) return GrabResult.NoneFree
        val st = released[holder]?.get(platform) ?: return GrabResult.NoneFree
        if (st.reason != ReleaseReason.IDLE_TIMEOUT && st.reason != ReleaseReason.EDIT_TIMEOUT) return GrabResult.NoneFree
        val d = devices.firstOrNull { it.key == st.lastDevice } ?: return GrabResult.NoneFree
        if (d.dontUse) return GrabResult.DontUse
        holds[d.key]?.let { return GrabResult.HeldByOther(d, it) }
        if (d.connectedSerials.isEmpty()) return GrabResult.NoneFree
        return grant(holder, d)
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
        holdings.remove(holder)
        released.remove(holder)
    }

    fun endSession(sessionId: String) {
        val all = (holds.values + queue + holdings.keys + released.keys).filter { it.sessionId == sessionId }.toSet()
        all.forEach { endHolder(it, ReleaseReason.SESSION_END) }
    }

    /** First connected serial of the holder's [platform] hold, or null if it holds none or the device is offline. */
    fun serialFor(holder: HolderId, platform: Platform): String? {
        val key = holdings[holder]?.get(platform)?.device ?: return null
        return devices.firstOrNull { it.key == key }?.connectedSerials?.firstOrNull()
    }

    private fun holds(holder: HolderId, platform: Platform): Boolean = holdings[holder]?.containsKey(platform) == true

    private fun platformsOf(wants: Wants): List<Platform> = wants.platform?.let(::listOf) ?: Platform.entries

    private fun granted(d: AllocDevice, holder: HolderId): GrabResult.Granted {
        val serial = devices.firstOrNull { it.key == d.key }?.connectedSerials?.firstOrNull() ?: ""
        return GrabResult.Granted(d, serial).also { check(holds[d.key] == holder) }
    }

    private fun grant(holder: HolderId, d: AllocDevice): GrabResult.Granted {
        removeFromQueueInternal(holder, emitEvent = false)
        val now = clock()
        holds[d.key] = holder
        holdings.getOrPut(holder) { LinkedHashMap() }[d.platform] =
            HolderStatus.Holding(d.key, now, now, editing = false, platform = d.platform)
        released[holder]?.remove(d.platform)
        emit(AllocatorEvent.Granted(now, holder, d.key))
        return granted(d, holder)
    }

    private fun free(device: DeviceKey, holder: HolderId, reason: ReleaseReason) {
        holds.remove(device)
        val m = holdings[holder]
        val platform = m?.entries?.firstOrNull { it.value.device == device }?.key ?: known[device]?.platform ?: Platform.ANDROID
        m?.remove(platform)
        if (m != null && m.isEmpty()) holdings.remove(holder)
        val r = released.getOrPut(holder) { LinkedHashMap() }
        r.remove(platform) // re-insert so the most recent release is last
        r[platform] = HolderStatus.Released(reason, device)
        emit(AllocatorEvent.Released(clock(), holder, device, reason))
    }

    private fun removeFromQueueInternal(holder: HolderId, emitEvent: Boolean = true) {
        detachedAt.remove(holder)
        if (queue.remove(holder)) {
            queueWants.remove(holder)
            if (emitEvent) emit(AllocatorEvent.LeftQueue(clock(), holder))
        }
    }

    /**
     * Gives a free, connected, usable device to the first queued holder it fits who doesn't hold its platform yet.
     * [except] is the holder that just gave the device up: it may still be queued for its other platform.
     */
    private fun passToQueue(device: DeviceKey, except: HolderId? = null) {
        if (device in holds) return
        val d = devices.firstOrNull { it.key == device } ?: return
        if (d.dontUse || d.connectedSerials.isEmpty()) return
        val next = queue.firstOrNull {
            it != except && it !in blockedHolders && queueWants[it]?.fits(d) == true && !holds(it, d.platform)
        } ?: return
        grant(next, d)
    }

    private fun emit(e: AllocatorEvent) = onEvent(e)
}
