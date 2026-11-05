package com.jacobrein.cdm.ui.nav

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.server.FakeManager
import com.jacobrein.cdm.ui.theme.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {
    private val snap = MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap()))
    private val manager = object : FakeManager() {
        override val snapshot = snap
    }

    @Test
    fun railSwitchesSectionsAndBadgeTracksQueue() = runComposeUiTest {
        val navigator = Navigator()
        val shell = ShellViewModel(manager)
        setContent { AppTheme(dark = true) { AppNavigation(navigator = navigator, shell = shell) } }

        onNodeWithText("Settings").performClick()
        waitForIdle()
        assertEquals(listOf<Route>(Route.Settings), navigator.backStack.toList())
        // Rail label plus the placeholder screen.
        assertEquals(2, onAllNodesWithText("Settings").fetchSemanticsNodes().size)

        onNodeWithText("Devices").performClick()
        waitForIdle()
        assertEquals(TopLevel.DEVICES, navigator.currentTopLevel)

        snap.value = snap.value.copy(queue = listOf(HolderId("a"), HolderId("b")))
        // The count reaches the UI through the view model's stateIn coroutine, not synchronously.
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithText("2", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun badgeExitsWithoutShowingZero() = runComposeUiTest {
        val shell = ShellViewModel(manager)
        snap.value = snap.value.copy(queue = listOf(HolderId("a")))
        setContent { AppTheme(dark = true) { AppNavigation(navigator = Navigator(), shell = shell) } }
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
