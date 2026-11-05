package com.jacobrein.cdm.ui.nav

import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
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
}
