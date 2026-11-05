package com.jacobrein.cdm.ui.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.WaitReason
import com.jacobrein.cdm.ui.components.CardBox
import com.jacobrein.cdm.ui.components.Muted
import com.jacobrein.cdm.ui.components.SectionLabel
import com.jacobrein.cdm.ui.components.SmallButton
import com.jacobrein.cdm.ui.components.clockTime
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The Queue detail pane: why the holder waits, and Move to top / Hand it <device> now / Remove from queue. */
@OptIn(ExperimentalLayoutApi::class)
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
        if (state.loaded && !state.inQueue) {
            Muted("No longer waiting.", Modifier.padding(top = 6.dp))
            return@Column
        }
        Muted(
            listOfNotNull(
                state.cwd.ifBlank { null },
                "wants: " + state.wants.joinToString(", ") { it.id }.ifBlank { "any" },
                state.queuedAt?.let { "queued ${clockTime(it)}" },
            ).joinToString(" · "),
        )
        CardBox(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
            SectionLabel("Why it's waiting")
            if (state.reasons.isEmpty()) Muted("No device of a category it wants is known yet.")
            state.reasons.forEach { r -> key(r.device) { ReasonLine(r) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallButton("Move to top", onClick = vm::onMoveToTop, enabled = state.position > 1)
            state.handOff.forEach { d -> SmallButton("Hand it ${d.name} now", onClick = { vm.onHandOff(d.key) }) }
            SmallButton("Remove from queue", danger = true, onClick = {
                vm.onRemove()
                onClose()
            })
        }
    }
}

/** A "why it's waiting" row; it expands in when it first appears. Don't-use rows are dimmed, as in the mockup. */
@Composable
private fun ReasonLine(r: WaitReason) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = fadeIn(tween(200)) + expandVertically(tween(200)), exit = fadeOut(tween(150)) + shrinkVertically(tween(150))) {
        Text(
            "${r.device} — ${r.reason}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 1.dp).alpha(if (r.reason == "marked don't use") 0.6f else 1f),
        )
    }
}
