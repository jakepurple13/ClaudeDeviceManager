package com.programmersbox.cdm.ui.lifecycle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class PillIcon { Spinner, Check, Problem }

private data class PillContent(val icon: PillIcon, val text: String, val count: String)

private fun pillContent(ui: LifecycleUi): PillContent? {
    val shutdown = ui.phase.isShutdown
    return when (ui.phase) {
        LifecyclePhase.Idle -> null
        LifecyclePhase.Starting, LifecyclePhase.Stopping -> {
            // Between steps nothing is running: keep showing the step that just finished instead of flickering.
            val i = ui.current?.let(ui.rows::indexOf) ?: (ui.finishedCount - 1)
            val row = ui.rows.getOrNull(i)
            PillContent(
                PillIcon.Spinner,
                row?.step?.verb(shutdown) ?: if (shutdown) "Cleaning up" else "Setting up",
                "${i.coerceAtLeast(0) + 1}/${ui.rows.size}",
            )
        }
        LifecyclePhase.Ready -> PillContent(PillIcon.Check, "Connected", "${ui.deviceCount} ${if (ui.deviceCount == 1) "device" else "devices"}")
        LifecyclePhase.StartFailed -> PillContent(PillIcon.Problem, "Setup problem", "")
        LifecyclePhase.Stopped -> PillContent(PillIcon.Check, "Unhooked", "")
        LifecyclePhase.StopFailed -> PillContent(PillIcon.Problem, "Clean-up problem", "")
    }
}

/**
 * The title-bar status pill: the current step while setting up or cleaning up, then "Connected". Clicking it
 * brings the card back. [modifier] should carry the title bar's `interactive` region so the click lands.
 */
@Composable
fun LifecyclePill(controller: LifecycleController, modifier: Modifier = Modifier) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val content = pillContent(ui)
    val scheme = MaterialTheme.colorScheme
    // Keep the last content on screen while the pill fades out.
    var shown by remember { mutableStateOf(content) }
    if (content != null) shown = content
    AnimatedVisibility(visible = content != null, enter = fadeIn(tween(250)), exit = fadeOut(tween(350))) {
        val last = shown ?: return@AnimatedVisibility
        val border by animateColorAsState(
            when (last.icon) {
                PillIcon.Check -> scheme.primaryContainer
                PillIcon.Problem -> scheme.error
                PillIcon.Spinner -> scheme.outlineVariant
            },
            tween(250),
            label = "pillBorder",
        )
        Surface(
            onClick = controller::reopen,
            modifier = modifier,
            shape = RoundedCornerShape(50),
            color = scheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, border),
        ) {
            Row(
                Modifier.padding(start = 7.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillMark(last.icon)
                AnimatedContent(
                    targetState = last.text,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "pillText",
                ) { Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface) }
                if (last.count.isNotEmpty()) {
                    Text(last.count, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PillMark(icon: PillIcon) {
    val scheme = MaterialTheme.colorScheme
    if (icon == PillIcon.Spinner) {
        CircularProgressIndicator(Modifier.size(10.dp), color = scheme.primary, strokeWidth = 1.5.dp)
        return
    }
    val fill = if (icon == PillIcon.Check) scheme.primary else scheme.error
    val ink = scheme.surfaceContainerHigh
    Canvas(Modifier.size(10.dp)) {
        val w = size.width
        val stroke = 1.3.dp.toPx()
        drawCircle(fill)
        if (icon == PillIcon.Check) {
            drawLine(ink, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), stroke, StrokeCap.Round)
            drawLine(ink, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.73f, w * 0.36f), stroke, StrokeCap.Round)
        } else {
            drawLine(ink, Offset(w / 2, w * 0.26f), Offset(w / 2, w * 0.58f), stroke, StrokeCap.Round)
            drawCircle(ink, radius = stroke * 0.6f, center = Offset(w / 2, w * 0.76f))
        }
    }
}
