package com.programmersbox.cdm.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.ui.components.DeviceCard
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.SectionLabel
import org.koin.compose.viewmodel.koinViewModel

/** The Devices list pane. */
@Composable
fun DevicesScreen(selected: DeviceKey?, onOpen: (DeviceKey) -> Unit, vm: DevicesViewModel = koinViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        SectionLabel("Devices · ${rows.size}")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rows.isEmpty()) item(key = "none") { Muted("No devices connected. Plug one in or connect over Wi-Fi.", Modifier.animateItem()) }
            items(rows, key = { it.key.hardwareSerial }) { row ->
                DeviceCard(row, detailed = false, selected = row.key == selected, onClick = { onOpen(row.key) }, modifier = Modifier.animateItem())
            }
        }
    }
}
