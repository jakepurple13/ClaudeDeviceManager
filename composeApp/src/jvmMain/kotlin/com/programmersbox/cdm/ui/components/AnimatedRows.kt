package com.programmersbox.cdm.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private class RowEntry<T>(val key: Any, item: T) {
    /** Snapshot state, so a changed item under the same key re-renders even when the row's lambda is skipped. */
    var item: T by mutableStateOf(item)
    val visible = MutableTransitionState(false).apply { targetState = true }
}

/**
 * A short, non-lazy list whose rows expand in when they appear and shrink out when they go (200 ms / 150 ms).
 * Removed rows stay in place, invisible to the caller's data, until their exit animation finishes.
 * For panes inside a vertical scroll where a LazyColumn (and `animateItem`) can't be used.
 */
@Composable
fun <T> AnimatedRows(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val entries = remember { mutableListOf<RowEntry<T>>() }
    val keyOf = rememberUpdatedState(key)
    val merged = remember(items) {
        // Drop rows whose exit has finished, then rebuild: current items in their order, leaving rows at their old index.
        entries.removeAll { it.visible.isIdle && !it.visible.currentState && !it.visible.targetState }
        val old = entries.associateBy { it.key }
        val keys = items.map(keyOf.value)
        val next = items.mapIndexed { i, item ->
            (old[keys[i]]?.also { it.item = item; it.visible.targetState = true }) ?: RowEntry(keys[i], item)
        }.toMutableList()
        val keySet = keys.toSet()
        entries.forEachIndexed { i, e ->
            if (e.key !in keySet) {
                e.visible.targetState = false
                next.add(i.coerceAtMost(next.size), e)
            }
        }
        entries.clear()
        entries.addAll(next)
        next.toList()
    }
    merged.forEach { e ->
        key(e.key) {
            AnimatedVisibility(
                visibleState = e.visible,
                enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
            ) { content(e.item) }
        }
    }
}

/** [AnimatedVisibility] for a nullable value: keeps showing the last non-null value while it animates out. */
@Composable
fun <T : Any> AnimatedValue(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter = fadeIn(tween(200)) + expandVertically(tween(200)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
    ) {
        @Suppress("UNCHECKED_CAST")
        (last[0] as T?)?.let { content(it) }
    }
}

/** A conditional line (empty states and the like) that expands and fades in and out. */
@Composable
fun AnimatedLine(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(200)) + expandVertically(tween(200)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
    ) { content() }
}
