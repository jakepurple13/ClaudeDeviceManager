package com.programmersbox.cdm.service

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.devices.TrackedDevice
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.StateFlow

/** A hook call, already parsed from the JSON payload (Task 7 does the parsing). */
data class HookInput(
    val hookEventName: String,
    val sessionId: String,
    val agentId: String?,
    val agentType: String?,
    val cwd: String,
    val toolName: String?,
    val command: String?,
    /** The hook JSON's `permission_mode`. */
    val permissionMode: String? = null,
) {
    val holder: HolderId get() = HolderId(sessionId, agentId ?: HolderId.MAIN)
}

/** Display-only bookkeeping about a holder, refreshed on every hook or cdm call. */
data class HolderInfo(
    val holder: HolderId,
    val cwd: String,
    val agentType: String?,
    val lastDeviceCommand: String?,
    val lastSeen: Instant,
    val ended: Boolean,
)

sealed interface AcquireResult {
    /**
     * [serial] is the granted device's ADB serial (Android) or UDID (iOS). For a paired hold (a real Apple Watch and
     * its iPhone) it is the requested device; [pairSerial] is the partner's.
     */
    data class Granted(val serial: String, val name: String, val platform: Platform, val pairSerial: String? = null) : AcquireResult

    /** [position] is the 1-based queue position when waiting stopped. */
    data class TimedOut(val position: Int) : AcquireResult
    data object Blocked : AcquireResult

    /** The holder was taken out of the queue while waiting (the user removed it, or its session ended). */
    data object Removed : AcquireResult
}

/** The service's public API (Ruling B). UI tasks and the HTTP server depend on this, not on [DeviceManagerService]. */
interface DeviceManager {
    val snapshot: StateFlow<AllocatorSnapshot>
    val tracked: StateFlow<List<TrackedDevice>>
    val holderInfo: StateFlow<Map<HolderId, HolderInfo>>

    suspend fun preToolUse(input: HookInput): HookDecision
    suspend fun subagentStop(sessionId: String, agentId: String)
    suspend fun sessionEnd(sessionId: String)
    suspend fun acquire(
        holder: HolderId,
        cwd: String,
        /** Empty [Wants.categories] means the project's default category. */
        wants: Wants,
        timeout: Duration,
        onPosition: suspend (Int) -> Unit,
    ): AcquireResult

    /** Releases the [platform] hold (and a queue entry that platform could answer), or everything when null. */
    suspend fun release(holder: HolderId, platform: Platform? = null)

    /** Merged status: Blocked, then Queued, then the first hold, then the last release. */
    suspend fun status(holder: HolderId): HolderStatus

    /** Every live hold, at most one per platform. */
    suspend fun holdings(holder: HolderId): List<HolderStatus.Holding>

    // UI actions
    suspend fun releaseDevice(key: DeviceKey, block: Boolean)
    suspend fun handOff(key: DeviceKey, to: HolderId)
    suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean)
    suspend fun setCategory(key: DeviceKey, category: Category?)
    suspend fun moveInQueue(h: HolderId, index: Int)
    suspend fun removeFromQueue(h: HolderId)
    suspend fun unblock(h: HolderId)
}
