package com.jacobrein.cdm.core.allocator

import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import kotlin.time.Instant

data class AllocDevice(
    val key: DeviceKey,
    val name: String,
    val category: Category,
    val dontUse: Boolean,
    val connectedSerials: List<String>,
)

enum class ReleaseReason {
    IDLE_TIMEOUT, EDIT_TIMEOUT, SELF_RELEASE, USER_RELEASE, USER_HANDOFF, DONT_USE, SUBAGENT_STOP, SESSION_END,
}

sealed interface HolderStatus {
    data object None : HolderStatus
    data class Holding(val device: DeviceKey, val since: Instant, val lastActivity: Instant, val editing: Boolean) : HolderStatus

    /** [position] is 1-based. */
    data class Queued(val position: Int, val wants: Set<Category>) : HolderStatus
    data class Released(val reason: ReleaseReason, val lastDevice: DeviceKey) : HolderStatus
    data object Blocked : HolderStatus
}

sealed interface GrabResult {
    data class Granted(val device: AllocDevice, val serial: String) : GrabResult
    data class HeldByOther(val device: AllocDevice, val holder: HolderId) : GrabResult
    data object NoneFree : GrabResult
    data object Blocked : GrabResult
    data object DontUse : GrabResult
}

sealed interface AllocatorEvent {
    val at: Instant

    data class Granted(override val at: Instant, val holder: HolderId, val device: DeviceKey) : AllocatorEvent
    data class Released(override val at: Instant, val holder: HolderId, val device: DeviceKey, val reason: ReleaseReason) : AllocatorEvent
    data class Queued(override val at: Instant, val holder: HolderId, val wants: Set<Category>) : AllocatorEvent
    data class LeftQueue(override val at: Instant, val holder: HolderId) : AllocatorEvent
    data class Blocked(override val at: Instant, val holder: HolderId) : AllocatorEvent
    data class Unblocked(override val at: Instant, val holder: HolderId) : AllocatorEvent
}

data class AllocatorSnapshot(
    val devices: List<AllocDevice>,
    val holds: Map<DeviceKey, HolderId>,
    val queue: List<HolderId>,
    val statuses: Map<HolderId, HolderStatus>,
)
