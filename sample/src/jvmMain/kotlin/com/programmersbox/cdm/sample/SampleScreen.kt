package com.programmersbox.cdm.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.window.TitleBarScope

@Composable
fun SampleScreen(state: SampleState) {
    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            DevicesPane(state, Modifier.weight(1f))
            VerticalDivider()
            HoldPane(state, Modifier.weight(1f))
        }
    }
}

/** Title bar: are we connected to CDM, and who are we. */
@Composable
fun TitleBarScope.ConnectionBar(state: SampleState) {
    val (color, text) = when (state.connected) {
        null -> MaterialTheme.colorScheme.outline to "Looking for Claude Device Manager…"
        true -> Color(0xFF2E9E5B) to "Connected"
        false -> MaterialTheme.colorScheme.error to "Claude Device Manager is not running"
    }
    val dot by animateColorAsState(color)
    Row(
        Modifier.align(Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.size(10.dp).background(dot, CircleShape))
        Text(text, color = MaterialTheme.colorScheme.onSurface)
    }
    Text(
        "Holder ID: ${state.client.holder}",
        Modifier.align(Alignment.End).padding(end = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Left side: every device and who holds it, updated live from GET /cdm/events. */
@Composable
private fun DevicesPane(state: SampleState, modifier: Modifier) {
    val canAsk = state.connected == true && state.hold == null && state.waiting == null
    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item { SectionTitle("Devices", "live from GET /cdm/events") }
        items(state.devices.devices, key = { it.serial }) { device ->
            DeviceRow(
                device = device,
                isMine = device.holder?.id == state.client.holder,
                // Ask for this exact device with acquire(device = serial).
                onAsk = if (canAsk && device.holder == null && !device.dontUse) {
                    { state.acquire(category = "any", platform = null, virtual = null, device = device.serial) }
                } else null,
                modifier = Modifier.animateItem(),
            )
        }
        if (state.devices.queue.isNotEmpty()) {
            item { Spacer(Modifier.size(16.dp)); SectionTitle("Waiting in line", null) }
            items(state.devices.queue, key = { "queue-" + it.holder.id }) { waiting ->
                Text("${waiting.position}. ${waiting.holder.label}", Modifier.animateItem().padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, isMine: Boolean, onAsk: (() -> Unit)?, modifier: Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.name, fontWeight = FontWeight.Medium)
            val kind = if (device.virtual) "virtual" else "real"
            Text(
                "${device.platform} · ${device.category} · $kind · ${device.serial}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val label = when {
            isMine -> "Mine"
            device.holder != null -> "Held by ${device.holder.label}"
            device.dontUse -> "Don't use"
            !device.online -> "Offline"
            else -> "Free"
        }
        AnimatedContent(label) { Pill(it, highlighted = it == "Mine") }
        if (onAsk != null) TextButton(onClick = onAsk) { Text("Ask for this one") }
    }
}

/** Right side: what we hold, the acquire filters, the buttons, and the wire log. */
@Composable
private fun HoldPane(state: SampleState, modifier: Modifier) {
    var category by remember { mutableStateOf("phone") }
    var platform by remember { mutableStateOf<String?>("android") }
    var virtual by remember { mutableStateOf<Boolean?>(null) }
    val connected = state.connected == true

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("My hold", null)
        HoldCard(state)

        ChoiceRow("Category", listOf("phone", "watch", "tablet", "any"), category, { it }) { category = it }
        ChoiceRow("Platform", listOf("android", "ios", null), platform, { it ?: "either" }) { platform = it }
        ChoiceRow("Kind", listOf(false, true, null), virtual, {
            when (it) { false -> "real"; true -> "virtual"; null -> "either" }
        }) { virtual = it }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.waiting == null) {
                Button(onClick = { state.acquire(category, platform, virtual) }, enabled = connected && state.hold == null) {
                    Text("Acquire")
                }
            } else {
                Button(onClick = state::cancel) { Text("Cancel") }
            }
            OutlinedButton(onClick = { state.renew() }, enabled = connected && state.hold != null) { Text("Renew") }
            OutlinedButton(onClick = { state.status() }, enabled = connected) { Text("Status") }
            OutlinedButton(onClick = { state.release() }, enabled = connected && state.hold != null) { Text("Release") }
        }

        AnimatedVisibility(state.problem != null) {
            Text(state.problem.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        SectionTitle("Log", "→ sent   ← received")
        WireLog(state.log, Modifier.weight(1f))
    }
}

@Composable
private fun HoldCard(state: SampleState) {
    val hold = state.hold
    val waiting = state.waiting
    Card(Modifier.fillMaxWidth()) {
        AnimatedContent(Triple(hold, waiting, state.connected)) { (hold, waiting, connected) ->
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                when {
                    hold != null -> {
                        Text("Holding ${hold.name}", style = MaterialTheme.typography.titleMedium)
                        // This is the device to use. Always name it: never run adb without -s.
                        val use = if (hold.platform == "ios") "xcrun simctl … ${hold.serial}" else "adb -s ${hold.serial} …"
                        SelectionContainer { Text(use, fontFamily = FontFamily.Monospace) }
                        Text("Renews every 60s while held", style = MaterialTheme.typography.bodySmall)
                    }
                    waiting != null -> Text(waiting, style = MaterialTheme.typography.titleMedium)
                    connected == false -> {
                        Text("Not connected. Without CDM, the app just runs without sharing.")
                        TextButton(onClick = state::connect) { Text("Try again") }
                    }
                    else -> Text("Not holding a device", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** The protocol lines, newest at the bottom. */
@Composable
private fun WireLog(lines: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
    SelectionContainer(modifier) {
        LazyColumn(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
            state = listState,
        ) {
            items(lines) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.startsWith("→")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.padding(end = 4.dp).size(width = 72.dp, height = 20.dp), style = MaterialTheme.typography.labelLarge)
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(name(option)) })
        }
    }
}

@Composable
private fun SectionTitle(title: String, hint: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun Pill(text: String, highlighted: Boolean) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
