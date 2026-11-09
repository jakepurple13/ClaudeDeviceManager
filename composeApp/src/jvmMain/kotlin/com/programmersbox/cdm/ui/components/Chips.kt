package com.programmersbox.cdm.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.ui.virtualWord

private val ColorAnim = tween<Color>(200)

/** A device's state as the status dot shows it. Colours are the mockups'. */
enum class DotStatus(val color: Color) {
    Free(Color(0xFF5FCF8A)),
    InUse(Color(0xFFE0B84A)),
    DontUse(Color(0xFF777777)),
    Offline(Color(0xFFFFE39A)),
    /** Set aside for a queued device set. */
    Reserved(Color(0xFF8AB4F8)),
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
    Tablet(Color(0xFF23364A), Color(0xFFB8D8FF)),
    DontUse(Color(0xFF4A2A2A), Color(0xFFFFB4B4)),
    Offline(Color(0xFF4A4224), Color(0xFFFFE39A)),
    Reserved(Color(0xFF243552), Color(0xFFB8D0FF)),
}

fun chipStyleFor(category: Category): ChipStyle = when (category) {
    Category.PHONE -> ChipStyle.Phone
    Category.WATCH -> ChipStyle.Watch
    Category.TABLET -> ChipStyle.Tablet
    else -> ChipStyle.Neutral
}

@Composable
fun Chip(label: String, style: ChipStyle, modifier: Modifier = Modifier) {
    val container by animateColorAsState(style.container, ColorAnim)
    val content by animateColorAsState(style.content, ColorAnim)
    Text(
        text = label,
        color = content,
        maxLines = 1,
        softWrap = false,
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

private val DashColor = Color(0xFF6E7787)
private val DashedTextColor = Color(0xFFCFD6E4)

/** The mockups' dashed 1dp `#6E7787` outline on a pill. */
private fun Modifier.dashedPill(): Modifier = drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(
        DashColor,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(9.dp.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
    )
}

/** A small monitor: screen rectangle on a stand, stroked. */
@Composable
private fun MonitorIcon(modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val s = 1.dp.toPx()
        drawRoundRect(DashedTextColor, Offset(s / 2, s / 2), Size(size.width - s, size.height * 0.66f), CornerRadius(1.5.dp.toPx()), Stroke(s))
        drawLine(DashedTextColor, Offset(size.width / 2, size.height * 0.66f), Offset(size.width / 2, size.height - s / 2), s)
        drawLine(DashedTextColor, Offset(size.width * 0.28f, size.height - s / 2), Offset(size.width * 0.72f, size.height - s / 2), s)
    }
}

/** A dashed pill with a monitor icon, for virtual devices: `emulator` (Android) or `simulator` (iOS). */
@Composable
fun VirtualChip(platform: Platform, modifier: Modifier = Modifier) {
    Row(
        modifier.dashedPill().padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        MonitorIcon()
        Text(virtualWord(platform), color = DashedTextColor, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
    }
}

/** A queue card's `virtual only` limit: the dashed pill, without the icon. */
@Composable
fun DashedChip(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = DashedTextColor,
        maxLines = 1,
        softWrap = false,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        modifier = modifier.dashedPill().padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** `iOS` or `Android`, Neutral style. */
@Composable
fun PlatformChip(platform: Platform, modifier: Modifier = Modifier) {
    Chip(if (platform == Platform.IOS) "iOS" else "Android", ChipStyle.Neutral, modifier)
}
