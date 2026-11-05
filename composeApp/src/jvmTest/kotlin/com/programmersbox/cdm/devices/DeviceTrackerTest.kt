package com.programmersbox.cdm.devices

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class FakeAdb(
    var devices: String,
    val hw: Map<String, String>,
    val watch: Map<String, String> = emptyMap(),
    val models: Map<String, String> = emptyMap(),
) : AdbClient {
    val calls = mutableListOf<String>()
    override suspend fun devicesLong() = devices
    override suspend fun shell(serial: String, vararg args: String): String {
        calls += "$serial ${args.joinToString(" ")}"
        return when {
            "ro.serialno" in args -> hw.getValue(serial)
            "ro.product.model" in args -> models[serial] ?: "Pixel 8"
            "ro.build.version.release" in args -> "15"
            // real adb: has-feature prints output but exits 1 when feature absent
            "pm" in args -> throw java.io.IOException("adb failed (1): ${watch[serial] ?: "false"}")
            else -> ""
        }
    }

    override suspend fun shellOutput(serial: String, vararg args: String): String {
        calls += "$serial output ${args.joinToString(" ")}"
        return if ("pm" in args) watch[serial] ?: "false" else shell(serial, *args)
    }
}

class DeviceTrackerTest {
    private val usb = "38261FDJH00A1Z"
    private val wifi = "192.168.1.20:41235"

    @Test
    fun groupsUsbAndWifiByHardwareSerial() = runTest {
        val adb = FakeAdb(
            "List of devices attached\n$wifi device product:x model:Pixel_8 transport_id:2\n$usb device usb:1-1 product:x model:Pixel_8 transport_id:1\n",
            mapOf(usb to usb, wifi to usb),
        )
        val d = DeviceTracker(adb).scan().single()
        assertEquals(DeviceKey(usb), d.key)
        assertEquals(listOf(usb, wifi), d.serials)
        assertEquals("Pixel 8", d.model)
        assertEquals("15", d.androidVersion)
        assertEquals(Category.PHONE, d.detectedCategory)
    }

    @Test
    fun detectsWatchFeature() = runTest {
        val adb = FakeAdb("List of devices attached\nW1 device usb:1\nP1 device usb:2\n", mapOf("W1" to "W1", "P1" to "P1"), mapOf("W1" to "true\n"))
        val byKey = DeviceTracker(adb).scan().associateBy { it.key.hardwareSerial }
        assertEquals(Category.WATCH, byKey.getValue("W1").detectedCategory)
        assertEquals(Category.PHONE, byKey.getValue("P1").detectedCategory)
    }

    @Test
    fun skipsUnauthorized() = runTest {
        val adb = FakeAdb("List of devices attached\nXYZ unauthorized usb:1\nA device usb:2\nB offline\n", mapOf("A" to "A"))
        assertEquals(listOf("A"), DeviceTracker(adb).scan().map { it.key.hardwareSerial })
    }

    @Test
    fun wifiSerialChangeKeepsIdentity() = runTest {
        val adb = FakeAdb("List of devices attached\n192.168.1.20:41235 device\n", mapOf("192.168.1.20:41235" to "HW", "192.168.1.20:39001" to "HW"))
        val tracker = DeviceTracker(adb)
        val first = tracker.scan().single()
        adb.devices = "List of devices attached\n192.168.1.20:39001 device\n"
        val second = tracker.scan().single()
        assertEquals(first.key, second.key)
        assertEquals(listOf("192.168.1.20:39001"), second.serials)
    }

    @Test
    fun cachesPropsAndSkipsFailingSerial() = runTest {
        val adb = FakeAdb("List of devices attached\nA device\nBAD device\n", mapOf("A" to "A"))
        val t = DeviceTracker(adb)
        t.scan()
        val n = adb.calls.count { it.startsWith("A ") }
        assertEquals(listOf("A"), t.scan().map { it.key.hardwareSerial })
        assertEquals(n, adb.calls.count { it.startsWith("A ") })
    }

    @Test
    fun phoneKeptWhenHasFeatureExitsNonZero() = runTest {
        val adb = FakeAdb("List of devices attached\nP1 device\n", mapOf("P1" to "P1"))
        val d = DeviceTracker(adb).scan().single()
        assertEquals(Category.PHONE, d.detectedCategory)
    }

    @Test
    fun failingSerialIsNegativeCachedFor30s() = runTest {
        val adb = FakeAdb("List of devices attached\nBAD device\n", emptyMap())
        var now = 1_000L
        val t = DeviceTracker(adb) { now }
        assertEquals(emptyList(), t.scan())
        val n = adb.calls.size
        assertEquals(1, n)
        now += 29_000
        t.scan()
        assertEquals(n, adb.calls.size)
        now += 2_000
        t.scan()
        assertEquals(n + 1, adb.calls.size)
    }

    @Test
    fun groupingPrefersUsbRowRegardlessOfOrder() = runTest {
        val adb = FakeAdb(
            "List of devices attached\n$wifi device\n$usb device\n",
            mapOf(usb to usb, wifi to usb),
            watch = mapOf(wifi to "true"),
            models = mapOf(wifi to "WifiModel", usb to "UsbModel"),
        )
        val d = DeviceTracker(adb).scan().single()
        assertEquals("UsbModel", d.model)
        assertEquals(Category.PHONE, d.detectedCategory)
    }
}
