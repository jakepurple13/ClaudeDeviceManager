package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.devices.TrackedDevice
import com.programmersbox.cdm.service.AcquireResult
import com.programmersbox.cdm.service.DeviceManager
import com.programmersbox.cdm.service.HolderInfo
import com.programmersbox.cdm.service.HookDecision
import com.programmersbox.cdm.service.HookInput
import com.programmersbox.cdm.service.HookReply
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
    override suspend fun releaseDevice(key: DeviceKey, block: Boolean): Unit = error("unused")
    override suspend fun handOff(key: DeviceKey, to: HolderId): Unit = error("unused")
    override suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean): Unit = error("unused")
    override suspend fun setCategory(key: DeviceKey, category: Category?): Unit = error("unused")
    override suspend fun moveInQueue(h: HolderId, index: Int): Unit = error("unused")
    override suspend fun removeFromQueue(h: HolderId): Unit = error("unused")
    override suspend fun unblock(h: HolderId): Unit = error("unused")
}
