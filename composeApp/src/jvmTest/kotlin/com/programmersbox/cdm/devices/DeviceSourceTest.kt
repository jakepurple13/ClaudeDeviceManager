package com.programmersbox.cdm.devices

import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class DeviceSourceTest {
    private val usb = "38261FDJH00A1Z"
    private fun android() = DeviceTracker(
        FakeAdb("List of devices attached\n$usb device usb:1-1 product:x model:Pixel_8 transport_id:1\n", mapOf(usb to usb)),
    )

    @Test
    fun iosFailureDoesNotDropAndroidDevices() = runTest {
        val devices = DeviceSource(android(), IosTracker(FakeIos(fail = true)), backgroundScope).scan()
        assertEquals(listOf(DeviceKey(usb)), devices.map { it.key })
    }

    @Test
    fun iosTrackerThrowingDoesNotDropAndroidDevices() = runTest {
        val broken = IosTracker(object : IosClient {
            override suspend fun bootedSimulatorsJson(): String = throw IllegalStateException("x")
            override suspend fun physicalDevicesJson(): String = throw IllegalStateException("x")
        })
        assertEquals(1, DeviceSource(android(), broken, backgroundScope).scan().size)
    }

    @Test
    fun nullIosTrackerMeansAndroidOnly() = runTest {
        val devices = DeviceSource(android(), null, backgroundScope).scan()
        assertEquals(listOf(Platform.ANDROID), devices.map { it.platform })
    }

    @Test
    fun mergesAndroidAndIos() = runTest {
        val sims = IosTrackerTest::class.java.getResource("/ios/simctl-booted.json")!!.readText()
        val devices = DeviceSource(android(), IosTracker(FakeIos(sims = sims)), backgroundScope).scan()
        assertEquals(4, devices.size)
        assertEquals(Platform.ANDROID, devices.first().platform)
    }

    @Test
    fun hungIosClientDoesNotStallAndroid() = runTest {
        val hung = object : IosClient {
            override suspend fun bootedSimulatorsJson(): String { delay(10 * 60_000L); return "{}" }
            override suspend fun physicalDevicesJson(): String { delay(10 * 60_000L); return "{}" }
        }
        val source = DeviceSource(android(), IosTracker(hung), backgroundScope)
        val start = testScheduler.currentTime
        assertEquals(listOf(DeviceKey(usb)), source.scan().map { it.key })
        assertTrue(testScheduler.currentTime - start < 1_000)
        // a second tick while the first iOS scan is still hung neither waits nor stacks another scan
        val t2 = testScheduler.currentTime
        assertEquals(1, source.scan().size)
        assertTrue(testScheduler.currentTime - t2 < 1_000)
    }
}
