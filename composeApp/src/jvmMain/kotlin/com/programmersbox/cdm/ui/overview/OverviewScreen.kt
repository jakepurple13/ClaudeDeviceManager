package com.programmersbox.cdm.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.ui.components.AnimatedCount
import com.programmersbox.cdm.ui.components.DeviceCard
import com.programmersbox.cdm.ui.components.LogLine
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.QueueCard
import com.programmersbox.cdm.ui.components.SectionLabel
import org.koin.compose.viewmodel.koinViewModel

/** Count tiles, then the device list beside Up next and Activity. */
@Composable
fun OverviewScreen(
    onOpenDevice: (DeviceKey) -> Unit,
    onOpenHolder: (HolderId) -> Unit,
    vm: OverviewViewModel = koinViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tile(state.counts.free, "free")
            Tile(state.counts.inUse, "in use")
            Tile(state.counts.dontUse, "don't use")
            Tile(state.counts.waiting, "waiting")
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1.2f)) {
                SectionLabel("Devices")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.devices.isEmpty()) item(key = "no-devices") { Muted("No devices connected", Modifier.animateItem()) }
                    items(state.devices, key = { it.key.hardwareSerial }) { row ->
                        DeviceCard(row, detailed = true, selected = false, onClick = { onOpenDevice(row.key) }, modifier = Modifier.animateItem())
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "up-next") { SectionLabel("Up next", Modifier.animateItem()) }
                if (state.upNext.isEmpty()) item(key = "nobody") { Muted("Nobody waiting", Modifier.animateItem()) }
                items(state.upNext, key = { "q-${it.holder.key}" }) { row ->
                    QueueCard(row, selected = false, onClick = { onOpenHolder(row.holder) }, modifier = Modifier.animateItem())
                }
                item(key = "activity") { SectionLabel("Activity", Modifier.padding(top = 12.dp).animateItem()) }
                if (state.activity.isEmpty()) item(key = "quiet") { Muted("Nothing yet", Modifier.animateItem()) }
                items(state.activity, key = { "a-${it.id}" }) { LogLine(it, Modifier.animateItem()) }
            }
        }
    }
}

@Composable
private fun RowScope.Tile(value: Int, label: String) {
    Column(
        Modifier.weight(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(8.dp),
    ) {
        AnimatedCount(value, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
        Muted(label)
    }
}
