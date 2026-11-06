package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import kotlin.time.Instant

data class AllocDevice(
    val key: DeviceKey,
    val name: String,
    val category: Category,
    val dontUse: Boolean,
    val connectedSerials: List<String>,
    val platform: Platform = Platform.ANDROID,
    val virtual: Boolean = false,
    val partner: DeviceKey? = null,
)

/** What a holder asks for: any of [categories], optionally limited to one [platform] and/or real or virtual devices. */
data class Wants(val categories: Set<Category>, val platform: Platform? = null, val virtual: Boolean? = null) {
    fun fits(d: AllocDevice): Boolean =
        (Category.ANY in categories || d.category in categories) &&
            (platform == null || d.platform == platform) &&
            (virtual == null || d.virtual == virtual)

    companion object {
        val ANY = Wants(setOf(Category.ANY))
    }
}

enum class ReleaseReason {
    IDLE_TIMEOUT, EDIT_TIMEOUT, SELF_RELEASE, USER_RELEASE, USER_HANDOFF, DONT_USE, SUBAGENT_STOP, SESSION_END,
}

sealed interface HolderStatus {
    data object None : HolderStatus
    data class Holding(
        val device: DeviceKey, val since: Instant, val lastActivity: Instant, val editing: Boolean,
        val platform: Platform = Platform.ANDROID,
    ) : HolderStatus

    /** [position] is 1-based. */
    data class Queued(val position: Int, val wants: Wants) : HolderStatus
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
    data class Queued(override val at: Instant, val holder: HolderId, val wants: Wants) : AllocatorEvent
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
