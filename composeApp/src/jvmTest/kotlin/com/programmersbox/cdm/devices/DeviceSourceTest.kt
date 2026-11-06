package com.programmersbox.cdm.devices

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DeviceSourceTest {
    private val usb = "38261FDJH00A1Z"
    private fun android() = DeviceTracker(
        FakeAdb("List of devices attached\n$usb device usb:1-1 product:x model:Pixel_8 transport_id:1\n", mapOf(usb to usb)),
    )

    @Test
    fun iosFailureDoesNotDropAndroidDevices() = runTest {
        val devices = DeviceSource(android(), IosTracker(FakeIos(fail = true))).scan()
        assertEquals(listOf(DeviceKey(usb)), devices.map { it.key })
    }

    @Test
    fun iosTrackerThrowingDoesNotDropAndroidDevices() = runTest {
        val broken = IosTracker(object : IosClient {
            override suspend fun bootedSimulatorsJson(): String = throw IllegalStateException("x")
            override suspend fun physicalDevicesJson(): String = throw IllegalStateException("x")
        })
        assertEquals(1, DeviceSource(android(), broken).scan().size)
    }

    @Test
    fun nullIosTrackerMeansAndroidOnly() = runTest {
        val devices = DeviceSource(android(), null).scan()
        assertEquals(listOf(Platform.ANDROID), devices.map { it.platform })
    }

    @Test
    fun mergesAndroidAndIos() = runTest {
        val sims = IosTrackerTest::class.java.getResource("/ios/simctl-booted.json")!!.readText()
        val devices = DeviceSource(android(), IosTracker(FakeIos(sims = sims))).scan()
        assertEquals(4, devices.size)
        assertEquals(Platform.ANDROID, devices.first().platform)
    }
}
