package com.programmersbox.cdm.ui.queue

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.ui.WaitReason
import com.programmersbox.cdm.ui.components.AnimatedLine
import com.programmersbox.cdm.ui.components.AnimatedRows
import com.programmersbox.cdm.ui.components.CardBox
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.SectionLabel
import com.programmersbox.cdm.ui.components.SmallButton
import com.programmersbox.cdm.ui.components.clockTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The Queue detail pane: why the holder waits, and Move to top / Hand it <device> now / Remove from queue. */
@Composable
fun QueueDetailScreen(
    holder: HolderId,
    onClose: () -> Unit,
    vm: QueueDetailViewModel = koinViewModel(key = "holder-${holder.key}") { parametersOf(holder) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.label.substringBefore(" #"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Muted("#" + state.label.substringAfter(" #", ""))
        }
        // Waiting and "no longer waiting" cross-fade; before the first load it counts as waiting (no flash).
        AnimatedContent(
            targetState = !state.loaded || state.inQueue,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "waiting",
        ) { waiting ->
            if (!waiting) {
                Muted("No longer waiting.", Modifier.padding(top = 6.dp))
            } else {
                Waiting(state, vm, onClose)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Waiting(state: QueueDetailState, vm: QueueDetailViewModel, onClose: () -> Unit) {
    Column {
        Muted(
            listOfNotNull(
                state.cwd.ifBlank { null },
                "wants: " + state.wants.joinToString(", ") { it.id }.ifBlank { "any" },
                state.queuedAt?.let { "queued ${clockTime(it)}" },
            ).joinToString(" · "),
        )
        CardBox(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            SectionLabel("Why it's waiting")
            AnimatedLine(state.loaded && state.reasons.isEmpty()) { Muted("No device of a category it wants is known yet.") }
            AnimatedRows(state.reasons, key = { it.device }) { ReasonLine(it) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Move to top", onClick = vm::onMoveToTop, enabled = state.position > 1)
            state.handOff.forEach { d -> SmallButton("Hand it ${d.name} now", onClick = { vm.onHandOff(d.key) }) }
            SmallButton("Remove from queue", danger = true, onClick = {
                vm.onRemove() // runs on the app scope, so closing the pane right away can't cancel it
                onClose()
            })
        }
    }
}

/** A "why it's waiting" row; don't-use rows are dimmed, as in the mockup. */
@Composable
private fun ReasonLine(r: WaitReason) {
    Text(
        "${r.device} — ${r.reason}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 1.dp).alpha(if (r.reason == "marked don't use") 0.6f else 1f),
    )
}
