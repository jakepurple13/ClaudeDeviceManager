package com.programmersbox.cdm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.programmersbox.cdm.ui.ActivityRow
import com.programmersbox.cdm.ui.DeviceRow
import com.programmersbox.cdm.ui.DeviceState
import com.programmersbox.cdm.ui.QueueRow
import com.programmersbox.cdm.ui.short
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** Chip text for a device marked don't use while held: it goes out of rotation when its holder releases it. */
const val DONT_USE_PENDING = "don't use · after release"

private val CardShape = RoundedCornerShape(6.dp)
private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Wall-clock "13:27" in the local zone. */
fun clockTime(at: Instant): String = ClockFormat.format(at.toJavaInstant().atZone(ZoneId.systemDefault()))

/** The mockups' small all-caps section heading. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 4.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.6.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A rounded card; [selected] draws the accent outline (animated). */
@Composable
fun CardBox(modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val outline by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(200))
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, outline, CardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(8.dp),
    ) { content() }
}

/** A small outlined text button, as in the mockups. */
@Composable
fun SmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, enabled: Boolean = true) {
    val border = if (danger) Color(0xFF7A3A3A) else MaterialTheme.colorScheme.outline
    val content = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Text(
        text,
        color = content,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

fun DeviceState.dot(): DotStatus = when (this) {
    DeviceState.FREE -> DotStatus.Free
    DeviceState.IN_USE -> DotStatus.InUse
    DeviceState.OFFLINE_HELD -> DotStatus.Offline
    DeviceState.DONT_USE, DeviceState.OFFLINE -> DotStatus.DontUse
}

/**
 * One device. [detailed] adds how long it's been held and its timer ("gabb-messenger #a41f · 12m · idle 1m"),
 * as on Overview; the Devices list shows just the holder.
 */
@Composable
fun DeviceCard(row: DeviceRow, detailed: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dim by animateFloatAsState(if (row.state == DeviceState.DONT_USE || row.state == DeviceState.OFFLINE) 0.55f else 1f, tween(250))
    CardBox(modifier.alpha(dim), selected = selected, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(row.state.dot())
            Text(row.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (row.state) {
                DeviceState.DONT_USE -> Chip("don't use", ChipStyle.DontUse)
                DeviceState.OFFLINE_HELD -> {
                    CategoryChip(row.category)
                    Chip("offline · held", ChipStyle.Offline)
                }
                DeviceState.OFFLINE -> Chip("offline", ChipStyle.Offline)
                else -> CategoryChip(row.category)
            }
            AnimatedVisibility(row.dontUsePending, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                Chip(DONT_USE_PENDING, ChipStyle.DontUse)
            }
        }
        val line = when {
            row.holderLabel == null && row.state == DeviceState.FREE -> "free"
            row.holderLabel == null -> null
            detailed -> listOfNotNull(row.holderLabel, row.heldFor?.let(::short), row.timerLabel).joinToString(" · ")
            else -> row.holderLabel
        }
        if (line != null) Muted(line, Modifier.padding(top = 2.dp))
    }
}

/** A queue row: accent bar on the left, position, holder, wanted categories, time waited. */
@Composable
fun QueueCard(
    row: QueueRow,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit = {},
) {
    val outline by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(200))
    val bar = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, outline, shape)
            .drawBehind { drawLine(bar, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx()) }
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            leading()
            AnimatedCount(row.position, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            Text(row.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            row.wants.forEach { CategoryChip(it) }
        }
        Muted("waiting ${short(row.waited)}", Modifier.padding(top = 2.dp))
    }
}

/** One activity or command line: "13:27  gabb-maps queued (watch)". */
@Composable
fun LogLine(row: ActivityRow, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 3.dp)) {
            Text(clockTime(row.at), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(row.text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
