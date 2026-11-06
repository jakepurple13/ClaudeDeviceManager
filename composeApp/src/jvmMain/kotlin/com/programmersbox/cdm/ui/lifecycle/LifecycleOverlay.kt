package com.programmersbox.cdm.ui.lifecycle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The start-up / quit card: the hub beside the checklist, over a scrim. Clicking anywhere hides it (the title bar
 * keeps showing progress). [onOpenSettings] is offered when the server could not start.
 */
@Composable
fun LifecycleOverlay(controller: LifecycleController, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = ui.cardVisible,
        modifier = modifier.fillMaxSize(),
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(300)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f))
                .clickable(remember { MutableInteractionSource() }, indication = null) { controller.dismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.animateEnterExit(enter = scaleIn(tween(300), 0.96f), exit = scaleOut(tween(300), 0.96f))) {
                LifecycleCard(
                    ui,
                    onRepair = controller::repair,
                    onOpenSettings = { controller.dismiss(); onOpenSettings() },
                    onClose = controller::dismiss,
                    onQuit = controller::quitNow,
                )
            }
        }
    }
}

@Composable
private fun LifecycleCard(
    ui: LifecycleUi,
    onRepair: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    onQuit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(440.dp),
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 12.dp,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(124.dp).background(scheme.surfaceContainerLowest, RoundedCornerShape(10.dp)).padding(10.dp),
                contentAlignment = Alignment.Center,
            ) { LifecycleHub(ui.rows, Modifier.fillMaxWidth()) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimatedContent(
                    targetState = heading(ui),
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "heading",
                ) { (title, sub) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ui.rows.forEach { StepLine(it, ui.phase.isShutdown) }
                }
                val progress by animateFloatAsState(
                    if (ui.rows.isEmpty()) 0f else ui.finishedCount.toFloat() / ui.rows.size,
                    tween(300),
                    label = "cardProgress",
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (ui.failed) scheme.error else scheme.primary,
                    trackColor = scheme.outlineVariant,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
                Footer(ui, onRepair, onOpenSettings, onClose, onQuit)
            }
        }
    }
}

@Composable
private fun Footer(ui: LifecycleUi, onRepair: () -> Unit, onOpenSettings: () -> Unit, onClose: () -> Unit, onQuit: () -> Unit) {
    when (ui.phase) {
        LifecyclePhase.StartFailed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.canRepair) Button(onClick = onRepair) { Text("Repair") }
            if (ui.serverFailed) Button(onClick = onOpenSettings) { Text("Open Settings") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        LifecyclePhase.StopFailed -> Button(onClick = onQuit) { Text("Quit anyway") }
        LifecyclePhase.Starting, LifecyclePhase.Stopping -> Text(
            "Click to hide. Progress stays in the title bar.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {}
    }
}

private fun heading(ui: LifecycleUi): Pair<String, String> = when (ui.phase) {
    LifecyclePhase.Idle, LifecyclePhase.Starting -> "Setting up" to "Connecting to Claude Code"
    LifecyclePhase.Ready -> "Ready" to "Start Claude sessions now"
    LifecyclePhase.StartFailed ->
        "Setup problem" to if (ui.serverFailed) "The server couldn't start, so nothing was installed" else "Part of the setup failed"
    LifecyclePhase.Stopping -> "Cleaning up" to "Unhooking from Claude Code"
    LifecyclePhase.Stopped -> "All clear" to "Claude works as before"
    LifecyclePhase.StopFailed -> "Clean-up problem" to "Remove the failed part by hand"
}

@Composable
private fun StepLine(row: StepRow, shutdown: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val status = row.status
    val color by animateColorAsState(
        when (status) {
            StepStatus.Running, StepStatus.Done -> scheme.onSurface
            is StepStatus.Failed -> scheme.error
            else -> scheme.onSurfaceVariant
        },
        tween(250),
        label = "rowColor",
    )
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepMark(status)
            Text(
                row.step.label(shutdown),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                textDecoration = if (status == StepStatus.Removed) TextDecoration.LineThrough else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                row.detail,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
        if (status is StepStatus.Failed) {
            Text(
                status.message,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.error,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp),
            )
        }
    }
}

/** The row's state mark: open circle, spinner, check, minus, or cross. */
@Composable
private fun StepMark(status: StepStatus) {
    val scheme = MaterialTheme.colorScheme
    AnimatedContent(
        targetState = markKind(status),
        transitionSpec = { (fadeIn(tween(180)) + scaleIn(tween(180), 0.6f)) togetherWith fadeOut(tween(120)) },
        label = "mark",
    ) { kind ->
        if (kind == Mark.Running) {
            CircularProgressIndicator(Modifier.size(12.dp), color = scheme.primary, strokeWidth = 1.5.dp)
            return@AnimatedContent
        }
        val fill = when (kind) {
            Mark.Done -> scheme.primary
            Mark.Removed -> scheme.outline
            Mark.Failed -> scheme.error
            else -> Color.Transparent
        }
        val ink = scheme.surfaceContainerHigh
        val ring = scheme.outline
        Canvas(Modifier.size(12.dp)) {
            val w = size.width
            if (fill == Color.Transparent) {
                drawCircle(ring, radius = w / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                if (kind == Mark.Skipped) drawLine(ring, Offset(w * 0.32f, w / 2), Offset(w * 0.68f, w / 2), 1.5.dp.toPx())
                return@Canvas
            }
            drawCircle(fill)
            val stroke = 1.5.dp.toPx()
            when (kind) {
                Mark.Done -> {
                    drawLine(ink, Offset(w * 0.28f, w * 0.52f), Offset(w * 0.44f, w * 0.68f), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.44f, w * 0.68f), Offset(w * 0.73f, w * 0.36f), stroke, StrokeCap.Round)
                }
                Mark.Removed -> drawLine(ink, Offset(w * 0.3f, w / 2), Offset(w * 0.7f, w / 2), stroke, StrokeCap.Round)
                Mark.Failed -> {
                    drawLine(ink, Offset(w * 0.33f, w * 0.33f), Offset(w * 0.67f, w * 0.67f), stroke, StrokeCap.Round)
                    drawLine(ink, Offset(w * 0.67f, w * 0.33f), Offset(w * 0.33f, w * 0.67f), stroke, StrokeCap.Round)
                }
                else -> {}
            }
        }
    }
}

private enum class Mark { Pending, Running, Done, Removed, Skipped, Failed }

private fun markKind(s: StepStatus) = when (s) {
    StepStatus.Pending -> Mark.Pending
    StepStatus.Running -> Mark.Running
    StepStatus.Done -> Mark.Done
    StepStatus.Removed -> Mark.Removed
    StepStatus.Skipped -> Mark.Skipped
    is StepStatus.Failed -> Mark.Failed
}

/** The 2dp line under the title bar: fills during start-up, drains during quit, fades out when idle. */
@Composable
fun LifecycleProgressLine(controller: LifecycleController, modifier: Modifier = Modifier) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val progress by animateFloatAsState(ui.progress, tween(350), label = "lineProgress")
    val alpha by animateFloatAsState(if (ui.running || ui.failed) 1f else 0f, tween(400), label = "lineAlpha")
    val color = if (ui.failed) scheme.error else scheme.primary
    Spacer(
        modifier.fillMaxWidth().height(2.dp).drawBehind {
            drawRect(color.copy(alpha = alpha), size = size.copy(width = size.width * progress))
        },
    )
}
