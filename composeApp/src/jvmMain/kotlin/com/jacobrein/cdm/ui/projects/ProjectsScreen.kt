package com.jacobrein.cdm.ui.projects

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jacobrein.cdm.ui.components.AnimatedLine
import com.jacobrein.cdm.ui.components.CardBox
import com.jacobrein.cdm.ui.components.Chip
import com.jacobrein.cdm.ui.components.ChipStyle
import com.jacobrein.cdm.ui.components.Muted
import com.jacobrein.cdm.ui.components.SectionLabel
import org.koin.compose.viewmodel.koinViewModel

/** Known project folders and the device category their sessions get by default. */
@Composable
fun ProjectsScreen(vm: ProjectsViewModel = koinViewModel()) {
    val loaded by vm.rows.collectAsStateWithLifecycle()
    val rows = loaded.orEmpty()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SectionLabel("Projects · default category")
        Muted("A folder is added the first time a session in it checks in. `cdm acquire` without --category uses this.", Modifier.padding(bottom = 6.dp))
        AnimatedLine(loaded?.isEmpty() == true) { Muted("No projects yet.") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows, key = { it.path }) { row ->
                CardBox(Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(row.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(row.path, maxLines = 1)
                        }
                        ChoicePicker(row.choice) { vm.onSelect(row.path, it) }
                    }
                }
            }
        }
    }
}

private fun ProjectChoice.style() = when (this) {
    ProjectChoice.PHONE -> ChipStyle.Phone
    ProjectChoice.WATCH -> ChipStyle.Watch
    else -> ChipStyle.Neutral
}

@Composable
private fun ChoicePicker(current: ProjectChoice, onSelect: (ProjectChoice) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Crossfade(current, animationSpec = tween(200), label = "projectCategory") { c ->
            Chip("${c.label} ▾", c.style(), Modifier.clip(RoundedCornerShape(9.dp)).clickable { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ProjectChoice.entries.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c.label) },
                    onClick = {
                        open = false
                        if (c != current) onSelect(c)
                    },
                )
            }
        }
    }
}
