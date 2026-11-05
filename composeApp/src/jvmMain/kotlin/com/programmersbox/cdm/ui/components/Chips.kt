package com.programmersbox.cdm.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.cdm.core.model.Category

private val ColorAnim = tween<Color>(200)

/** A device's state as the status dot shows it. Colours are the mockups'. */
enum class DotStatus(val color: Color) {
    Free(Color(0xFF5FCF8A)),
    InUse(Color(0xFFE0B84A)),
    DontUse(Color(0xFF777777)),
    Offline(Color(0xFFFFE39A)),
}

@Composable
fun StatusDot(status: DotStatus, modifier: Modifier = Modifier) {
    val color by animateColorAsState(status.color, ColorAnim)
    Box(modifier.size(9.dp).background(color, CircleShape))
}

/** Chip colour pairs from the mockups (dark), keyed by what the chip says. */
enum class ChipStyle(val container: Color, val content: Color) {
    Neutral(Color(0xFF333A46), Color(0xFFCFD6E4)),
    Phone(Color(0xFF23403A), Color(0xFFB6F0DC)),
    Watch(Color(0xFF3B2F52), Color(0xFFD7C4FF)),
    DontUse(Color(0xFF4A2A2A), Color(0xFFFFB4B4)),
    Offline(Color(0xFF4A4224), Color(0xFFFFE39A)),
}

fun chipStyleFor(category: Category): ChipStyle = when (category) {
    Category.PHONE -> ChipStyle.Phone
    Category.WATCH -> ChipStyle.Watch
    else -> ChipStyle.Neutral
}

@Composable
fun Chip(label: String, style: ChipStyle, modifier: Modifier = Modifier) {
    val container by animateColorAsState(style.container, ColorAnim)
    val content by animateColorAsState(style.content, ColorAnim)
    Text(
        text = label,
        color = content,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        modifier = modifier
            .background(container, RoundedCornerShape(9.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun CategoryChip(category: Category, modifier: Modifier = Modifier) {
    Chip(category.id, chipStyleFor(category), modifier)
}
