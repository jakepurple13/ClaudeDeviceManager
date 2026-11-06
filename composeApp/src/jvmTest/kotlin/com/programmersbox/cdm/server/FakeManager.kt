package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
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
    var acquireImpl: suspend (HolderId, String, Wants, Duration, suspend (Int) -> Unit) -> AcquireResult =
        { _, _, _, _, _ -> AcquireResult.Blocked }
    var statusImpl: (HolderId) -> HolderStatus = { HolderStatus.None }
    var holdingsImpl: (HolderId) -> List<HolderStatus.Holding> = { emptyList() }
    var lastWants: Wants? = null
    var lastTimeout: Duration? = null
    val released = mutableListOf<Pair<HolderId, Platform?>>()

    val snapshotFlow = MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap()))
    override val tracked: StateFlow<List<TrackedDevice>> = MutableStateFlow(emptyList())
    override val snapshot: StateFlow<AllocatorSnapshot> get() = snapshotFlow
    val holderInfoFlow = MutableStateFlow<Map<HolderId, HolderInfo>>(emptyMap())
    override val holderInfo: StateFlow<Map<HolderId, HolderInfo>> get() = holderInfoFlow

    override suspend fun preToolUse(input: HookInput): HookDecision { inputs += input; return decision }
    override suspend fun subagentStop(sessionId: String, agentId: String) { stops += sessionId to agentId }
    override suspend fun sessionEnd(sessionId: String) { ended += sessionId }
    override suspend fun acquire(holder: HolderId, cwd: String, wants: Wants, timeout: Duration, onPosition: suspend (Int) -> Unit): AcquireResult {
        lastWants = wants
        lastTimeout = timeout
        return acquireImpl(holder, cwd, wants, timeout, onPosition)
    }
    override suspend fun release(holder: HolderId, platform: Platform?) { released += holder to platform }
    val renewed = mutableListOf<Pair<HolderId, Platform?>>()
    var renewImpl: (HolderId, Platform?) -> Boolean = { _, _ -> false }
    override suspend fun renew(holder: HolderId, platform: Platform?): Boolean { renewed += holder to platform; return renewImpl(holder, platform) }
    override suspend fun status(holder: HolderId): HolderStatus = statusImpl(holder)
    override suspend fun holdings(holder: HolderId): List<HolderStatus.Holding> = holdingsImpl(holder)
    override suspend fun releaseDevice(key: DeviceKey, block: Boolean): Unit = error("unused")
    override suspend fun handOff(key: DeviceKey, to: HolderId): Unit = error("unused")
    override suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean): Unit = error("unused")
    override suspend fun setCategory(key: DeviceKey, category: Category?): Unit = error("unused")
    override suspend fun moveInQueue(h: HolderId, index: Int): Unit = error("unused")
    override suspend fun removeFromQueue(h: HolderId): Unit = error("unused")
    override suspend fun unblock(h: HolderId): Unit = error("unused")
}
