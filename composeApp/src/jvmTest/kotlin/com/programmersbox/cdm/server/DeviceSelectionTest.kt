package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `cdm acquire --device` resolution and the `cdm devices` listing. */
class DeviceSelectionTest {
    private val pixel = AllocDevice(DeviceKey("HW-PIXEL"), "Pixel 8", Category.PHONE, false, listOf("39071FDJH00A2K"))
    private val emu = AllocDevice(DeviceKey("emu-hw"), "Pixel 8", Category.PHONE, false, listOf("emulator-5554"), virtual = true)
    private val watch = AllocDevice(DeviceKey("W"), "Galaxy Watch", Category.WATCH, true, listOf("w1"))
    private val sim = AllocDevice(DeviceKey("ABCD-1234"), "iPhone 16 Pro", Category.PHONE, false, listOf("ABCD-1234"), Platform.IOS, virtual = true)
    private val offline = AllocDevice(DeviceKey("OFF"), "Pixel 6", Category.PHONE, false, emptyList())
    private val devices = listOf(pixel, emu, watch, sim, offline)

    private fun want(asked: String, cats: Set<Category> = emptySet(), platform: Platform? = null, virtual: Boolean? = null) =
        deviceWants(asked, devices, cats, platform, virtual)

    @Test fun matchesAdbSerialHardwareSerialAndUdid() {
        assertEquals(Wants(setOf(Category.ANY), Platform.ANDROID, null, pixel.key), want("39071FDJH00A2K").getOrThrow())
        assertEquals(pixel.key, want("hw-pixel").getOrThrow().device)
        assertEquals(Wants(setOf(Category.ANY), Platform.IOS, null, sim.key), want("abcd-1234").getOrThrow())
    }

    @Test fun matchesUniqueNameCaseInsensitive() {
        assertEquals(sim.key, want("iphone 16 pro").getOrThrow().device)
    }

    @Test fun offlineDeviceIsAccepted() {
        assertEquals(offline.key, want("OFF").getOrThrow().device)
    }

    @Test fun keepsGivenFlagsThatFit() {
        assertEquals(Wants(setOf(Category.PHONE), Platform.ANDROID, true, emu.key), want("emulator-5554", setOf(Category.PHONE), Platform.ANDROID, true).getOrThrow())
    }

    @Test fun refusals() {
        assertTrue("No device matches \"nope\"" in want("nope").exceptionOrNull()!!.message!!)
        assertTrue("More than one device is named \"pixel 8\"" in want("pixel 8").exceptionOrNull()!!.message!!)
        assertTrue("marked don't use" in want("w1").exceptionOrNull()!!.message!!)
        assertTrue("rule it out" in want("39071FDJH00A2K", platform = Platform.IOS).exceptionOrNull()!!.message!!)
        assertTrue("rule it out" in want("39071FDJH00A2K", virtual = true).exceptionOrNull()!!.message!!)
        assertTrue("rule it out" in want("39071FDJH00A2K", setOf(Category.WATCH)).exceptionOrNull()!!.message!!)
    }

    private fun manager(holds: Map<DeviceKey, HolderId> = emptyMap()) = object : FakeManager() {
        override val snapshot = kotlinx.coroutines.flow.MutableStateFlow(AllocatorSnapshot(devices, holds, emptyList(), emptyMap()))
    }

    @Test fun acquireRouteResolvesDevice() {
        val m = manager()
        testApplication {
            application { cdmModule(m) }
            client.post("/cdm/acquire?holder=s:main&cwd=/x&device=ABCD-1234").bodyAsText()
            assertEquals(Wants(setOf(Category.ANY), Platform.IOS, null, sim.key), m.lastWants)
            val bad = client.post("/cdm/acquire?holder=s:main&cwd=/x&device=nope")
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue("cdm devices" in bad.bodyAsText())
        }
    }

    @Test fun listMarksStates() {
        val me = HolderId("s", "main")
        val text = deviceListText(AllocatorSnapshot(devices, mapOf(pixel.key to me, sim.key to HolderId("other")), emptyList(), emptyMap()), me)
        val lines = text.lines().filter { it.isNotBlank() }
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("39071FDJH00A2K  Pixel 8 · Android real · phone · yours"), lines[0])
        assertTrue(lines[1].endsWith("Pixel 8 · Android emulator · phone · free"), lines[1])
        assertTrue(lines[2].endsWith("don't use"), lines[2])
        assertTrue(lines[3].endsWith("iPhone 16 Pro · iOS simulator · phone · in use by other:main"), lines[3])
        assertTrue(lines[4].startsWith("OFF ") && lines[4].endsWith("offline"), lines[4])
        assertEquals("No devices.\n", deviceListText(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap()), null))
    }

    @Test fun listRouteWorksWithoutHolder() {
        testApplication {
            application { cdmModule(manager()) }
            val body = client.get("/cdm/list").bodyAsText()
            assertTrue("iPhone 16 Pro" in body, body)
        }
    }
}
