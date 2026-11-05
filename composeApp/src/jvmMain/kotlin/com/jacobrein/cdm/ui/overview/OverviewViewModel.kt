package com.jacobrein.cdm.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.ui.ActivityRow
import com.jacobrein.cdm.ui.DeviceRow
import com.jacobrein.cdm.ui.OverviewCounts
import com.jacobrein.cdm.ui.QueueRow
import com.jacobrein.cdm.ui.UiSources
import com.jacobrein.cdm.ui.activityRows
import com.jacobrein.cdm.ui.deviceRows
import com.jacobrein.cdm.ui.overviewCounts
import com.jacobrein.cdm.ui.queueRows
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class OverviewState(
    val counts: OverviewCounts = OverviewCounts(0, 0, 0, 0),
    val devices: List<DeviceRow> = emptyList(),
    val upNext: List<QueueRow> = emptyList(),
    val activity: List<ActivityRow> = emptyList(),
)

class OverviewViewModel(sources: UiSources) : ViewModel() {
    val uiState: StateFlow<OverviewState> = sources.world
        .map { w ->
            val devices = deviceRows(w)
            OverviewState(
                counts = overviewCounts(devices, w.snapshot.queue.size),
                devices = devices,
                upNext = queueRows(w).take(UP_NEXT),
                activity = activityRows(w, w.activity.filter { it.kind != "command" }.take(ACTIVITY)),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverviewState())

    private companion object {
        const val UP_NEXT = 3
        const val ACTIVITY = 20
    }
}
