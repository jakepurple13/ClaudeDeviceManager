package com.jacobrein.cdm.ui.devices

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.ui.components.Chip
import com.jacobrein.cdm.ui.components.Muted
import com.jacobrein.cdm.ui.components.SmallButton
import com.jacobrein.cdm.ui.components.chipStyleFor

/** "Category: [phone ▾] (auto-detected)", with Auto / Phone / Watch in the menu. */
@Composable
internal fun CategoryPicker(state: DeviceDetailState, onSelect: (Category?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val effective = state.categoryOverride ?: state.row?.category ?: state.detectedCategory ?: Category.PHONE
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Category:", style = MaterialTheme.typography.bodyMedium)
        Box {
            Chip("${effective.id} ▾", chipStyleFor(effective), Modifier.clip(RoundedCornerShape(9.dp)).clickable { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                val auto = state.detectedCategory?.let { "Auto (${it.id})" } ?: "Auto"
                listOf<Pair<String, Category?>>(auto to null, "Phone" to Category.PHONE, "Watch" to Category.WATCH).forEach { (label, c) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSelect(c)
                        },
                    )
                }
            }
        }
        Muted(if (state.categoryOverride != null) "(overridden)" else "(auto-detected)")
    }
}

/** Asked when don't-use is switched on while someone holds the device. */
@Composable
internal fun DontUseDialog(
    visible: Boolean,
    deviceName: String,
    holderLabel: String?,
    onReleaseNow: () -> Unit,
    onAfterRelease: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .animateEnterExit(enter = scaleIn(tween(180), initialScale = 0.94f), exit = scaleOut(tween(180), targetScale = 0.94f))
                    .widthIn(max = 380.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Stop using $deviceName?", fontWeight = FontWeight.SemiBold)
                Muted("${holderLabel ?: "A holder"} is using it. Release it now, or let them finish first?", maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SmallButton("Release now", onClick = onReleaseNow, danger = true)
                    SmallButton("After current holder releases", onClick = onAfterRelease)
                    SmallButton("Cancel", onClick = onDismiss)
                }
            }
        }
    }
}
