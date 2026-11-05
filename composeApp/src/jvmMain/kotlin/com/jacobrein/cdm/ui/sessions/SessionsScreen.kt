package com.jacobrein.cdm.ui.sessions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.components.AnimatedLine
import com.jacobrein.cdm.ui.components.AnimatedRows
import com.jacobrein.cdm.ui.components.AnimatedValue
import com.jacobrein.cdm.ui.components.CardBox
import com.jacobrein.cdm.ui.components.Chip
import com.jacobrein.cdm.ui.components.ChipStyle
import com.jacobrein.cdm.ui.components.Muted
import com.jacobrein.cdm.ui.components.SectionLabel
import com.jacobrein.cdm.ui.components.SmallButton
import org.koin.compose.viewmodel.koinViewModel

/** Every session seen in the last hour, with its main agent and subagents. */
@Composable
fun SessionsScreen(vm: SessionsViewModel = koinViewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val groups = state.groups.orEmpty()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SectionLabel("Sessions · last hour")
        AnimatedLine(state.groups?.isEmpty() == true) {
            Muted("No sessions yet. They appear here when a Claude Code session runs a device command or `cdm`.")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(groups, key = { it.sessionId }) { g ->
                SessionCard(
                    g,
                    expanded = g.sessionId !in state.collapsed,
                    onToggle = { vm.onToggle(g.sessionId) },
                    onUnblock = vm::onUnblock,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun SessionCard(g: SessionGroup, expanded: Boolean, onToggle: () -> Unit, onUnblock: (HolderId) -> Unit, modifier: Modifier = Modifier) {
    val dim by animateFloatAsState(if (g.ended) 0.6f else 1f, tween(250))
    val agents = g.agents
    CardBox(modifier.alpha(dim)) {
        Row(
            Modifier.fillMaxWidth().let { if (agents.isNotEmpty()) it.clickable(onClick = onToggle) else it },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val turn by animateFloatAsState(if (expanded) 90f else 0f, tween(200))
            AnimatedVisibility(agents.isNotEmpty(), enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Text("▸", modifier = Modifier.rotate(turn), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(g.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedVisibility(g.ended, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) { Chip("ended", ChipStyle.Neutral) }
            AnimatedVisibility(agents.isNotEmpty() && !expanded, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Muted("${agents.size} subagent${if (agents.size == 1) "" else "s"}")
            }
        }
        AnimatedValue(g.cwd) { Muted(it, Modifier.padding(top = 1.dp), maxLines = 1) }
        g.main?.let { HolderLine(it, indent = false, onUnblock) }
        AnimatedVisibility(
            expanded,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column { AnimatedRows(agents, key = { it.holder.key }) { HolderLine(it, indent = true, onUnblock) } }
        }
    }
}

private fun HolderState.chip(): Pair<String, ChipStyle> = when (this) {
    HolderState.HOLDING -> "holding" to ChipStyle.Phone
    HolderState.WAITING -> "waiting" to ChipStyle.Offline
    HolderState.IDLE -> "idle" to ChipStyle.Neutral
    HolderState.ENDED -> "ended" to ChipStyle.Neutral
    HolderState.BLOCKED -> "blocked" to ChipStyle.DontUse
}

@Composable
private fun HolderLine(row: SessionHolderRow, indent: Boolean, onUnblock: (HolderId) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = if (indent) 18.dp else 0.dp, top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (indent) Muted("└")
            Text(row.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (indent) FontWeight.Normal else FontWeight.Medium)
            Crossfade(row.state, animationSpec = tween(200), label = "holderState") { s ->
                val (label, style) = s.chip()
                Chip(label, style)
            }
            Muted(row.detail, Modifier.weight(1f, fill = false), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            AnimatedVisibility(row.state == HolderState.BLOCKED, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                SmallButton("Unblock", onClick = { onUnblock(row.holder) })
            }
        }
        AnimatedValue(row.lastCommand) { Muted("last: $it", Modifier.padding(start = if (indent) 14.dp else 0.dp, top = 1.dp), maxLines = 1) }
    }
}
