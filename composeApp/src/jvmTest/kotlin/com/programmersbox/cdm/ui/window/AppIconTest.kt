package com.programmersbox.cdm.ui.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AppIconTest {
    @Test
    fun iconResourceLoads() {
        val painter = assertNotNull(AppIcon.painter)
        assertEquals(256f, painter.intrinsicSize.width)
    }
}
