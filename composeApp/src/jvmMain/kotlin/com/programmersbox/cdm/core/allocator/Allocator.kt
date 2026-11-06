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
 *
 * A device with [AllocDevice.partner] set (a real Apple Watch and its iPhone) is held as a pair: one hold whose
 * [HolderStatus.Holding.device] is the requested key and [HolderStatus.Holding.partner] the other. Both keys map to
 * the holder in [holds]. A pair is grantable only when the partner is listed, connected, usable and free. Release,
 * hand-off and timeouts act on the pair as a unit.
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

    /** The holder's status for one [platform]: Blocked, then its hold there, then Queued, then that platform's last release, then None. */
    fun statusOf(holder: HolderId, platform: Platform): HolderStatus {
        if (holder in blockedHolders) return HolderStatus.Blocked
        holdings[holder]?.get(platform)?.let { return it }
        val i = queue.indexOf(holder)
        if (i >= 0) return HolderStatus.Queued(i + 1, queueWants[holder] ?: Wants(emptySet()))
        return released[holder]?.get(platform) ?: HolderStatus.None
    }

    fun setDevices(devices: List<AllocDevice>) {
        this.devices = devices.toList()
        devices.forEach { known[it.key] = it }
        devices.forEach { passToQueue(it.key) }
    }

    /**
     * With [specific], grabs that device unless the holder already holds one of its platform ([GrabResult.NoneFree]).
     *
     * Otherwise, if the holder already holds a device that fits [wants], returns that hold. This is true even when
     * [Wants.platform] is null: an unflagged grab never adds a second platform. Failing that, grants a free device
     * that fits [wants] on a platform the holder doesn't hold yet. A second platform needs [Wants.platform] set to it.
     */
    fun tryGrab(holder: HolderId, wants: Wants, specific: DeviceKey? = null): GrabResult {
        if (holder in blockedHolders) return GrabResult.Blocked
        if (specific != null) {
            val d = devices.firstOrNull { it.key == specific } ?: return GrabResult.NoneFree
            if (holds[d.key] == holder) return granted(known.getValue(d.key), holder)
            if (holds(holder, d.platform)) return GrabResult.NoneFree
            if (d.dontUse) return GrabResult.DontUse
            holds[d.key]?.let { return GrabResult.HeldByOther(d, it) }
            if (d.connectedSerials.isEmpty() || !partnerFree(d)) return GrabResult.NoneFree
            return grant(holder, d)
        }
        // A pair hold fits when either side does; the result always reports the primary, matching serialFor.
        val existing = holdingsOf(holder).firstOrNull { h ->
            listOfNotNull(h.device, h.partner).any { k -> known[k]?.let(wants::fits) == true }
        }
        if (existing != null) return granted(known.getValue(existing.device), holder)
        val free = devices.firstOrNull {
            !it.dontUse && it.connectedSerials.isNotEmpty() && it.key !in holds && wants.fits(it) &&
                !holds(holder, it.platform) && partnerFree(it)
        }
        return if (free != null) grant(holder, free) else GrabResult.NoneFree
    }

    /**
     * Returns the 1-based queue position, or 0 if the holder is blocked, or already has what [wants] asks for: with
     * [Wants.platform] set, a hold on that platform; without it, a hold that fits [wants] (or a hold on every platform).
     * A queued holder is only ever granted a device of a platform it doesn't hold yet.
     * An already-queued holder keeps its place; its wanted categories are replaced with [wants].
     */
    fun enqueue(holder: HolderId, wants: Wants): Int {
        if (holder in blockedHolders) return 0
        val satisfied = if (wants.platform != null) {
            holds(holder, wants.platform)
        } else {
            heldDevicesOf(holder).any(wants::fits) || Platform.entries.all { holds(holder, it) }
        }
        if (satisfied) return 0
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

    /**
     * Releases [device] (with its partner, when it is one of a pair). With [block], the holder's other holds are
     * released too and the holder is blocked.
     */
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
     * offline (no connected serials). [device] may be either key of a pair: the whole pair moves, keeping its
     * primary, and only when the partner is usable too.
     */
    fun handOff(device: DeviceKey, to: HolderId) {
        val old = holds[device] ?: return
        val primary = holdingOf(old, device)?.device ?: device
        val d = known[primary] ?: return
        if (old == to || to in blockedHolders || holds(to, d.platform)) return
        if (!usable(primary) || d.partner?.let(::usable) == false) return
        free(primary, old, ReleaseReason.USER_HANDOFF)
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
        if (d.connectedSerials.isEmpty() || !partnerFree(d)) return GrabResult.NoneFree
        return grant(holder, d)
    }

    /** With [releaseNow], a held pair is released as a unit whichever of its keys is marked. */
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

    /** The holder's hold covering [device] as its primary or its partner. */
    private fun holdingOf(holder: HolderId, device: DeviceKey): HolderStatus.Holding? =
        holdings[holder]?.values?.firstOrNull { it.device == device || it.partner == device }

    /** Every device the holder has, partners included, primaries first within each hold. */
    private fun heldDevicesOf(holder: HolderId): List<AllocDevice> =
        holdingsOf(holder).flatMap { listOfNotNull(it.device, it.partner) }.mapNotNull { known[it] }

    /** Listed, connected and not don't-use. Says nothing about holds. */
    private fun usable(key: DeviceKey): Boolean =
        devices.firstOrNull { it.key == key }?.let { !it.dontUse && it.connectedSerials.isNotEmpty() } == true

    /** True for an unpaired device; for a paired one, the partner must be usable and not held. */
    private fun partnerFree(d: AllocDevice): Boolean {
        val partner = d.partner ?: return true
        return partner != d.key && partner !in holds && usable(partner)
    }

    private fun platformsOf(wants: Wants): List<Platform> = wants.platform?.let(::listOf) ?: Platform.entries

    private fun granted(d: AllocDevice, holder: HolderId): GrabResult.Granted {
        val serial = devices.firstOrNull { it.key == d.key }?.connectedSerials?.firstOrNull() ?: ""
        return GrabResult.Granted(d, serial).also { check(holds[d.key] == holder) }
    }

    /**
     * A queue entry pinned to another platform survives the grant (the holder may queue for one platform while
     * holding the other). Any other entry is answered by the grant and removed, as before.
     */
    private fun grant(holder: HolderId, d: AllocDevice): GrabResult.Granted {
        val queuedFor = queueWants[holder]?.platform
        if (queuedFor == null || queuedFor == d.platform) removeFromQueueInternal(holder, emitEvent = false)
        val now = clock()
        holds[d.key] = holder
        d.partner?.let { holds[it] = holder }
        holdings.getOrPut(holder) { LinkedHashMap() }[d.platform] =
            HolderStatus.Holding(d.key, now, now, editing = false, platform = d.platform, partner = d.partner)
        released[holder]?.remove(d.platform)
        emit(AllocatorEvent.Granted(now, holder, d.key))
        return granted(d, holder)
    }

    /** Frees [device], or the whole pair when [device] is either key of one. Records and emits the primary. */
    private fun free(device: DeviceKey, holder: HolderId, reason: ReleaseReason) {
        val m = holdings[holder]
        val entry = m?.entries?.firstOrNull { it.value.device == device || it.value.partner == device }
        val primary = entry?.value?.device ?: device
        holds.remove(primary)
        entry?.value?.partner?.let { if (holds[it] == holder) holds.remove(it) }
        val platform = entry?.key ?: known[device]?.platform ?: Platform.ANDROID
        m?.remove(platform)
        if (m != null && m.isEmpty()) holdings.remove(holder)
        val r = released.getOrPut(holder) { LinkedHashMap() }
        r.remove(platform) // re-insert so the most recent release is last
        r[platform] = HolderStatus.Released(reason, primary)
        emit(AllocatorEvent.Released(clock(), holder, primary, reason))
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
     * For one of a pair, both sides are offered at once: the first queued holder (in queue order) that fits either
     * side gets the pair, with the side it fits as primary. A side is offered only when its partner is usable and free.
     */
    private fun passToQueue(device: DeviceKey, except: HolderId? = null) {
        val sides = listOfNotNull(device, known[device]?.partner).distinct()
            .mapNotNull { k -> devices.firstOrNull { it.key == k } }
            .filter { it.key !in holds && !it.dontUse && it.connectedSerials.isNotEmpty() && partnerFree(it) }
        if (sides.isEmpty()) return
        for (holder in queue) {
            if (holder == except || holder in blockedHolders) continue
            val wants = queueWants[holder] ?: continue
            val side = sides.firstOrNull { wants.fits(it) && !holds(holder, it.platform) } ?: continue
            grant(holder, side)
            return
        }
    }

    private fun emit(e: AllocatorEvent) = onEvent(e)
}
