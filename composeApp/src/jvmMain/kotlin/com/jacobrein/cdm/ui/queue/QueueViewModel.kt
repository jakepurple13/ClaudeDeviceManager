package com.jacobrein.cdm.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.service.DeviceManager
import com.jacobrein.cdm.ui.QueueRow
import com.jacobrein.cdm.ui.UiSources
import com.jacobrein.cdm.ui.queueRows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class QueueViewModel(private val manager: DeviceManager, sources: UiSources) : ViewModel() {
    /** The order after a drag, shown until the service's snapshot catches up (or the queue's members change). */
    private val pending = MutableStateFlow<List<HolderId>?>(null)

    init {
        manager.snapshot
            .onEach { s -> pending.update { p -> if (p == null || p == s.queue || p.toSet() != s.queue.toSet()) null else p } }
            .launchIn(viewModelScope)
    }

    val rows: StateFlow<List<QueueRow>> = combine(sources.world, pending) { w, p ->
        val rows = queueRows(w)
        if (p == null) {
            rows
        } else {
            val byHolder = rows.associateBy { it.holder }
            p.mapIndexedNotNull { i, h -> byHolder[h]?.copy(position = i + 1) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A drag step: moves the row at [from] to [to] at once and tells the service. */
    fun onMove(from: Int, to: Int) {
        val current = pending.value ?: manager.snapshot.value.queue
        if (from !in current.indices || to !in current.indices || from == to) return
        val holder = current[from]
        pending.value = current.toMutableList().apply { add(to, removeAt(from)) }
        viewModelScope.launch { manager.moveInQueue(holder, to) }
    }
}
