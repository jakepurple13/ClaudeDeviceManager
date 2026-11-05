package com.jacobrein.cdm.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AnimatedRowsTest {
    private fun count(t: androidx.compose.ui.test.ComposeUiTest, text: String) =
        t.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun removedRowAnimatesOutThenLeaves() = runComposeUiTest {
        val items = mutableStateOf(listOf("Gabb Watch 3", "Galaxy Watch 6"))
        setContent { AnimatedRows(items.value, key = { it }) { Text(it) } }
        waitForIdle()
        assertEquals(1, count(this, "Galaxy Watch 6"))

        mainClock.autoAdvance = false
        items.value = listOf("Gabb Watch 3")
        mainClock.advanceTimeBy(50)
        assertEquals(1, count(this, "Galaxy Watch 6"), "row should still be exiting at 50 ms")
        mainClock.advanceTimeBy(400)
        assertEquals(0, count(this, "Galaxy Watch 6"), "row should be gone after its exit")

        // Re-adding it brings it back (enter animation), and the other row was never disturbed.
        items.value = listOf("Gabb Watch 3", "Galaxy Watch 6")
        mainClock.advanceTimeBy(400)
        assertEquals(1, count(this, "Galaxy Watch 6"))
        assertEquals(1, count(this, "Gabb Watch 3"))
    }

    private data class Reason(val device: String, val text: String)

    @Test
    fun changedItemUnderSameKeyRerenders() = runComposeUiTest {
        val items = mutableStateOf(listOf(Reason("Gabb Watch 3", "held by watch-launcher #77c2")))
        setContent { AnimatedRows(items.value, key = { it.device }) { Text("${it.device} — ${it.text}") } }
        waitForIdle()
        assertEquals(1, count(this, "Gabb Watch 3 — held by watch-launcher #77c2"))

        items.value = listOf(Reason("Gabb Watch 3", "marked don't use"))
        waitForIdle()
        assertEquals(1, count(this, "Gabb Watch 3 — marked don't use"))
        assertEquals(0, count(this, "Gabb Watch 3 — held by watch-launcher #77c2"))
    }
}
