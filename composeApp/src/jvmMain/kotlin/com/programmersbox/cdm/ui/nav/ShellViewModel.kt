package com.programmersbox.cdm.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.service.DeviceManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Live counts for the window chrome: the title bar status and the Queue badge. */
data class ShellCounts(val inUse: Int, val waiting: Int)

fun shellCounts(snapshot: AllocatorSnapshot): ShellCounts = ShellCounts(snapshot.holds.size, snapshot.queue.size)

/** State for the shell around the screens (title bar and rail). */
class ShellViewModel(manager: DeviceManager) : ViewModel() {
    val counts: StateFlow<ShellCounts> = manager.snapshot
        .map(::shellCounts)
        .stateIn(viewModelScope, SharingStarted.Eagerly, shellCounts(manager.snapshot.value))
}
