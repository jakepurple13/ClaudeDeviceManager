package com.programmersbox.cdm.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import com.programmersbox.cdm.ui.components.AnimatedCount
import com.programmersbox.cdm.ui.devices.DeviceDetailScreen
import com.programmersbox.cdm.ui.devices.DevicesScreen
import com.programmersbox.cdm.ui.overview.OverviewScreen
import com.programmersbox.cdm.ui.queue.QueueDetailScreen
import com.programmersbox.cdm.ui.queue.QueueScreen
import com.programmersbox.cdm.ui.projects.ProjectsScreen
import com.programmersbox.cdm.ui.sessions.SessionsScreen
import com.programmersbox.cdm.ui.settings.SettingsScreen
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
        // Screens draw on the bare background (no Surface), so give their text the right default colour.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
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
                    entry<Route.Overview>(metadata = section(TopLevel.OVERVIEW)) {
                        OverviewScreen(
                            onOpenDevice = { key ->
                                navigator.selectTopLevel(TopLevel.DEVICES)
                                navigator.openDetail(Route.DeviceDetail(key))
                            },
                            onOpenHolder = { h ->
                                navigator.selectTopLevel(TopLevel.QUEUE)
                                navigator.openDetail(Route.QueueDetail(h))
                            },
                        )
                    }
                    entry<Route.Devices>(
                        metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPane { Placeholder("Select a device") } }) +
                            section(TopLevel.DEVICES),
                    ) {
                        DevicesScreen(
                            selected = (navigator.backStack.getOrNull(1) as? Route.DeviceDetail)?.key,
                            onOpen = { navigator.openDetail(Route.DeviceDetail(it)) },
                        )
                    }
                    entry<Route.DeviceDetail>(metadata = ListDetailSceneStrategy.detailPane() + section(TopLevel.DEVICES)) {
                        DetailPane(navigator, it) { DeviceDetailScreen(it.key) }
                    }
                    entry<Route.Queue>(
                        metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPane { Placeholder("Select a holder") } }) +
                            section(TopLevel.QUEUE),
                    ) {
                        QueueScreen(
                            selected = (navigator.backStack.getOrNull(1) as? Route.QueueDetail)?.holder,
                            onOpen = { navigator.openDetail(Route.QueueDetail(it)) },
                        )
                    }
                    entry<Route.QueueDetail>(metadata = ListDetailSceneStrategy.detailPane() + section(TopLevel.QUEUE)) {
                        DetailPane(navigator, it) { QueueDetailScreen(it.holder, onClose = navigator::back) }
                    }
                    entry<Route.Sessions>(metadata = section(TopLevel.SESSIONS)) { SessionsScreen() }
                    entry<Route.Projects>(metadata = section(TopLevel.PROJECTS)) { ProjectsScreen() }
                    entry<Route.Settings>(metadata = section(TopLevel.SETTINGS)) { SettingsScreen() }
                },
            )
        }
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
    // While the badge fades out after the queue empties, keep showing the last non-zero count, not "0".
    val shown = remember { mutableIntStateOf(waiting) }
    if (waiting > 0) shown.intValue = waiting
    AnimatedVisibility(
        visible = waiting > 0,
        enter = fadeIn(tween(200)) + scaleIn(tween(200)),
        exit = fadeOut(tween(200)) + scaleOut(tween(200)),
    ) {
        Badge(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            AnimatedCount(shown.intValue)
        }
    }
}

/**
 * Slides and fades a detail pane's content in when it first composes, and (for a [route]) out again when it is
 * closed. In the two-pane layout the list-detail scene keeps its key when a detail opens, changes or closes, so
 * NavDisplay's scene transition doesn't run there; this animates the detail pane alone and leaves the list pane
 * still. To close, [Navigator.back] marks the route [Navigator.closing]; the pane animates out, then pops it.
 * In the single-pane layout NavDisplay's pop transition already animates, so the pane doesn't register.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun DetailPane(navigator: Navigator? = null, route: Route? = null, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    if (navigator != null && route != null) {
        val twoPane = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).maxHorizontalPartitions > 1
        DisposableEffect(navigator, route, twoPane) {
            if (twoPane) navigator.registerExitAnimation(route)
            onDispose { navigator.unregisterExitAnimation(route) }
        }
        val closing = navigator.closing == route
        LaunchedEffect(closing) { shown.targetState = !closing }
        if (closing && shown.isIdle && !shown.currentState) {
            LaunchedEffect(Unit) { navigator.finishClose(route) }
        }
    }
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(DETAIL_MS)) + slideInHorizontally(tween(DETAIL_MS)) { it / 8 },
        exit = fadeOut(tween(DETAIL_MS)) + slideOutHorizontally(tween(DETAIL_MS)) { it / 8 },
    ) { content() }
}

/** Stand-in for an empty detail pane. */
@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
