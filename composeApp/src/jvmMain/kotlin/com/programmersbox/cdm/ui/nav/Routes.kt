package com.programmersbox.cdm.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId

/**
 * Every destination in the app. Not `@Serializable`: the back stack lives in [Navigator] (a Koin single), not in
 * `rememberNavBackStack`, so it never needs saving.
 */
sealed interface Route : NavKey {
    data object Overview : Route
    data object Devices : Route
    data class DeviceDetail(val key: DeviceKey) : Route
    data object Queue : Route
    data class QueueDetail(val holder: HolderId) : Route
    data object Sessions : Route
    data object Projects : Route
    data object Settings : Route
}

/** The rail sections, in rail order. */
enum class TopLevel(val route: Route, val label: String) {
    OVERVIEW(Route.Overview, "Overview"),
    DEVICES(Route.Devices, "Devices"),
    QUEUE(Route.Queue, "Queue"),
    SESSIONS(Route.Sessions, "Sessions"),
    PROJECTS(Route.Projects, "Projects"),
    SETTINGS(Route.Settings, "Settings"),
}

/** The rail section a route belongs to; detail routes belong to their list's section. */
val Route.topLevel: TopLevel
    get() = when (this) {
        Route.Overview -> TopLevel.OVERVIEW
        Route.Devices, is Route.DeviceDetail -> TopLevel.DEVICES
        Route.Queue, is Route.QueueDetail -> TopLevel.QUEUE
        Route.Sessions -> TopLevel.SESSIONS
        Route.Projects -> TopLevel.PROJECTS
        Route.Settings -> TopLevel.SETTINGS
    }

/**
 * Owns the back stack. The stack is always `[list route]` or `[list route, detail route]`; the bottom entry is
 * never popped, so `NavDisplay` never sees an empty stack.
 */
class Navigator(start: Route = Route.Overview) {
    val backStack: SnapshotStateList<Route> = mutableStateListOf(start)

    val currentTopLevel: TopLevel get() = backStack.first().topLevel

    fun selectTopLevel(t: TopLevel) {
        if (backStack.size == 1 && backStack[0] == t.route) return
        // Add before removing so the list is never momentarily empty for a reader.
        backStack.add(0, t.route)
        backStack.removeRange(1, backStack.size)
    }

    fun openDetail(r: Route) {
        require(r is Route.DeviceDetail || r is Route.QueueDetail) { "not a detail route: $r" }
        when {
            backStack.size == 1 -> backStack.add(r)
            backStack[1] != r -> backStack[1] = r
        }
    }

    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
}
