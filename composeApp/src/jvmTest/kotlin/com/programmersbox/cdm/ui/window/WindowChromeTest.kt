package com.programmersbox.cdm.ui.window

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowChromeTest {
    @Test
    fun unsupportedRuntimeFallsBackToSystem() {
        assertEquals(WindowChrome.System, windowChrome(supported = false))
    }

    @Test
    fun supportedUsesCustom() {
        assertEquals(WindowChrome.Custom, windowChrome(supported = true))
    }

    @Test
    fun barColorsFromScheme() {
        assertEquals(darkColorScheme().surfaceContainer, barColors(darkColorScheme()).background)
        assertEquals(lightColorScheme().onSurface, barColors(lightColorScheme()).content)
    }

    @Test
    fun jewelNeedsJbr25() {
        assertTrue(jewelUsable(jbrAvailable = true, javaFeature = 25))
        assertFalse(jewelUsable(jbrAvailable = true, javaFeature = 21))
        assertFalse(jewelUsable(jbrAvailable = false, javaFeature = 25))
    }
}
