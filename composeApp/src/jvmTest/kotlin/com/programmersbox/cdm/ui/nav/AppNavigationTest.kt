package com.programmersbox.cdm.ui.nav

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.server.FakeManager
import com.programmersbox.cdm.ui.theme.AppTheme
import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.data.ActivityRepository
import com.programmersbox.cdm.data.DeviceConfigRepository
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.data.ProjectRepository
import com.programmersbox.cdm.install.InstallLifecycle
import com.programmersbox.cdm.install.Installer
import com.programmersbox.cdm.server.ServerControl
import com.programmersbox.cdm.server.ServerStatus
import java.nio.file.Files
import com.programmersbox.cdm.di.APP_SCOPE
import com.programmersbox.cdm.di.uiModule
import com.programmersbox.cdm.update.UnsupportedUpdater
import com.programmersbox.cdm.update.UpdateController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import com.programmersbox.cdm.service.DeviceManager
import com.programmersbox.cdm.ui.FixedClock
import com.programmersbox.cdm.ui.openedInMemoryDatabase
import com.programmersbox.cdm.ui.UiClock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.koin.core.context.startKoin
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {
    private val snap = MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap()))
    private val manager = object : FakeManager() {
        override val snapshot = snap
    }

    @BeforeTest
    fun startKoin() {
        val db = openedInMemoryDatabase()
        startKoin {
            modules(
                uiModule,
                module {
                    single<DeviceManager> { manager }
                    single<UiClock> { FixedClock() }
                    single<CoroutineScope>(named(APP_SCOPE)) { CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }
                    single { DeviceConfigRepository(db.deviceConfigDao()) }
                    single { ActivityRepository(db.activityDao()) }
                    single { SettingsRepository(db.settingDao()) }
                    single { ProjectRepository(db.projectDao()) }
                    // A temp home: the Settings screen must never read or write the real ~/.claude.
                    single { Installer(Files.createTempDirectory("cdm-nav-home")) }
                    single { InstallLifecycle(get()) }
                    single { ServerStatus() }
                    single { com.programmersbox.cdm.devices.AdbSelection() }
                    single<ServerControl> { object : ServerControl { override suspend fun restart(port: Int) = Unit } }
                    single { UpdateController(UnsupportedUpdater, get(named(APP_SCOPE))) }
                },
            )
        }
    }

    @AfterTest
    fun stopKoin() = org.koin.core.context.stopKoin()

    @Test
    fun deviceRowOpensDetailBesideList() = runComposeUiTest {
        val key = DeviceKey("SER1")
        snap.value = snap.value.copy(devices = listOf(AllocDevice(key, "S6003L", Category.PHONE, false, listOf("s1"))))
        val navigator = Navigator(Route.Devices)
        setContent { AppTheme(dark = true) { AppNavigation(navigator = navigator, shell = ShellViewModel(manager)) } }

        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("S6003L").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("S6003L").performClick()
        waitForIdle()
        assertEquals(listOf(Route.Devices, Route.DeviceDetail(key)), navigator.backStack.toList())
        // Both panes: the list row plus the detail's title.
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("S6003L").fetchSemanticsNodes().size == 2 }
        onNodeWithText("Category:").assertExists()
    }

    @Test
    fun railSwitchesSectionsAndBadgeTracksQueue() = runComposeUiTest {
        val navigator = Navigator()
        val shell = ShellViewModel(manager)
        setContent { AppTheme(dark = true) { AppNavigation(navigator = navigator, shell = shell) } }

        onNodeWithText("Settings").performClick()
        waitForIdle()
        assertEquals(listOf<Route>(Route.Settings), navigator.backStack.toList())
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("Repair").fetchSemanticsNodes().isNotEmpty() }

        onNodeWithText("Devices").performClick()
        waitForIdle()
        assertEquals(TopLevel.DEVICES, navigator.currentTopLevel)

        snap.value = snap.value.copy(queue = listOf(HolderId("a"), HolderId("b")))
        // The count reaches the UI through the view model's stateIn coroutine, not synchronously.
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithText("2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Opening a detail must slide in only the detail pane; the list pane stays where it is on every frame. */
    @Test
    fun openingDetailKeepsListPanePut() = runComposeUiTest {
        val key = DeviceKey("SER1")
        snap.value = snap.value.copy(devices = listOf(AllocDevice(key, "S6003L", Category.PHONE, false, listOf("s1"))))
        val navigator = Navigator(Route.Devices)
        setContent { AppTheme(dark = true) { AppNavigation(navigator = navigator, shell = ShellViewModel(manager)) } }
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("S6003L").fetchSemanticsNodes().isNotEmpty() }
        val before = onNodeWithText("S6003L").fetchSemanticsNode().boundsInRoot

        mainClock.autoAdvance = false
        navigator.openDetail(Route.DeviceDetail(key))
        val lefts = mutableListOf<Float>()
        val detailLefts = mutableListOf<Float>()
        repeat(20) {
            mainClock.advanceTimeByFrame()
            // The list row is the first "S6003L" node; the detail title (once composed) is the second.
            val nodes = onAllNodesWithText("S6003L").fetchSemanticsNodes()
            lefts += nodes.first().boundsInRoot.left
            nodes.getOrNull(1)?.let { detailLefts += it.boundsInRoot.left }
        }
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(lefts.all { it == before.left }, "list pane moved while the detail opened: ${before.left} -> $lefts")
        assertTrue(detailLefts.size > 2 && detailLefts.first() > detailLefts.last(), "detail pane didn't slide in: $detailLefts")
    }

    /** Final review #14: closing a detail in the two-pane layout animates the detail out, not a hard cut. */
    @Test
    fun closingDetailAnimatesOut() = runComposeUiTest {
        val key = DeviceKey("SER1")
        snap.value = snap.value.copy(devices = listOf(AllocDevice(key, "S6003L", Category.PHONE, false, listOf("s1"))))
        val navigator = Navigator(Route.Devices)
        setContent { AppTheme(dark = true) { AppNavigation(navigator = navigator, shell = ShellViewModel(manager)) } }
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("S6003L").fetchSemanticsNodes().isNotEmpty() }
        navigator.openDetail(Route.DeviceDetail(key))
        waitUntil(timeoutMillis = 3_000) { onAllNodesWithText("S6003L").fetchSemanticsNodes().size == 2 }
        waitForIdle()
        val listLeft = onAllNodesWithText("S6003L").fetchSemanticsNodes().first().boundsInRoot.left

        mainClock.autoAdvance = false
        navigator.back()
        val detailLefts = mutableListOf<Float>()
        val listLefts = mutableListOf<Float>()
        repeat(30) {
            mainClock.advanceTimeByFrame()
            val nodes = onAllNodesWithText("S6003L").fetchSemanticsNodes()
            listLefts += nodes.first().boundsInRoot.left
            nodes.getOrNull(1)?.let { detailLefts += it.boundsInRoot.left }
        }
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(listOf(Route.Devices), navigator.backStack.toList())
        assertEquals(1, onAllNodesWithText("S6003L").fetchSemanticsNodes().size, "detail still shown after the exit")
        assertTrue(listLefts.all { it == listLeft }, "list pane moved while the detail closed: $listLefts")
        assertTrue(detailLefts.size > 2 && detailLefts.last() > detailLefts.first(), "detail pane didn't slide out: $detailLefts")
    }

    @Test
    fun badgeExitsWithoutShowingZero() = runComposeUiTest {
        val shell = ShellViewModel(manager)
        snap.value = snap.value.copy(queue = listOf(HolderId("a")))
        // Start away from Overview: its count tiles legitimately read "0".
        setContent { AppTheme(dark = true) { AppNavigation(navigator = Navigator(Route.Settings), shell = shell) } }
        waitUntil(timeoutMillis = 2_000) { onAllNodesWithText("1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        mainClock.autoAdvance = false
        snap.value = snap.value.copy(queue = emptyList())
        // Step through the whole exit animation frame by frame: the badge must never read "0".
        repeat(40) {
            mainClock.advanceTimeByFrame()
            waitForIdle()
            assertTrue(
                onAllNodesWithText("0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
                "badge showed 0 at frame $it",
            )
        }
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(onAllNodesWithText("1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "badge still shown")
    }
}
