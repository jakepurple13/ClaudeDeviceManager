package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.service.HolderInfo
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class IntegrationRoutesTest {
    private val claude = HolderId("sess-1")
    private val ext = HolderId("scrcpy-vwr", "main")
    private val waiting = HolderId("sess-2", "agent-7")

    private fun app(m: FakeManager, block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { cdmModule(m) }
            block()
        }

    private fun populated() = FakeManager().apply {
        snapshotFlow.value = AllocatorSnapshot(
            devices = listOf(
                AllocDevice(DeviceKey("P"), "Pixel 8", Category.PHONE, false, listOf("p-usb")),
                AllocDevice(DeviceKey("W"), "Pixel Watch", Category.WATCH, false, listOf("w-usb")),
                AllocDevice(DeviceKey("emulator-5554"), "heads_up", Category.PHONE, false, listOf("emulator-5554"), virtual = true),
                AllocDevice(DeviceKey("T"), "Tab", Category.TABLET, true, emptyList()),
            ),
            holds = mapOf(DeviceKey("P") to claude, DeviceKey("W") to ext),
            queue = listOf(waiting),
            statuses = mapOf(waiting to HolderStatus.Queued(1, Wants(setOf(Category.WATCH), Platform.ANDROID, false))),
        )
        holderInfoFlow.value = mapOf(claude to HolderInfo(claude, "/Users/me/code/my-app", null, null, Instant.fromEpochMilliseconds(0), false))
    }

    private fun obj(body: String) = Json.parseToJsonElement(body).jsonObject

    @Test
    fun devicesListsHoldersAndQueue() = app(populated()) {
        val r = client.get("/cdm/devices")
        assertEquals(ContentType.Application.Json, r.contentType()?.withoutParameters())
        val o = obj(r.bodyAsText())
        val devices = o["devices"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("P", "W", "emulator-5554", "T"), devices.map { it["key"]!!.jsonPrimitive.content })

        val p = devices[0]
        assertEquals("p-usb", p["serial"]!!.jsonPrimitive.content)
        assertEquals("android", p["platform"]!!.jsonPrimitive.content)
        assertEquals("phone", p["category"]!!.jsonPrimitive.content)
        val pHolder = p["holder"]!!.jsonObject
        assertEquals("sess-1:main", pHolder["id"]!!.jsonPrimitive.content)
        assertEquals("my-app", pHolder["label"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, pHolder["agent"])

        // An integration has no cwd, so it is labelled by its own id.
        assertEquals("scrcpy-vwr:main", devices[1]["holder"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertTrue(devices[2]["virtual"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, devices[2]["holder"])
        val tab = devices[3]
        assertTrue(tab["dontUse"]!!.jsonPrimitive.boolean)
        assertEquals(false, tab["online"]!!.jsonPrimitive.boolean)
        assertEquals("T", tab["serial"]!!.jsonPrimitive.content)

        val q = o["queue"]!!.jsonArray.single().jsonObject
        assertEquals(1, q["position"]!!.jsonPrimitive.int)
        assertEquals("agent-7", q["holder"]!!.jsonObject["agent"]!!.jsonPrimitive.content)
        assertEquals(listOf("watch"), q["categories"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("android", q["platform"]!!.jsonPrimitive.content)
        assertEquals(false, q["virtual"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun renewReportsWhetherAnythingWasHeld() {
        val m = FakeManager()
        app(m) {
            assertEquals("NONE", client.post("/cdm/renew?holder=scrcpy-vwr:main").bodyAsText().trim())
            m.renewImpl = { _, _ -> true }
            assertEquals("RENEWED", client.post("/cdm/renew?holder=scrcpy-vwr:main&platform=ios").bodyAsText().trim())
            assertEquals(listOf(ext to null, ext to Platform.IOS), m.renewed)
            assertEquals(HttpStatusCode.BadRequest, client.post("/cdm/renew").status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/cdm/renew?holder=x&platform=windows").status)
        }
    }

    @Test
    fun guidePageIsServedAtRootAndIntegrations() = app(FakeManager()) {
        for (path in listOf("/", "/integrations")) {
            val r = client.get(path)
            assertEquals(HttpStatusCode.OK, r.status, path)
            assertEquals(ContentType.Text.Html, r.contentType()?.withoutParameters(), path)
            val body = r.bodyAsText()
            assertTrue("<title>CDM Integrations</title>" in body, path)
            assertTrue("/cdm/events" in body, path)
        }
    }

    @Test
    fun llmsTxtFillsInPort() = app(FakeManager()) {
        val body = client.get("/llms.txt").bodyAsText()
        assertTrue(body.startsWith("# Claude Device Manager integration brief"))
        assertTrue("{{PORT}}" !in body)
        assertTrue("POST /cdm/renew" in body)
    }

    @Test
    fun eventsStreamStartsWithCurrentDevices() = app(populated()) {
        client.prepareGet("/cdm/events").execute { r ->
            assertEquals(ContentType.Text.EventStream, r.contentType()?.withoutParameters())
            val ch = r.bodyAsChannel()
            assertEquals("event: devices", ch.readUTF8Line())
            val data = assertNotNull(ch.readUTF8Line())
            assertTrue(data.startsWith("data: "))
            val o = obj(data.removePrefix("data: "))
            assertEquals(4, o["devices"]!!.jsonArray.size)
            assertTrue(o["queue"] is kotlinx.serialization.json.JsonArray)
        }
    }

    @Test
    fun devicesJsonIsEmptyWithNoDevices() {
        val o: JsonObject = devicesJson(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap()), emptyMap())
        assertEquals(0, o["devices"]!!.jsonArray.size)
        assertEquals(0, o["queue"]!!.jsonArray.size)
    }
}
