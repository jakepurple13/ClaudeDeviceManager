package com.jacobrein.cdm.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.QueueRow
import com.jacobrein.cdm.ui.UiActions
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

class QueueViewModel(private val actions: UiActions, sources: UiSources) : ViewModel() {
    /**
     * The order the UI shows when it differs from the service's: during a drag, and after it until the snapshot
     * catches up (or the queue's members change). The single source for the rendered order.
     */
    private val shown = MutableStateFlow<List<HolderId>?>(null)

    /** The holder being dragged; set by the first step of a drag, cleared when it ends. */
    private var dragged: HolderId? = null

    init {
        actions.manager.snapshot
            .onEach { s ->
                if (dragged == null) shown.update { p -> if (p == null || p == s.queue || p.toSet() != s.queue.toSet()) null else p }
            }
            .launchIn(viewModelScope)
    }

    val rows: StateFlow<List<QueueRow>> = combine(sources.world, shown) { w, p ->
        val rows = queueRows(w)
        if (p == null) {
            rows
        } else {
            val byHolder = rows.associateBy { it.holder }
            p.mapIndexedNotNull { i, h -> byHolder[h]?.copy(position = i + 1) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** A drag step: [from] moves to where [to] is in the order on screen. Nothing reaches the service yet. */
    fun onMove(from: HolderId, to: HolderId) {
        val current = shown.value ?: actions.manager.snapshot.value.queue
        val i = current.indexOf(from)
        val j = current.indexOf(to)
        if (i < 0 || j < 0 || i == j) return
        dragged = from
        shown.value = current.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** The drag ended: one service call puts the dragged holder where it was dropped. */
    fun onDragEnd() {
        val holder = dragged ?: return
        dragged = null
        val index = shown.value?.indexOf(holder)?.takeIf { it >= 0 } ?: return
        actions.run { moveInQueue(holder, index) }
    }
}
