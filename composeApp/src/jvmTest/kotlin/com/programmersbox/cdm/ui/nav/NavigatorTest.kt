package com.programmersbox.cdm.ui.nav

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigatorTest {
    private val a = DeviceKey("a")
    private val b = DeviceKey("b")

    @Test
    fun startsOnOverview() {
        val nav = Navigator()
        assertEquals(listOf<Route>(Route.Overview), nav.backStack.toList())
        assertEquals(TopLevel.OVERVIEW, nav.currentTopLevel)
    }

    @Test
    fun selectTopLevelResetsStack() {
        val nav = Navigator(Route.Devices)
        nav.openDetail(Route.DeviceDetail(a))
        nav.selectTopLevel(TopLevel.QUEUE)
        assertEquals(listOf<Route>(Route.Queue), nav.backStack.toList())
        assertEquals(TopLevel.QUEUE, nav.currentTopLevel)
    }

    @Test
    fun openDetailReplacesDetail() {
        val nav = Navigator(Route.Devices)
        nav.openDetail(Route.DeviceDetail(a))
        assertEquals(listOf(Route.Devices, Route.DeviceDetail(a)), nav.backStack.toList())
        nav.openDetail(Route.DeviceDetail(b))
        assertEquals(listOf(Route.Devices, Route.DeviceDetail(b)), nav.backStack.toList())
    }

    @Test
    fun detailKeepsItsTopLevel() {
        val nav = Navigator(Route.Queue)
        nav.openDetail(Route.QueueDetail(HolderId("s1")))
        assertEquals(TopLevel.QUEUE, nav.currentTopLevel)
    }

    @Test
    fun backPopsDetailButNeverEmptiesStack() {
        val nav = Navigator(Route.Devices)
        nav.openDetail(Route.DeviceDetail(a))
        nav.back()
        assertEquals(listOf<Route>(Route.Devices), nav.backStack.toList())
        nav.back()
        assertEquals(listOf<Route>(Route.Devices), nav.backStack.toList())
    }

    @Test
    fun topLevelRoutesMatchRailOrder() {
        assertEquals(
            listOf(Route.Overview, Route.Devices, Route.Queue, Route.Sessions, Route.Projects, Route.Settings),
            TopLevel.entries.map { it.route },
        )
        assertEquals(TopLevel.DEVICES, Route.DeviceDetail(a).topLevel)
    }

    @Test
    fun devStartRouteMalformedIsInert() {
        val nav = Navigator()
        applyDevStartRoute(nav, "devices:")
        applyDevStartRoute(nav, "bogus:x")
        applyDevStartRoute(nav, ":::")
        assertEquals(listOf<Route>(Route.Devices), nav.backStack.toList())
        applyDevStartRoute(nav, "queue:s1:main")
        assertEquals(listOf<Route>(Route.Queue, Route.QueueDetail(HolderId("s1"))), nav.backStack.toList())
    }

    /** Final review #14: a registered (two-pane) detail is popped only after its exit animation finishes. */
    @Test
    fun backWaitsForRegisteredDetailExit() {
        val nav = Navigator(Route.Devices)
        val d = Route.DeviceDetail(a)
        nav.openDetail(d)
        nav.registerExitAnimation(d)
        nav.back()
        assertEquals(listOf(Route.Devices, d), nav.backStack.toList())
        assertEquals(d, nav.closing)
        nav.finishClose(d)
        assertEquals(listOf<Route>(Route.Devices), nav.backStack.toList())
        assertEquals(null, nav.closing)

        // A pane that leaves composition mid-close still completes the pop.
        nav.openDetail(d)
        nav.back()
        nav.unregisterExitAnimation(d)
        assertEquals(listOf<Route>(Route.Devices), nav.backStack.toList())

        // Unregistered: immediate.
        nav.openDetail(d)
        nav.back()
        assertEquals(listOf<Route>(Route.Devices), nav.backStack.toList())
    }
}
