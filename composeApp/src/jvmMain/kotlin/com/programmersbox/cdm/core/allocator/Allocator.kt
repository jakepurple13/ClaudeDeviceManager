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
 *
 * A holder may instead hold one **set** ([HeldSet]): several devices granted all at once by [tryGrabSet] or from
 * the queue ([enqueueSet]), never partially. A holder has either per-platform holds or one set, never both. Every
 * member's device (and partner) maps to the holder in [holds]. Releasing one member from the app, or marking it
 * don't-use with release, releases the whole set; [release] with a platform keeps the other members, names unchanged.
 * Hand-off does not apply to set members.
 *
 * No starvation: the first queued set (in queue order) **reserves** free devices toward its request (see
 * [settleReservation]). A reserved device is not a hold, but no one else can take it: not [tryGrab], [tryRegrab],
 * [tryGrabSet], nor the queue. A pair is reserved as a unit (both keys).
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

    /** Queue entries that ask for a set; such a holder has no [queueWants] entry. */
    private val queueSets = HashMap<HolderId, SetRequest>()

    /** Held sets, in grant order. */
    private val sets = LinkedHashMap<HolderId, HeldSet>()

    /** Free devices kept for the first queued set; a pair has both keys here. See [settleReservation]. */
    private val reservations = LinkedHashMap<DeviceKey, HolderId>()
    private var settling = false
    private val blockedHolders = LinkedHashSet(blocked)

    /** When a queued holder's waiter detached. Absent means attached (or never detached). */
    private val detachedAt = HashMap<HolderId, Instant>()

    /** Live holds per holder, in grant order. Queued and Blocked are derived. */
    private val holdings = LinkedHashMap<HolderId, LinkedHashMap<Platform, HolderStatus.Holding>>()

    /** Last release per holder and platform; the most recent release is last. */
    private val released = LinkedHashMap<HolderId, LinkedHashMap<Platform, HolderStatus.Released>>()

    /** Sets that timed out, per holder, kept for [tryRegrabSet]. See [releasedSetOf]. */
    private val releasedSets = HashMap<HolderId, ReleasedSet>()

    fun snapshot(): AllocatorSnapshot {
        val all = LinkedHashMap<HolderId, HolderStatus>()
        (holdings.keys + sets.keys + released.keys).forEach { all[it] = statusOf(it) }
        queue.forEach { all[it] = statusOf(it) }
        blockedHolders.forEach { all[it] = HolderStatus.Blocked }
        val held = holdings.filterValues { it.isNotEmpty() }.mapValues { it.value.values.toList() }
        return AllocatorSnapshot(
            devices.toList(), holds.toMap(), queue.toList(), all, held,
            sets = sets.toMap(), reserved = reservations.toMap(),
        )
    }

    /** Blocked, then Queued, then the held set, then the first live hold, then the most recent release, then None. */
    fun statusOf(holder: HolderId): HolderStatus {
        if (holder in blockedHolders) return HolderStatus.Blocked
        queuedStatus(holder)?.let { return it }
        sets[holder]?.let { return HolderStatus.HoldingSet(it) }
        holdings[holder]?.values?.firstOrNull()?.let { return it }
        return released[holder]?.values?.lastOrNull() ?: HolderStatus.None
    }

    fun holdingsOf(holder: HolderId): List<HolderStatus.Holding> = holdings[holder]?.values?.toList().orEmpty()

    /**
     * The holder's status for one [platform]: Blocked, then its hold there (or its set, when the set has a member on
     * [platform]), then Queued, then that platform's last release, then None.
     */
    fun statusOf(holder: HolderId, platform: Platform): HolderStatus {
        if (holder in blockedHolders) return HolderStatus.Blocked
        holdings[holder]?.get(platform)?.let { return it }
        sets[holder]?.takeIf { s -> s.members.any { it.platform == platform } }?.let { return HolderStatus.HoldingSet(it) }
        queuedStatus(holder)?.let { return it }
        return released[holder]?.get(platform) ?: HolderStatus.None
    }

    /** The holder's set, or null if it holds none. */
    fun setOf(holder: HolderId): HeldSet? = sets[holder]

    /** Keys reserved for [holder]'s queued set, both keys of a reserved pair included. */
    fun reservedFor(holder: HolderId): List<DeviceKey> = reservations.filterValues { it == holder }.keys.toList()

    private fun queuedStatus(holder: HolderId): HolderStatus.Queued? {
        val i = queue.indexOf(holder)
        if (i < 0) return null
        return HolderStatus.Queued(i + 1, queueWants[holder] ?: Wants(emptySet()), queueSets[holder])
    }

    /** True when [holder] holds a set or any single device. */
    private fun holdsAnything(holder: HolderId): Boolean = holder in sets || holdings[holder]?.isNotEmpty() == true

    /**
     * True when some assignment of [req]'s members fits the listed devices that are not don't-use, ignoring holds and
     * connection. A pair counts only when its partner is listed and not don't-use too.
     */
    fun feasible(req: SetRequest): Boolean {
        val usableKeys = devices.filter { !it.dontUse }.map { it.key }.toSet()
        val candidates = devices.filter { d -> d.key in usableKeys && d.partner?.let { it in usableKeys } != false }
        return SetMatcher.assign(req.members, onePerPair(candidates), ::listed) != null
    }

    /**
     * Grants every member of [req] at once, or nothing. [GrabSetResult.NoneFree] when the holder already holds
     * anything or the free devices don't cover the request; [GrabSetResult.Impossible] when no listed devices could
     * (see [feasible]).
     */
    fun tryGrabSet(holder: HolderId, req: SetRequest): GrabSetResult {
        releasedSets.remove(holder)
        if (holder in blockedHolders) return GrabSetResult.Blocked
        if (holdsAnything(holder)) return GrabSetResult.NoneFree
        if (!feasible(req)) return GrabSetResult.Impossible
        val assigned = SetMatcher.assign(req.members, freeCandidates(holder), ::listed) ?: return GrabSetResult.NoneFree
        return GrabSetResult.Granted(grantSet(holder, req, assigned)).also { settleReservation() }
    }

    /**
     * Queues [holder] for [req] and returns its 1-based position, or 0 if it is blocked or already holds anything.
     * An already-queued holder keeps its place; its request (set or single) is replaced with [req].
     * If the entry is the first set, it reserves at once; when free devices already cover [req] it is granted at once
     * and 0 is returned (it now holds a set).
     */
    fun enqueueSet(holder: HolderId, req: SetRequest): Int {
        releasedSets.remove(holder)
        if (holder in blockedHolders || holdsAnything(holder)) return 0
        val existing = queue.indexOf(holder)
        queueWants.remove(holder)
        queueSets[holder] = req
        if (existing >= 0) return (existing + 1).also { settleReservation() }
        queue.add(holder)
        released[holder]?.let { r -> req.members.mapNotNull { it.platform }.forEach { r.remove(it) } }
        emit(AllocatorEvent.Queued(clock(), holder, Wants(emptySet()), req))
        settleReservation()
        return queue.indexOf(holder) + 1
    }

    fun setDevices(devices: List<AllocDevice>) {
        this.devices = devices.toList()
        devices.forEach { known[it.key] = it }
        devices.forEach { passToQueue(it.key) }
        settleReservation()
    }

    /**
     * With [specific], grabs that device unless the holder already holds one of its platform ([GrabResult.NoneFree]).
     *
     * Otherwise, if the holder already holds a device that fits [wants], returns that hold. This is true even when
     * [Wants.platform] is null: an unflagged grab never adds a second platform. Failing that, grants a free device
     * that fits [wants] on a platform the holder doesn't hold yet. A second platform needs [Wants.platform] set to it.
     */
    fun tryGrab(holder: HolderId, wants: Wants, specific: DeviceKey? = null): GrabResult =
        grab(holder, wants, specific).also { settleReservation() }

    /** [tryGrab] without settling: a reserved device counts as not free. */
    private fun grab(holder: HolderId, wants: Wants, specific: DeviceKey?): GrabResult {
        if (holder in blockedHolders) return GrabResult.Blocked
        if (holder in sets) return GrabResult.NoneFree
        if (specific != null) {
            val d = devices.firstOrNull { it.key == specific } ?: return GrabResult.NoneFree
            if (holds[d.key] == holder) return granted(known.getValue(d.key), holder)
            if (holds(holder, d.platform)) return GrabResult.NoneFree
            if (d.dontUse) return GrabResult.DontUse
            holds[d.key]?.let { return GrabResult.HeldByOther(d, it) }
            if (d.connectedSerials.isEmpty() || !partnerFree(d) || reservedAgainst(d, holder)) return GrabResult.NoneFree
            return grant(holder, d)
        }
        // A pair hold fits when either side does; the result always reports the primary, matching serialFor.
        val existing = holdingsOf(holder).firstOrNull { h ->
            listOfNotNull(h.device, h.partner).any { k -> known[k]?.let(wants::fits) == true }
        }
        if (existing != null) return granted(known.getValue(existing.device), holder)
        val free = devices.firstOrNull {
            !it.dontUse && it.connectedSerials.isNotEmpty() && it.key !in holds && wants.fits(it) &&
                !holds(holder, it.platform) && partnerFree(it) && !reservedAgainst(it, holder)
        }
        return if (free != null) grant(holder, free) else GrabResult.NoneFree
    }

    /**
     * Returns the 1-based queue position, or 0 if the holder is blocked, or already has what [wants] asks for: with
     * [Wants.platform] set, a hold on that platform; without it, a hold that fits [wants] (or a hold on every platform).
     * A queued holder is only ever granted a device of a platform it doesn't hold yet.
     * An already-queued holder keeps its place; its wanted categories are replaced with [wants]. A set entry turned
     * single drops its reservation; if a freed device is granted to the holder right away, 0 is returned.
     */
    fun enqueue(holder: HolderId, wants: Wants): Int {
        if (holder in blockedHolders || holder in sets) return 0
        val satisfied = if (wants.platform != null) {
            holds(holder, wants.platform)
        } else {
            heldDevicesOf(holder).any(wants::fits) || Platform.entries.all { holds(holder, it) }
        }
        if (satisfied) return 0
        val existing = queue.indexOf(holder)
        if (existing >= 0) {
            queueSets.remove(holder)
            queueWants[holder] = wants
            // A set entry turned single gives up its reservation; the device may now go to this holder.
            settleReservation()
            return queue.indexOf(holder) + 1
        }
        queue.add(holder)
        queueWants[holder] = wants
        releasedSets.remove(holder)
        released[holder]?.let { r -> platformsOf(wants).forEach { r.remove(it) } }
        emit(AllocatorEvent.Queued(clock(), holder, wants))
        settleReservation()
        return queue.indexOf(holder) + 1
    }

    /**
     * Releases the holder's hold on [platform], or every hold when [platform] is null. For a set, releases that
     * platform's members (the rest stay held under their names), or the whole set when [platform] is null.
     * Any release by the holder drops its timed-out set record ([releasedSetOf]).
     */
    fun release(holder: HolderId, reason: ReleaseReason, platform: Platform? = null) {
        releasedSets.remove(holder)
        holdingsOf(holder).filter { platform == null || it.platform == platform }.forEach { h ->
            free(h.device, holder, reason)
            passToQueue(h.device, except = holder)
        }
        freeSet(holder, reason) { platform == null || it.platform == platform }.forEach { passToQueue(it, except = holder) }
        settleReservation()
    }

    /**
     * Releases [device] (with its partner, when it is one of a pair). With [block], the holder's other holds are
     * released too and the holder is blocked. For a set member, the whole set is released.
     */
    fun releaseDevice(device: DeviceKey, block: Boolean) {
        val holder = holds[device] ?: return
        if (block) removeFromQueueInternal(holder)
        if (holder in sets) {
            val freed = freeSet(holder, ReleaseReason.USER_RELEASE) { true }
            if (block) {
                blockedHolders.add(holder)
                emit(AllocatorEvent.Blocked(clock(), holder))
            }
            freed.forEach { passToQueue(it, except = holder) }
            settleReservation()
            return
        }
        free(device, holder, ReleaseReason.USER_RELEASE)
        val others = if (block) holdingsOf(holder).map { it.device } else emptyList()
        others.forEach { free(it, holder, ReleaseReason.USER_RELEASE) }
        if (block) {
            blockedHolders.add(holder)
            emit(AllocatorEvent.Blocked(clock(), holder))
        }
        passToQueue(device, except = holder)
        others.forEach { passToQueue(it, except = holder) }
        settleReservation()
    }

    /**
     * No-op if [to] is blocked or already holds a device of this device's platform, or the device is don't-use or
     * offline (no connected serials). [device] may be either key of a pair: the whole pair moves, keeping its
     * primary, and only when the partner is usable too. A set member never moves, and a set holder never receives one.
     */
    fun handOff(device: DeviceKey, to: HolderId) {
        val old = holds[device] ?: return
        if (old in sets || to in sets) return
        val primary = holdingOf(old, device)?.device ?: device
        val d = known[primary] ?: return
        if (old == to || to in blockedHolders || holds(to, d.platform)) return
        if (!usable(primary) || d.partner?.let(::usable) == false) return
        free(primary, old, ReleaseReason.USER_HANDOFF)
        grant(to, d)
        // The grant may answer [to]'s queued set entry.
        settleReservation()
    }

    /**
     * Resets the idle timer of the holder's [platform] hold only, and clears editing on that hold. For a set holder
     * with a member on [platform], resets the whole set's timer instead (see [recordSetActivity]).
     */
    fun recordDeviceActivity(holder: HolderId, platform: Platform) {
        if (sets[holder]?.members?.any { it.platform == platform } == true) recordSetActivity(holder)
        val m = holdings[holder] ?: return
        m[platform]?.let { m[platform] = it.copy(lastActivity = clock(), editing = false) }
    }

    /** Switches every hold of the holder, and its set, to the editing timer. */
    fun recordEdit(holder: HolderId) {
        sets[holder]?.let { sets[holder] = it.copy(editing = true) }
        val m = holdings[holder] ?: return
        m.replaceAll { _, h -> h.copy(editing = true) }
    }

    /** Resets the idle timer of the holder's whole set and clears its editing flag. */
    fun recordSetActivity(holder: HolderId) {
        sets[holder]?.let { sets[holder] = it.copy(lastActivity = clock(), editing = false) }
    }

    /**
     * The holder's set as it was when it timed out, or null. Cleared when the holder acquires again ([tryGrabSet],
     * [enqueueSet], any single grant, or a single [enqueue]), regrabs it, releases, or ends.
     */
    fun releasedSetOf(holder: HolderId): ReleasedSet? = releasedSets[holder]

    /**
     * Only valid after an idle/edit timeout of the holder's set: regrabs exactly the former member devices (with their
     * partners) under the same names, all or nothing. If any member is unlisted, offline, don't-use, held, or reserved
     * for someone else, nothing is granted, the [releasedSetOf] record stays, and [GrabSetResult.NoneFree] is returned.
     * Anything else (no timed-out set, or the holder holds something) is [GrabSetResult.NoneFree] too.
     */
    fun tryRegrabSet(holder: HolderId): GrabSetResult {
        if (holder in blockedHolders) return GrabSetResult.Blocked
        if (holdsAnything(holder)) return GrabSetResult.NoneFree
        val rs = releasedSets[holder] ?: return GrabSetResult.NoneFree
        if (rs.reason != ReleaseReason.IDLE_TIMEOUT && rs.reason != ReleaseReason.EDIT_TIMEOUT) return GrabSetResult.NoneFree
        val free = rs.members.all { m ->
            // A device re-paired since the timeout is not the same member any more.
            listed(m.device)?.partner == m.partner && listOfNotNull(m.device, m.partner).all { k ->
                usable(k) && k !in holds && reservations[k]?.let { it == holder } != false
            }
        }
        if (!free) return GrabSetResult.NoneFree
        releasedSets.remove(holder)
        return GrabSetResult.Granted(grantMembers(holder, rs.members)).also { settleReservation() }
    }

    fun attachWaiter(holder: HolderId) {
        detachedAt.remove(holder)
    }

    /** Starts the queue-grace clock for a queued holder. */
    fun detachWaiter(holder: HolderId) {
        if (holder in queue) detachedAt[holder] = clock()
    }

    /**
     * Expires detached queue spots, then releases holds past their idle/editing timeout. A set times out as a unit:
     * every member is released and the set is recorded for [releasedSetOf]; its devices go through the queue.
     */
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
        sets.toList().forEach { (holder, set) ->
            val limit = if (set.editing) t.editing else t.idle
            if (now - set.lastActivity > limit) {
                val reason = if (set.editing) ReleaseReason.EDIT_TIMEOUT else ReleaseReason.IDLE_TIMEOUT
                val freed = freeSet(holder, reason) { true }
                releasedSets[holder] = ReleasedSet(reason, set.members)
                freed.forEach { passToQueue(it, except = holder) }
            }
        }
        settleReservation()
    }

    /**
     * Only valid after an idle/edit timeout of the [platform] hold; anything else is [GrabResult.NoneFree]. A timed-out
     * set comes back only whole, through [tryRegrabSet], so its members are [GrabResult.NoneFree] here.
     */
    fun tryRegrab(holder: HolderId, platform: Platform): GrabResult {
        if (holder in blockedHolders) return GrabResult.Blocked
        if (holder in sets || holder in releasedSets || holds(holder, platform)) return GrabResult.NoneFree
        val st = released[holder]?.get(platform) ?: return GrabResult.NoneFree
        if (st.reason != ReleaseReason.IDLE_TIMEOUT && st.reason != ReleaseReason.EDIT_TIMEOUT) return GrabResult.NoneFree
        val d = devices.firstOrNull { it.key == st.lastDevice } ?: return GrabResult.NoneFree
        if (d.dontUse) return GrabResult.DontUse
        holds[d.key]?.let { return GrabResult.HeldByOther(d, it) }
        if (d.connectedSerials.isEmpty() || !partnerFree(d) || reservedAgainst(d, holder)) return GrabResult.NoneFree
        return grant(holder, d).also { settleReservation() }
    }

    /**
     * With [releaseNow], a held pair is released as a unit whichever of its keys is marked, and a set member releases
     * the whole set (its other devices go through the queue).
     */
    fun setDontUse(device: DeviceKey, dontUse: Boolean, releaseNow: Boolean) {
        known[device]?.let { known[device] = it.copy(dontUse = dontUse) }
        devices = devices.map { if (it.key == device) it.copy(dontUse = dontUse) else it }
        if (dontUse) {
            val holder = holds[device]
            if (holder != null && releaseNow && holder in sets) {
                freeSet(holder, ReleaseReason.DONT_USE) { true }.forEach { passToQueue(it, except = holder) }
            } else if (holder != null && releaseNow) {
                free(device, holder, ReleaseReason.DONT_USE)
            }
        } else {
            passToQueue(device)
        }
        settleReservation()
    }

    fun moveInQueue(holder: HolderId, newIndex: Int) {
        if (!queue.remove(holder)) return
        queue.add(newIndex.coerceIn(0, queue.size), holder)
        settleReservation()
    }

    fun removeFromQueue(holder: HolderId) {
        removeFromQueueInternal(holder)
        settleReservation()
    }

    fun unblock(holder: HolderId) {
        if (blockedHolders.remove(holder)) emit(AllocatorEvent.Unblocked(clock(), holder))
    }

    fun endHolder(holder: HolderId, reason: ReleaseReason) {
        removeFromQueueInternal(holder)
        release(holder, reason)
        holdings.remove(holder)
        sets.remove(holder)
        released.remove(holder)
        releasedSets.remove(holder)
    }

    fun endSession(sessionId: String) {
        val all = (holds.values + queue + holdings.keys + sets.keys + released.keys + releasedSets.keys).filter { it.sessionId == sessionId }.toSet()
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
        // Acquiring again replaces a timed-out set: it can no longer be regrabbed.
        releasedSets.remove(holder)
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
            queueSets.remove(holder)
            if (emitEvent) emit(AllocatorEvent.LeftQueue(clock(), holder))
        }
    }

    /**
     * Gives a free, connected, usable device to the first queued holder it fits who doesn't hold its platform yet.
     * [except] is the holder that just gave the device up: it may still be queued for its other platform.
     * For one of a pair, both sides are offered at once: the first queued holder (in queue order) that fits either
     * side gets the pair, with the side it fits as primary. A side is offered only when its partner is usable and free.
     *
     * A queued set, met in queue order, is granted whole when the free devices cover every member. If that leaves
     * [device] free, the walk goes on.
     *
     * A reserved device is never offered: a single never gets one, and a set only counts its own reservation.
     */
    private fun passToQueue(device: DeviceKey, except: HolderId? = null) {
        fun sides() = listOfNotNull(device, known[device]?.partner).distinct()
            .mapNotNull { k -> devices.firstOrNull { it.key == k } }
            .filter {
                it.key !in holds && !it.dontUse && it.connectedSerials.isNotEmpty() && partnerFree(it) &&
                    it.key !in reservations
            }
        if (sides().isEmpty()) return
        for (holder in queue.toList()) {
            if (holder == except || holder in blockedHolders || holder !in queue) continue
            val req = queueSets[holder]
            if (req != null) {
                if (holdsAnything(holder)) continue
                val candidates = freeCandidates(holder)
                val assigned = SetMatcher.assign(req.members, candidates, ::listed)
                if (assigned == null) {
                    // The reserving set takes what it can before anyone behind it sees the device.
                    if (holder == reservingSet()) {
                        setReservation(holder, SetMatcher.maxPartial(req.members, candidates, ::listed).values)
                        if (sides().isEmpty()) return
                    }
                    continue
                }
                if (holder in reservations.values) setReservation(null, emptyList())
                grantSet(holder, req, assigned)
                if (sides().isEmpty()) return
                continue
            }
            val wants = queueWants[holder] ?: continue
            val side = sides().firstOrNull { wants.fits(it) && !holds(holder, it.platform) } ?: continue
            grant(holder, side)
            return
        }
    }

    /** The listed device with [key], or null. */
    private fun listed(key: DeviceKey): AllocDevice? = devices.firstOrNull { it.key == key }

    /** Each pair once: a device is dropped when its partner came earlier in [ds]. */
    private fun onePerPair(ds: List<AllocDevice>): List<AllocDevice> {
        val seen = HashSet<DeviceKey>()
        return ds.filter { d ->
            val keep = d.partner == null || d.partner !in seen
            seen += d.key
            keep
        }
    }

    /**
     * Free, connected, usable devices whose partner (if any) is free and usable too, each pair once, leaving out
     * devices reserved for anyone but [holder]. [holder]'s own reserved devices come first, so a matching keeps them.
     */
    private fun freeCandidates(holder: HolderId): List<AllocDevice> {
        val free = devices.filter {
            it.key !in holds && !it.dontUse && it.connectedSerials.isNotEmpty() && partnerFree(it) &&
                !reservedAgainst(it, holder)
        }
        val (mine, rest) = free.partition { reservations[it.key] == holder }
        return onePerPair(mine + rest)
    }

    /** True when [d] or its partner is reserved for someone other than [holder]. */
    private fun reservedAgainst(d: AllocDevice, holder: HolderId): Boolean =
        listOfNotNull(d.key, d.partner).any { k -> reservations[k]?.let { it != holder } == true }

    /**
     * Recomputes the reservation, after any change that can affect it. Only the first set entry in queue order
     * reserves, and only when [feasible] (a set no listed devices could meet would hold devices for nothing). Its
     * reservation is the devices of a maximum partial matching over the free devices that are unreserved or its own.
     * A full matching grants the set at once, and the next set entry takes over. Devices that leave the reservation
     * (no longer needed, offline, don't-use, or the set left the queue) go through [passToQueue] when still free.
     * Repeats until nothing changes, since passing a device on can grant a set.
     */
    private fun settleReservation() {
        if (settling) return
        settling = true
        try {
            // Each pass that changes anything grants a set or a device, so this bound is never reached in practice.
            repeat(devices.size + queue.size + 2) {
                val holdsBefore = holds.toMap()
                val reservedBefore = reservations.toMap()
                val first = reservingSet()
                val req = first?.let { queueSets.getValue(it) }
                val matched = if (first != null && req != null) {
                    SetMatcher.maxPartial(req.members, freeCandidates(first), ::listed)
                } else {
                    emptyMap()
                }
                if (first != null && req != null && matched.size == req.members.size) {
                    setReservation(null, emptyList())
                    grantSet(first, req, req.members.indices.map { matched.getValue(it) })
                } else {
                    setReservation(first, matched.values)
                }
                val drop = droppedReservations.filter { it !in reservations && it !in holds }
                droppedReservations.clear()
                drop.forEach { passToQueue(it) }
                if (holds == holdsBefore && reservations == reservedBefore) return
            }
        } finally {
            settling = false
        }
    }

    /** The queued set that may reserve: the first set entry in queue order, if [feasible]. */
    private fun reservingSet(): HolderId? =
        queue.firstOrNull { it in queueSets && it !in blockedHolders && !holdsAnything(it) }
            ?.takeIf { feasible(queueSets.getValue(it)) }

    /** Replaces the reservation with [devices] (and their partners) for [holder]; keys that leave it are remembered. */
    private fun setReservation(holder: HolderId?, devices: Collection<AllocDevice>) {
        val keys = devices.flatMap { listOfNotNull(it.key, it.partner) }
        droppedReservations += reservations.keys.filter { it !in keys }
        reservations.clear()
        if (holder != null) keys.forEach { reservations[it] = holder }
    }

    /** Keys that left the reservation and still need to go through [passToQueue]. */
    private val droppedReservations = LinkedHashSet<DeviceKey>()

    /** Grants [assigned] (one device per member of [req], in member order) to [holder] as one set. */
    private fun grantSet(holder: HolderId, req: SetRequest, assigned: List<AllocDevice>): HeldSet {
        val members = SetMatcher.names(req.members).mapIndexed { i, name ->
            val d = assigned[i]
            SetMember(name, req.members[i].platform!!, d.key, d.partner)
        }
        return grantMembers(holder, members)
    }

    /** Grants [members] (devices and partners) to [holder] as one set, with a fresh timer. */
    private fun grantMembers(holder: HolderId, members: List<SetMember>): HeldSet {
        removeFromQueueInternal(holder, emitEvent = false)
        val now = clock()
        val set = HeldSet(members, since = now, lastActivity = now, editing = false)
        sets[holder] = set
        members.forEach { m ->
            holds[m.device] = holder
            m.partner?.let { holds[it] = holder }
        }
        released[holder]?.let { r ->
            members.forEach { r.remove(it.platform) }
            if (r.isEmpty()) released.remove(holder)
        }
        members.forEach { emit(AllocatorEvent.Granted(now, holder, it.device)) }
        return set
    }

    /**
     * Frees the holder's set members matching [which] (device and partner), emitting one Released per member and
     * recording each platform's last release. The rest stay held under their names. Returns the freed keys.
     */
    private fun freeSet(holder: HolderId, reason: ReleaseReason, which: (SetMember) -> Boolean): List<DeviceKey> {
        val set = sets[holder] ?: return emptyList()
        val (gone, kept) = set.members.partition(which)
        if (gone.isEmpty()) return emptyList()
        if (kept.isEmpty()) sets.remove(holder) else sets[holder] = set.copy(members = kept)
        val freed = ArrayList<DeviceKey>()
        val r = released.getOrPut(holder) { LinkedHashMap() }
        gone.forEach { m ->
            listOfNotNull(m.device, m.partner).forEach { k ->
                if (holds[k] == holder) {
                    holds.remove(k)
                    freed += k
                }
            }
            r.remove(m.platform) // re-insert so the most recent release is last
            r[m.platform] = HolderStatus.Released(reason, m.device)
            emit(AllocatorEvent.Released(clock(), holder, m.device, reason))
        }
        return freed
    }

    /** Events held back between [deferEvents] and [flushEvents]; null when events go out at once. */
    private var deferred: MutableList<AllocatorEvent>? = null

    /** Holds events back until [flushEvents], so a grab undone by [undoGrab] in between is never seen. */
    fun deferEvents() {
        if (deferred == null) deferred = mutableListOf()
    }

    /** Sends the events held back since [deferEvents], in order, and goes back to sending them at once. */
    fun flushEvents() {
        val d = deferred ?: return
        deferred = null
        d.forEach(onEvent)
    }

    /**
     * Releases [holder]'s [platform] hold, granted earlier in the same deferred decision, as if it never happened:
     * its Granted and Released events are dropped (no grab/release notification, no activity entry). Other events
     * (a queued holder getting the freed device) still go out.
     */
    fun undoGrab(holder: HolderId, platform: Platform) {
        val device = holdings[holder]?.get(platform)?.device ?: return
        release(holder, ReleaseReason.SELF_RELEASE, platform)
        released[holder]?.let { r ->
            if (r[platform]?.lastDevice == device) r.remove(platform)
            if (r.isEmpty()) released.remove(holder)
        }
        deferred?.removeAll {
            (it is AllocatorEvent.Granted && it.holder == holder && it.device == device) ||
                (it is AllocatorEvent.Released && it.holder == holder && it.device == device)
        }
    }

    private fun emit(e: AllocatorEvent) {
        val d = deferred
        if (d != null) d += e else onEvent(e)
    }
}
