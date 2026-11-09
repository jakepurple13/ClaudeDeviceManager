package com.programmersbox.cdm.core.allocator

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SetMatcherTest {
    private val p = AllocDevice(DeviceKey("P"), "Pixel", Category.PHONE, false, listOf("p-usb"))
    private val w = AllocDevice(DeviceKey("W"), "Watch", Category.WATCH, false, listOf("w-usb"))
    private val iPair = AllocDevice(
        DeviceKey("I"), "iPhone", Category.PHONE, false, listOf("I"), platform = Platform.IOS, partner = DeviceKey("IW"),
    )
    private val androidPhone = Wants(setOf(Category.PHONE), platform = Platform.ANDROID)
    private val androidAny = Wants(setOf(Category.PHONE, Category.WATCH), platform = Platform.ANDROID)

    @Test fun assignsOverlappingSpecs() {
        assertEquals(listOf(p, w), SetMatcher.assign(listOf(androidPhone, androidAny), listOf(w, p)))
    }

    @Test fun returnsNullWhenShort() {
        assertNull(SetMatcher.assign(listOf(androidPhone, androidPhone), listOf(p)))
    }

    @Test fun pairCountsOnceAndFitsEitherSide() {
        val iosWatch = Wants(setOf(Category.WATCH), platform = Platform.IOS)
        val iosPhone = Wants(setOf(Category.PHONE), platform = Platform.IOS)
        assertEquals(DeviceKey("IW"), SetMatcher.assign(listOf(iosWatch), listOf(iPair))!!.single().key)
        assertEquals(DeviceKey("I"), SetMatcher.assign(listOf(iosPhone), listOf(iPair))!!.single().key)
        assertNull(SetMatcher.assign(listOf(iosPhone, iosWatch), listOf(iPair)))
    }

    @Test fun maxPartialFindsLargest() {
        val p2 = p.copy(key = DeviceKey("P2"))
        assertEquals(2, SetMatcher.maxPartial(List(3) { androidPhone }, listOf(p, p2)).size)
    }

    @Test fun namesNumberPerPlatformInOrder() {
        val a = Wants.ANY.copy(platform = Platform.ANDROID)
        val i = Wants.ANY.copy(platform = Platform.IOS)
        assertEquals(listOf("android1", "ios1", "android2"), SetMatcher.names(listOf(a, i, a)))
    }

    @Test fun setRequestRejectsOneMemberOrNullPlatform() {
        assertFailsWith<IllegalArgumentException> { SetRequest(listOf(androidPhone)) }
        assertFailsWith<IllegalArgumentException> { SetRequest(listOf(androidPhone, Wants.ANY)) }
    }
}
