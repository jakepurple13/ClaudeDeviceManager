package com.programmersbox.cdm.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.cdm.ui.DeviceRow
import com.programmersbox.cdm.ui.UiSources
import com.programmersbox.cdm.ui.deviceRows
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class DevicesViewModel(sources: UiSources) : ViewModel() {
    val rows: StateFlow<List<DeviceRow>> = sources.world
        .map(::deviceRows)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
