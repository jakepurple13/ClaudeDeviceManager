package com.jacobrein.cdm.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private const val TOP_LEVEL_KEY = "cdm.topLevel"
private const val SECTION_MS = 220
private const val DETAIL_MS = 250

private fun section(t: TopLevel): Map<String, Any> = mapOf(TOP_LEVEL_KEY to t)

/** The rail section a scene shows; a list-detail scene's first entry is its list. */
private fun Scene<Route>.section(): Any? = entries.firstOrNull()?.metadata?.get(TOP_LEVEL_KEY)

/** Rail glyphs, as in the approved mockup. */
private val TopLevel.glyph: String
    get() = when (this) {
        TopLevel.OVERVIEW -> "◫"
        TopLevel.DEVICES -> "▣"
        TopLevel.QUEUE -> "☰"
        TopLevel.SESSIONS -> "◷"
        TopLevel.PROJECTS -> "▤"
        TopLevel.SETTINGS -> "⚙"
    }

/** The rail plus the Navigation 3 display. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AppNavigation(
    modifier: Modifier = Modifier,
    navigator: Navigator = koinInject(),
    shell: ShellViewModel = koinViewModel(),
) {
    val counts by shell.counts.collectAsStateWithLifecycle()
    Row(modifier.fillMaxSize()) {
        AppRail(navigator.currentTopLevel, counts.waiting, navigator::selectTopLevel)
        val slidePx = with(LocalDensity.current) { 24.dp.roundToPx() }
        val forward: AnimatedContentTransitionScope<Scene<Route>>.() -> ContentTransform =
            remember(slidePx) { { sceneTransition(slidePx, pop = false) } }
        val pop: AnimatedContentTransitionScope<Scene<Route>>.() -> ContentTransform =
            remember(slidePx) { { sceneTransition(slidePx, pop = true) } }
        NavDisplay(
            backStack = navigator.backStack,
            onBack = navigator::back,
            modifier = Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            sceneStrategies = listOf(rememberListDetailSceneStrategy()),
            transitionSpec = forward,
            popTransitionSpec = pop,
            entryProvider = entryProvider {
                entry<Route.Overview>(metadata = section(TopLevel.OVERVIEW)) { Placeholder("Overview") }
                entry<Route.Devices>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { Placeholder("Select a device") }) +
                        section(TopLevel.DEVICES),
                ) { Placeholder("Devices") }
                entry<Route.DeviceDetail>(metadata = ListDetailSceneStrategy.detailPane() + section(TopLevel.DEVICES)) {
                    Placeholder("Device ${it.key.hardwareSerial}")
                }
                entry<Route.Queue>(
                    metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { Placeholder("Select a holder") }) +
                        section(TopLevel.QUEUE),
                ) { Placeholder("Queue") }
                entry<Route.QueueDetail>(metadata = ListDetailSceneStrategy.detailPane() + section(TopLevel.QUEUE)) {
                    Placeholder("Holder ${it.holder.key}")
                }
                entry<Route.Sessions>(metadata = section(TopLevel.SESSIONS)) { Placeholder("Sessions") }
                entry<Route.Projects>(metadata = section(TopLevel.PROJECTS)) { Placeholder("Projects") }
                entry<Route.Settings>(metadata = section(TopLevel.SETTINGS)) { Placeholder("Settings") }
            },
        )
    }
}

/**
 * Switching rail sections: fade plus a 24 dp vertical slide (220 ms). Opening a detail: fade plus a horizontal
 * slide in from the end (250 ms); closing it slides back out to the end.
 */
private fun AnimatedContentTransitionScope<Scene<Route>>.sceneTransition(slidePx: Int, pop: Boolean): ContentTransform =
    if (initialState.section() != targetState.section()) {
        (fadeIn(tween(SECTION_MS)) + slideInVertically(tween(SECTION_MS)) { slidePx }) togetherWith
            (fadeOut(tween(SECTION_MS)) + slideOutVertically(tween(SECTION_MS)) { -slidePx })
    } else if (!pop) {
        (fadeIn(tween(DETAIL_MS)) + slideInHorizontally(tween(DETAIL_MS)) { it / 4 }) togetherWith
            fadeOut(tween(DETAIL_MS))
    } else {
        fadeIn(tween(DETAIL_MS)) togetherWith
            (fadeOut(tween(DETAIL_MS)) + slideOutHorizontally(tween(DETAIL_MS)) { it / 4 })
    }

@Composable
private fun AppRail(selected: TopLevel, waiting: Int, onSelect: (TopLevel) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Spacer(Modifier.height(4.dp))
        TopLevel.entries.forEach { t ->
            NavigationRailItem(
                selected = t == selected,
                onClick = { onSelect(t) },
                icon = {
                    if (t == TopLevel.QUEUE) {
                        BadgedBox(badge = { QueueBadge(waiting) }) { RailGlyph(t) }
                    } else {
                        RailGlyph(t)
                    }
                },
                label = { Text(t.label, fontSize = 11.sp) },
            )
        }
    }
}

@Composable
private fun RailGlyph(t: TopLevel) {
    Text(t.glyph, fontSize = 17.sp)
}

@Composable
private fun QueueBadge(waiting: Int) {
    AnimatedVisibility(
        visible = waiting > 0,
        enter = fadeIn(tween(200)) + scaleIn(tween(200)),
        exit = fadeOut(tween(200)) + scaleOut(tween(200)),
    ) {
        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            AnimatedCount(waiting)
        }
    }
}

/** A number that slides vertically and fades when it changes: up when it grows, down when it shrinks. */
@Composable
fun AnimatedCount(value: Int, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(tween(200)) { h -> if (up) h else -h } + fadeIn(tween(200))) togetherWith
                (slideOutVertically(tween(200)) { h -> if (up) -h else h } + fadeOut(tween(200)))
        },
        label = "count",
    ) { Text("$it", style = style) }
}

/** Stand-in screen until Tasks 10–11 fill the routes in. */
@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
