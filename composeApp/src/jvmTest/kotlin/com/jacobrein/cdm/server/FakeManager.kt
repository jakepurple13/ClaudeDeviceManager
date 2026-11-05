package com.jacobrein.cdm.server

import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.devices.TrackedDevice
import com.jacobrein.cdm.service.AcquireResult
import com.jacobrein.cdm.service.DeviceManager
import com.jacobrein.cdm.service.HolderInfo
import com.jacobrein.cdm.service.HookDecision
import com.jacobrein.cdm.service.HookInput
import com.jacobrein.cdm.service.HookReply
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Scriptable [DeviceManager] for server tests; unused UI actions throw. */
open class FakeManager : DeviceManager {
    var decision: HookDecision = HookDecision(HookReply.NoOpinion, null)
    val inputs = mutableListOf<HookInput>()
    val stops = mutableListOf<Pair<String, String>>()
    val ended = mutableListOf<String>()
    var acquireImpl: suspend (HolderId, String, Set<Category>, Duration, suspend (Int) -> Unit) -> AcquireResult =
        { _, _, _, _, _ -> AcquireResult.Blocked }
    var statusImpl: (HolderId) -> HolderStatus = { HolderStatus.None }
    var lastWants: Set<Category>? = null
    val released = mutableListOf<HolderId>()

    override val snapshot: StateFlow<AllocatorSnapshot> = MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap()))
    override val tracked: StateFlow<List<TrackedDevice>> = MutableStateFlow(emptyList())
    override val holderInfo: StateFlow<Map<HolderId, HolderInfo>> = MutableStateFlow(emptyMap())

    override suspend fun preToolUse(input: HookInput): HookDecision { inputs += input; return decision }
    override suspend fun subagentStop(sessionId: String, agentId: String) { stops += sessionId to agentId }
    override suspend fun sessionEnd(sessionId: String) { ended += sessionId }
    override suspend fun acquire(holder: HolderId, cwd: String, wants: Set<Category>, timeout: Duration, onPosition: suspend (Int) -> Unit): AcquireResult {
        lastWants = wants
        return acquireImpl(holder, cwd, wants, timeout, onPosition)
    }
    override suspend fun release(holder: HolderId) { released += holder }
    override suspend fun status(holder: HolderId): HolderStatus = statusImpl(holder)
    override suspend fun releaseDevice(key: DeviceKey, block: Boolean) = error("unused")
    override suspend fun handOff(key: DeviceKey, to: HolderId) = error("unused")
    override suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean) = error("unused")
    override suspend fun setCategory(key: DeviceKey, category: Category?) = error("unused")
    override suspend fun moveInQueue(h: HolderId, index: Int) = error("unused")
    override suspend fun removeFromQueue(h: HolderId) = error("unused")
    override suspend fun unblock(h: HolderId) = error("unused")
}
