package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.devices.TrackedDevice
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
    data class Granted(val serial: String, val name: String) : AcquireResult

    /** [position] is the 1-based queue position when waiting stopped; 0 if the holder was taken out of the queue. */
    data class TimedOut(val position: Int) : AcquireResult
    data object Blocked : AcquireResult
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
        wants: Set<Category>,
        timeout: Duration,
        onPosition: suspend (Int) -> Unit,
    ): AcquireResult
    suspend fun release(holder: HolderId)
    suspend fun status(holder: HolderId): HolderStatus

    // UI actions
    suspend fun releaseDevice(key: DeviceKey, block: Boolean)
    suspend fun handOff(key: DeviceKey, to: HolderId)
    suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean)
    suspend fun setCategory(key: DeviceKey, category: Category?)
    suspend fun moveInQueue(h: HolderId, index: Int)
    suspend fun removeFromQueue(h: HolderId)
    suspend fun unblock(h: HolderId)
}
