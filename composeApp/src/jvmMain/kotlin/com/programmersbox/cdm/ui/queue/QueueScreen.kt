package com.programmersbox.cdm.ui.queue

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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.ui.components.AnimatedLine
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.QueueCard
import com.programmersbox.cdm.ui.components.SectionLabel
import org.koin.compose.viewmodel.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The Queue list pane: drag the grip to reorder. */
@Composable
fun QueueScreen(selected: HolderId?, onOpen: (HolderId) -> Unit, vm: QueueViewModel = koinViewModel()) {
    // The view model owns the rendered order (one source); reorderable waits for the layout to catch up after onMove.
    val loaded by vm.rows.collectAsStateWithLifecycle()
    val rows = loaded.orEmpty()
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        vm.onMove(HolderId.parse(from.key as String), HolderId.parse(to.key as String))
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SectionLabel("Waiting · drag to reorder")
        AnimatedLine(loaded?.isEmpty() == true) { Muted("Nobody is waiting for a device.") }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows, key = { it.holder.key }) { row ->
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
                                // One service call per drag, when it ends.
                                modifier = Modifier.draggableHandle(onDragStopped = vm::onDragEnd),
                            )
                        },
                    )
                }
            }
        }
    }
}
