package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.service.DeviceManager
import com.programmersbox.cdm.service.HolderInfo
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The integration surface for apps other than Claude Code: the guide page (`/`), the agent brief (`/llms.txt`),
 * the device list (`/cdm/devices`), its live stream (`/cdm/events`) and `/cdm/renew`.
 */
internal fun Route.integrationRoutes(manager: DeviceManager, eventsHeartbeat: Duration = 15.seconds) {
    get("/") { call.respondText(resource("integrations/index.html"), ContentType.Text.Html) }
    get("/integrations") { call.respondText(resource("integrations/index.html"), ContentType.Text.Html) }
    get("/llms.txt") {
        val port = call.request.local.localPort
        call.respondText(resource("integrations/llms.txt").replace("{{PORT}}", port.toString()), ContentType.Text.Plain)
    }
    get("/cdm/devices") {
        call.respondText(devicesJson(manager.snapshot.value, manager.holderInfo.value).toString(), ContentType.Application.Json)
    }
    get("/cdm/events") {
        call.response.cacheControl(CacheControl.NoCache(null))
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.respondTextWriter(ContentType.Text.EventStream) {
            val out = Mutex()
            suspend fun send(s: String) = out.withLock { write(s); flush() }
            coroutineScope {
                // A comment line every so often: a write to a closed client fails, which ends this response.
                launch {
                    while (true) {
                        delay(eventsHeartbeat)
                        send(": ping\n\n")
                    }
                }
                manager.snapshot.combine(manager.holderInfo, ::devicesJson)
                    .distinctUntilChanged()
                    .collect { send("event: devices\ndata: $it\n\n") }
            }
        }
    }
    post("/cdm/renew") {
        val holder = call.holder() ?: return@post call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
        val platform = call.platform().getOrElse { return@post call.respondText("bad platform\n", status = HttpStatusCode.BadRequest) }
        call.respondText(if (manager.renew(holder, platform)) "RENEWED\n" else "NONE\n")
    }
}

/** Every device and the queue, as `/cdm/devices` and `/cdm/events` send them. */
internal fun devicesJson(snap: AllocatorSnapshot, info: Map<HolderId, HolderInfo>): JsonObject = buildJsonObject {
    putJsonArray("devices") {
        for (d in snap.devices) addJsonObject {
            put("key", d.key.hardwareSerial)
            put("name", d.name)
            put("platform", d.platform.name.lowercase())
            put("category", d.category.id)
            put("virtual", d.virtual)
            put("online", d.connectedSerials.isNotEmpty())
            put("dontUse", d.dontUse)
            put("serial", d.connectedSerials.firstOrNull() ?: d.key.hardwareSerial)
            val holder = snap.holds[d.key]
            if (holder == null) put("holder", null as String?) else put("holder", holderJson(holder, info[holder]))
        }
    }
    putJsonArray("queue") {
        snap.queue.forEachIndexed { i, h ->
            addJsonObject {
                put("position", i + 1)
                put("holder", holderJson(h, info[h]))
                val wants = (snap.statuses[h] as? HolderStatus.Queued)?.wants
                if (wants != null) {
                    putJsonArray("categories") { wants.categories.sortedBy { it.id }.forEach { add(it.id) } }
                    put("platform", wants.platform?.name?.lowercase())
                    put("virtual", wants.virtual)
                }
            }
        }
    }
}

private fun holderJson(h: HolderId, info: HolderInfo?) = buildJsonObject {
    put("id", h.key)
    // Claude sessions are named by their project folder; an integration has no cwd, so its own id is its name.
    put("label", info?.cwd?.takeIf { it.isNotBlank() }?.let { Path.of(it).fileName?.toString() } ?: h.key)
    put("agent", h.agentId.takeIf { it != HolderId.MAIN })
}

private fun resource(path: String): String =
    CdmServer::class.java.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }
        ?: error("missing resource $path")
