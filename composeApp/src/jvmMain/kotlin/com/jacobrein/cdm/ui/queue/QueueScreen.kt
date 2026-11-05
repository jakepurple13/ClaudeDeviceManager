package com.jacobrein.cdm.ui.queue

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.components.Muted
import com.jacobrein.cdm.ui.components.QueueCard
import com.jacobrein.cdm.ui.components.SectionLabel
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The Queue list pane: drag the grip to reorder. */
@Composable
fun QueueScreen(selected: HolderId?, onOpen: (HolderId) -> Unit, vm: QueueViewModel = koinViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    // The list the user drags; reorderable wants it updated in onMove, before the view model's flow catches up.
    var items by remember { mutableStateOf(rows) }
    LaunchedEffect(rows) { items = rows }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }.mapIndexed { i, r -> r.copy(position = i + 1) }
        vm.onMove(from.index, to.index)
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SectionLabel("Waiting · drag to reorder")
        if (items.isEmpty()) Muted("Nobody is waiting for a device.")
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.holder.key }) { row ->
                ReorderableItem(reorder, key = row.holder.key) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, tween(150))
                    QueueCard(
                        row,
                        selected = row.holder == selected,
                        onClick = { onOpen(row.holder) },
                        modifier = Modifier.shadow(elevation, RoundedCornerShape(4.dp)),
                        leading = {
                            Text(
                                "⋮⋮",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.draggableHandle(),
                            )
                        },
                    )
                }
            }
        }
    }
}
