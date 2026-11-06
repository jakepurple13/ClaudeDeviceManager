package com.programmersbox.cdm.ui.lifecycle

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp

/** The app icon's hub, in the icon's own 176 x 166 units (its viewBox, offset by 22, 10). */
private object HubGeometry {
    const val W = 176f
    const val H = 166f
    val center = Offset(88f, 90f)
    const val RING = 18f
    const val DOT = 7f
    const val SPOKE = 7f

    /** A node: its rectangle, the spoke's two ends (hub side first), and the way it drifts off when removed. */
    class Node(val topLeft: Offset, val size: Size, val hubEnd: Offset, val nodeEnd: Offset, val drift: Offset)

    // Top: hooks. Bottom left: cdm. Bottom right: CLAUDE.md. Same places as in the app icon.
    val nodes = listOf(
        Node(Offset(72f, 6f), Size(32f, 48f), Offset(88f, 76f), Offset(88f, 54f), Offset(0f, -14f)),
        Node(Offset(10f, 112f), Size(34f, 46f), Offset(75f, 98f), Offset(42f, 120f), Offset(-14f, 10f)),
        Node(Offset(130f, 116f), Size(36f, 34f), Offset(101f, 98f), Offset(134f, 120f), Offset(14f, 10f)),
    )
}

private val HUB_PARTS = listOf(LifecycleStep.HOOKS, LifecycleStep.CDM, LifecycleStep.SNIPPET)

/**
 * The hub from the app icon, wired up as [rows] progress: the ring is the server, each spoke and node is one
 * part of the integration, and the hub pulses while devices are being scanned.
 */
@Composable
fun LifecycleHub(rows: List<StepRow>, modifier: Modifier = Modifier) {
    fun status(s: LifecycleStep) = rows.firstOrNull { it.step == s }?.status
    val server = animatedPart(partVisual(status(LifecycleStep.SERVER)))
    val parts = HUB_PARTS.map { animatedPart(partVisual(status(it))) }
    val scanning = status(LifecycleStep.DEVICES) == StepStatus.Running
    val pulse by rememberInfiniteTransition(label = "hubPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "hubPulseT",
    )

    val scheme = MaterialTheme.colorScheme
    val accent = scheme.primary
    val off = scheme.outline
    val error = scheme.error
    val ringColor = scheme.onSurface

    Canvas(modifier.aspectRatio(HubGeometry.W / HubGeometry.H)) {
        val s = size.width / HubGeometry.W
        fun Offset.px() = Offset(x * s, y * s)
        fun tint(p: PartVisual) = lerp(lerp(off, accent, p.lit), error, p.error)

        HubGeometry.nodes.forEachIndexed { i, n ->
            val p = parts[i]
            if (p.drawn > 0.01f) {
                val end = lerp(n.hubEnd, n.nodeEnd, p.drawn)
                drawLine(tint(p), n.hubEnd.px(), end.px(), strokeWidth = HubGeometry.SPOKE * s, cap = StrokeCap.Round)
            }
        }
        HubGeometry.nodes.forEachIndexed { i, n -> drawNode(n, parts[i], s, tint(parts[i]), accent) }

        if (scanning) {
            drawCircle(
                accent.copy(alpha = 0.8f * (1f - pulse)),
                radius = HubGeometry.RING * (1f + 1.4f * pulse) * s,
                center = HubGeometry.center.px(),
                style = Stroke(width = 2f * s),
            )
        }
        val ringScale = server.seen.coerceAtLeast(0f)
        val ringAlpha = (1f - 0.75f * server.drift).coerceIn(0f, 1f)
        if (server.glow > 0.01f) {
            drawCircle(accent.copy(alpha = 0.3f * server.glow), (HubGeometry.RING + 5f) * ringScale * s, HubGeometry.center.px())
        }
        drawCircle(ringColor.copy(alpha = ringAlpha), HubGeometry.RING * ringScale * s, HubGeometry.center.px())
        drawCircle(tint(server).copy(alpha = ringAlpha), HubGeometry.DOT * ringScale * s, HubGeometry.center.px())
    }
}

private fun DrawScope.drawNode(n: HubGeometry.Node, p: PartVisual, s: Float, color: Color, glowColor: Color) {
    val alpha = (p.seen * (1f - p.drift)).coerceIn(0f, 1f)
    if (alpha <= 0.01f) return
    val scale = (0.6f + 0.4f * p.seen - 0.2f * p.drift).coerceAtLeast(0f)
    val w = n.size.width * scale
    val h = n.size.height * scale
    val cx = n.topLeft.x + n.size.width / 2 + n.drift.x * p.drift
    val cy = n.topLeft.y + n.size.height / 2 + n.drift.y * p.drift
    val radius = CornerRadius(9f * s)
    if (p.glow > 0.01f) {
        val g = 5f
        drawRoundRect(
            glowColor.copy(alpha = 0.3f * p.glow * alpha),
            topLeft = Offset((cx - w / 2 - g) * s, (cy - h / 2 - g) * s),
            size = Size((w + 2 * g) * s, (h + 2 * g) * s),
            cornerRadius = CornerRadius((9f + g) * s),
        )
    }
    drawRoundRect(
        color.copy(alpha = alpha),
        topLeft = Offset((cx - w / 2) * s, (cy - h / 2) * s),
        size = Size(w * s, h * s),
        cornerRadius = radius,
    )
}

@Composable
private fun animatedPart(v: PartVisual): PartVisual {
    val seen by animateFloatAsState(v.seen, spring(dampingRatio = 0.55f, stiffness = 380f), label = "seen")
    val drawn by animateFloatAsState(v.drawn, tween(320), label = "drawn")
    val lit by animateFloatAsState(v.lit, tween(260), label = "lit")
    val glow by animateFloatAsState(v.glow, tween(200), label = "glow")
    val drift by animateFloatAsState(v.drift, tween(450, easing = FastOutLinearInEasing), label = "drift")
    val error by animateFloatAsState(v.error, tween(260), label = "error")
    return PartVisual(seen, drawn, lit, glow, drift, error)
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
