package com.jacobrein.cdm.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.DeviceRef
import com.jacobrein.cdm.ui.UiActions
import com.jacobrein.cdm.ui.UiSources
import com.jacobrein.cdm.ui.WaitReason
import com.jacobrein.cdm.ui.handOffTargets
import com.jacobrein.cdm.ui.labelOf
import com.jacobrein.cdm.ui.queueRows
import com.jacobrein.cdm.ui.queuedAt
import com.jacobrein.cdm.ui.waitReasons
import kotlin.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class QueueDetailState(
    val holder: HolderId,
    val loaded: Boolean = false,
    val inQueue: Boolean = false,
    val label: String = holder.key,
    val cwd: String = "",
    val position: Int = 0,
    val wants: Set<Category> = emptySet(),
    val queuedAt: Instant? = null,
    val reasons: List<WaitReason> = emptyList(),
    val handOff: List<DeviceRef> = emptyList(),
)

class QueueDetailViewModel(
    private val holder: HolderId,
    private val actions: UiActions,
    sources: UiSources,
) : ViewModel() {
    val uiState: StateFlow<QueueDetailState> = sources.world
        .map { w ->
            val row = queueRows(w).firstOrNull { it.holder == holder }
            QueueDetailState(
                holder = holder,
                loaded = true,
                inQueue = row != null,
                label = w.labelOf(holder),
                cwd = w.info[holder]?.cwd.orEmpty(),
                position = row?.position ?: 0,
                wants = row?.wants.orEmpty(),
                queuedAt = w.queuedAt(holder),
                reasons = if (row != null) waitReasons(holder, w) else emptyList(),
                handOff = if (row != null) handOffTargets(holder, w) else emptyList(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueDetailState(holder))

    fun onMoveToTop() = actions.run { moveInQueue(holder, 0) }

    fun onHandOff(device: DeviceKey) = actions.run { handOff(device, holder) }

    fun onRemove() = actions.run { removeFromQueue(holder) }
}
