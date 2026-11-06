package com.programmersbox.cdm.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.ui.components.AnimatedLine
import com.programmersbox.cdm.ui.components.AnimatedRows
import com.programmersbox.cdm.ui.components.AnimatedValue
import com.programmersbox.cdm.ui.components.CardBox
import com.programmersbox.cdm.ui.components.Chip
import com.programmersbox.cdm.ui.components.ChipStyle
import com.programmersbox.cdm.ui.components.DONT_USE_PENDING
import com.programmersbox.cdm.ui.components.LogLine
import com.programmersbox.cdm.ui.components.Muted
import com.programmersbox.cdm.ui.components.SectionLabel
import com.programmersbox.cdm.ui.components.SmallButton
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The Devices detail pane: facts, category, holder card, recent commands, and the don't-use toggle. */
@Composable
fun DeviceDetailScreen(
    key: DeviceKey,
    vm: DeviceDetailViewModel = koinViewModel(key = "device-${key.hardwareSerial}") { parametersOf(key) },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                AnimatedLine(state.row?.dontUsePending == true) { Chip(DONT_USE_PENDING, ChipStyle.DontUse) }
            }
            AnimatedRows(state.info, key = { it.first }) { (label, value) ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Muted("$label:", Modifier.width(72.dp), maxLines = 1)
                    Text(value, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), maxLines = 1)
                }
            }
            CategoryPicker(state, vm::onCategorySelected, Modifier.padding(vertical = 8.dp))
            HolderSection(state.holder, vm::onRelease)
            SectionLabel("Recent commands", Modifier.padding(top = 6.dp))
            AnimatedLine(state.loaded && state.commands.isEmpty()) { Muted("No device commands yet") }
            AnimatedRows(state.commands, key = { it.id }) { LogLine(it) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = state.dontUse, onCheckedChange = vm::onDontUseToggled)
                Text("Don't use this device", style = MaterialTheme.typography.bodyMedium)
            }
        }
        DontUseDialog(
            visible = state.confirmDontUse,
            deviceName = state.name,
            holderLabel = state.holder?.label,
            onReleaseNow = { vm.onConfirmDontUse(releaseNow = true) },
            onAfterRelease = { vm.onConfirmDontUse(releaseNow = false) },
            onDismiss = vm::onDismissDontUse,
        )
    }
}

@Composable
private fun HolderSection(holder: HolderCard?, onRelease: (block: Boolean) -> Unit) {
    // Switches between held and free (and between holders); the ticking timing text alone doesn't re-animate.
    AnimatedContent(
        targetState = holder,
        contentKey = { it?.holder },
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
        label = "holder",
    ) { card ->
        CardBox(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            if (card == null) {
                SectionLabel("Holder")
                Muted("Free — nobody holds this device")
            } else {
                SectionLabel("Held by")
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(card.label.substringBefore(" #"), fontWeight = FontWeight.SemiBold)
                    Muted("#" + card.label.substringAfter(" #", ""))
                }
                Muted(listOf(card.cwd.ifBlank { null }, card.timing.ifBlank { null }).filterNotNull().joinToString(" · "), maxLines = 3)
                AnimatedValue(card.lastCommand) { Muted("last: $it", maxLines = 1) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallButton("Release", onClick = { onRelease(false) })
                    SmallButton("Release + block", onClick = { onRelease(true) }, danger = true)
                }
            }
        }
    }
}
