package com.programmersbox.cdm.core.model

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category.Companion.PHONE
import com.programmersbox.cdm.core.model.Category.Companion.TABLET
import com.programmersbox.cdm.core.model.Category.Companion.WATCH
import com.programmersbox.cdm.core.model.Platform.ANDROID
import com.programmersbox.cdm.core.model.Platform.IOS
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WantsTest {
    private fun dev(platform: Platform, virtual: Boolean = false, category: Category = PHONE) =
        AllocDevice(DeviceKey("D"), "D", category, false, listOf("s"), platform, virtual)

    @Test fun anyCategoryFitsEveryPlatformAndKind() {
        assertTrue(Wants.ANY.fits(dev(platform = IOS, virtual = true)))
        assertTrue(Wants.ANY.fits(dev(platform = ANDROID, virtual = false)))
    }

    @Test fun platformLimitIsStrict() { assertFalse(Wants(setOf(PHONE), platform = IOS).fits(dev(ANDROID))) }
    @Test fun realOnlyRejectsSimulator() { assertFalse(Wants(setOf(PHONE), virtual = false).fits(dev(IOS, virtual = true))) }
    @Test fun virtualOnlyRejectsReal() { assertFalse(Wants(setOf(WATCH), virtual = true).fits(dev(ANDROID, category = WATCH))) }
    @Test fun tabletIsItsOwnCategory() { assertFalse(Wants(setOf(PHONE)).fits(dev(IOS, category = TABLET))) }
}
