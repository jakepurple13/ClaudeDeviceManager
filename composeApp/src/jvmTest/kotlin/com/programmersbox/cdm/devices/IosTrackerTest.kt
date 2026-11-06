package com.programmersbox.cdm.devices

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun fixture(name: String) =
    IosTrackerTest::class.java.getResource("/ios/$name")!!.readText()

class FakeIos(
    var sims: String = """{"devices":{}}""",
    var physical: String = """{"result":{"devices":[]}}""",
    var fail: Boolean = false,
) : IosClient {
    var simCalls = 0
    var physCalls = 0
    override suspend fun bootedSimulatorsJson(): String {
        simCalls++
        if (fail) throw java.io.IOException("xcrun missing")
        return sims
    }

    override suspend fun physicalDevicesJson(): String {
        physCalls++
        if (fail) throw java.io.IOException("xcrun missing")
        return physical
    }
}

class IosTrackerTest {
    private val sims = fixture("simctl-booted.json")
    private val real = fixture("devicectl-devices.json")

    @Test
    fun parsesBootedSimulatorsWithRuntimeInName() = runTest {
        val d = IosTracker(FakeIos(sims = sims)).scan().first { it.key == DeviceKey("11111111-1111-1111-1111-111111111111") }
        assertEquals("iPhone 16 Pro · iOS 18.2", d.model)
        assertEquals("18.2", d.osVersion)
        assertEquals(Category.PHONE, d.detectedCategory)
        assertEquals(Platform.IOS, d.platform)
        assertTrue(d.virtual)
        assertEquals("simulator", d.transport)
        assertEquals(listOf("11111111-1111-1111-1111-111111111111"), d.serials)
    }

    @Test
    fun ipadSimulatorIsTablet() = runTest {
        val d = IosTracker(FakeIos(sims = sims)).scan().first { it.key == DeviceKey("22222222-2222-2222-2222-222222222222") }
        assertEquals(Category.TABLET, d.detectedCategory)
    }

    @Test
    fun watchSimulatorIsWatch() = runTest {
        val d = IosTracker(FakeIos(sims = sims)).scan().first { it.key == DeviceKey("44444444-4444-4444-4444-444444444444") }
        assertEquals(Category.WATCH, d.detectedCategory)
        assertEquals("Apple Watch Series 10 (46mm) · watchOS 11.2", d.model)
    }

    @Test
    fun shutdownAndTvSimulatorsAreIgnored() = runTest {
        val keys = IosTracker(FakeIos(sims = sims)).scan().map { it.key.hardwareSerial }
        assertEquals(
            setOf("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222", "44444444-4444-4444-4444-444444444444"),
            keys.toSet(),
        )
    }

    @Test
    fun parsesPhysicalIphoneIpadWatch() = runTest {
        val all = IosTracker(FakeIos(physical = real)).scan()
        assertEquals(
            listOf("00008000-000000000000A001", "00008000-000000000000A002", "00008000-000000000000A003", "00008000-000000000000A006"),
            all.map { it.key.hardwareSerial },
        ) // unavailable and unpaired devices are excluded; an idle ("disconnected") plugged-in one is included
        assertEquals(listOf(Category.PHONE, Category.TABLET, Category.WATCH, Category.PHONE), all.map { it.detectedCategory })
        assertTrue(all.none { it.virtual })
        assertTrue(all.all { it.platform == Platform.IOS })
        // wired -> usb, localNetwork -> wifi, missing -> usb
        assertEquals(listOf("usb", "wifi", "usb", "usb"), all.map { it.transport })
        assertEquals("Test iPhone", all[0].model)
        assertEquals("26.2", all[0].osVersion)
    }

    /** Final whole-branch review: devicectl reports an idle, plugged-in, paired device with tunnelState "disconnected". */
    @Test
    fun idleDisconnectedPairedDeviceIsIncluded() = runTest {
        val idle = IosTracker(FakeIos(physical = real)).scan().single { it.key.hardwareSerial == "00008000-000000000000A006" }
        assertEquals("Idle iPhone", idle.model)
        assertEquals("usb", idle.transport)
    }

    /** Final whole-branch review: one malformed entry drops only itself, not the whole source. */
    @Test
    fun malformedEntriesAreSkipped() = runTest {
        val good = """{"connectionProperties":{"pairingState":"paired","tunnelState":"connected"},""" +
            """"deviceProperties":{"name":"Good"},"hardwareProperties":{"deviceType":"iPhone","udid":"00008000-0000000000000001"}}"""
        val physical = """{"result":{"devices":[
            42,
            "text",
            {"connectionProperties":"oops"},
            {"connectionProperties":{"pairingState":["paired"],"tunnelState":"connected"},"hardwareProperties":{"deviceType":"iPhone","udid":"x"}},
            {"connectionProperties":{"pairingState":"paired","tunnelState":"connected"},"hardwareProperties":{"deviceType":"iPhone","udid":{"n":1}}},
            {"connectionProperties":{"pairingState":"paired","tunnelState":"connected"},"deviceProperties":{"name":{"x":1}},"hardwareProperties":{"deviceType":"iPhone","udid":"00008000-0000000000000002"}},
            $good
        ]}}"""
        val sims = """{"devices":{"com.apple.CoreSimulator.SimRuntime.iOS-18-2":[
            7,
            {"state":["Booted"],"udid":"BAD"},
            {"state":"Booted","udid":{"x":1}},
            {"state":"Booted","udid":"11111111-1111-1111-1111-111111111111","name":"iPhone 16","deviceTypeIdentifier":"x.iPhone-16"}
        ]}}"""
        val all = IosTracker(FakeIos(sims = sims, physical = physical)).scan()
        assertEquals(
            listOf("11111111-1111-1111-1111-111111111111", "00008000-0000000000000002", "00008000-0000000000000001"),
            all.map { it.key.hardwareSerial },
        )
    }

    @Test
    fun partnerIsNullWhenNotExposed() = runTest {
        // devicectl list devices exposes no watch -> iPhone field (spike); both sides stay null.
        assertTrue(IosTracker(FakeIos(physical = real)).scan().all { it.partner == null })
        assertNull(IosTracker(FakeIos(sims = sims)).scan().first().partner)
    }

    @Test
    fun clientFailureYieldsEmptyAndBacksOff30s() = runTest {
        var now = 1_000L
        val client = FakeIos(fail = true)
        val t = IosTracker(client) { now }
        assertEquals(emptyList(), t.scan())
        assertEquals(1, client.simCalls)
        assertEquals(1, client.physCalls)
        now += 29_000
        t.scan()
        assertEquals(1, client.simCalls)
        assertEquals(1, client.physCalls)
        now += 1_001
        client.fail = false
        client.sims = sims
        assertEquals(3, t.scan().size)
        assertEquals(2, client.simCalls)
        assertEquals(2, client.physCalls)
    }

    @Test
    fun simulatorFailureDoesNotDropPhysicalDevices() = runTest {
        val client = object : IosClient {
            override suspend fun bootedSimulatorsJson(): String = error("boom")
            override suspend fun physicalDevicesJson() = real
        }
        assertEquals(4, IosTracker(client).scan().size)
    }

    @Test
    fun pollsAtMostOnceEvery5s() = runTest {
        var now = 1_000L
        val client = FakeIos(sims = sims)
        val t = IosTracker(client) { now }
        assertEquals(3, t.scan().size)
        now += 2_000
        assertEquals(3, t.scan().size)
        assertEquals(1, client.simCalls)
        assertEquals(1, client.physCalls)
        now += 3_000
        t.scan()
        assertEquals(2, client.simCalls)
        assertEquals(2, client.physCalls)
    }
}
