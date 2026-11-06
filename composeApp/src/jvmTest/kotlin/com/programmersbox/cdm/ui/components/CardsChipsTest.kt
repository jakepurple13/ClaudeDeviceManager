package com.programmersbox.cdm.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.ui.DeviceRow
import com.programmersbox.cdm.ui.DeviceState
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CardsChipsTest {
    private fun row(name: String, platform: Platform) = DeviceRow(
        DeviceKey("K"), name, Category.PHONE, false, DeviceState.FREE, null, null, null,
        platform = platform, virtual = true,
    )

    @Test
    fun virtualChipStaysOnOneLineNextToLongName() = runComposeUiTest {
        setContent {
            DeviceCard(row("CDM Test iPhone 15 · iOS 17.5 with a very long extra name", Platform.IOS), detailed = false, selected = false, onClick = {}, modifier = Modifier.width(260.dp))
        }
        waitForIdle()
        val chip = onNodeWithText("simulator", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(chip.height < 18.dp, "simulator chip text wrapped: height ${chip.height}")
        assertTrue(chip.width > chip.height, "simulator chip squeezed into a column: ${chip.width} x ${chip.height}")
    }

    @Test
    fun emulatorChipStaysOnOneLineNextToLongName() = runComposeUiTest {
        setContent {
            DeviceCard(row("Android SDK built for arm64 with a very long extra name", Platform.ANDROID), detailed = false, selected = false, onClick = {}, modifier = Modifier.width(240.dp))
        }
        waitForIdle()
        val chip = onNodeWithText("emulator", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(chip.height < 18.dp, "emulator chip text wrapped: height ${chip.height}")
    }
}
