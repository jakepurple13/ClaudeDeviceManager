package com.programmersbox.cdm.sample

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything an app needs to talk to Claude Device Manager (CDM). Each function is one HTTP call.
 *
 * [holder] names this app to CDM, as "<app-name>:<slot>". Keep it the same across launches.
 * [onWire] gets every line sent and received, so the sample can show it in its log.
 */
class CdmClient(val holder: String, private val onWire: (String) -> Unit = {}) {

    private val http = HttpClient(CIO) {
        install(SSE)
        // acquire and events stay open for a long time, so turn off the default 15 second limit.
        engine { requestTimeout = 0 }
    }

    /** CDM writes its port here when it starts. No file means CDM is not running. */
    private val baseUrl: String?
        get() {
            val portFile = File(System.getProperty("user.home"), ".claude-device-manager/port")
            val port = portFile.takeIf { it.exists() }?.readText()?.trim() ?: return null
            return "http://127.0.0.1:$port"
        }

    /** GET /cdm/ping: true when CDM is running. */
    suspend fun ping(): Boolean {
        val base = baseUrl ?: return false
        onWire("→ GET /cdm/ping")
        val reply = runCatching { http.get("$base/cdm/ping").bodyAsText().trim() }.getOrNull()
        onWire("← ${reply ?: "no answer"}")
        return reply == "cdm-ok"
    }

    /** GET /cdm/devices: every device, who holds it, and who is waiting. */
    suspend fun devices(): DeviceList {
        onWire("→ GET /cdm/devices")
        val list = json.decodeFromString<DeviceList>(http.get("${base()}/cdm/devices").bodyAsText())
        onWire("← ${list.devices.size} devices, ${list.queue.size} waiting")
        return list
    }

    /** GET /cdm/events: the same list as [devices], sent again every time something changes. */
    fun events(): Flow<DeviceList> = channelFlow {
        onWire("→ GET /cdm/events (live)")
        http.sse("${base()}/cdm/events") {
            incoming.collect { event ->
                if (event.event == "devices") send(json.decodeFromString<DeviceList>(event.data.orEmpty()))
            }
        }
    }

    /**
     * POST /cdm/acquire: wait in line for a device. All filters are optional.
     *
     * CDM answers line by line while we wait. [onQueued] gets our place in line.
     * To give up, cancel the coroutine and call [release]: release takes us out of the line at once.
     */
    suspend fun acquire(
        category: String = "any",       // phone, watch, tablet, phone,watch or any
        platform: String? = null,       // android, ios, or null for either
        virtual: Boolean? = null,       // true for emulators/simulators, false for real devices, null for either
        device: String? = null,         // one specific device, by the "serial" from devices()
        onQueued: (position: Int) -> Unit = {},
    ): AcquireResult {
        val request = http.preparePost("${base()}/cdm/acquire") {
            parameter("holder", holder)
            parameter("category", category)
            parameter("platform", platform)
            parameter("virtual", virtual)
            parameter("device", device)
        }
        onWire("→ POST /cdm/acquire?category=$category" + listOfNotNull(
            platform?.let { "&platform=$it" }, virtual?.let { "&virtual=$it" }, device?.let { "&device=$it" },
        ).joinToString(""))

        return request.execute { response ->
            // A bad request (an unknown device, say) comes back as HTTP 400 with a one-line reason.
            if (!response.status.isSuccess()) {
                val reason = response.bodyAsText().trim()
                onWire("← ${response.status.value} $reason")
                return@execute AcquireResult.Error(reason)
            }
            val body = response.bodyAsChannel()
            var result: AcquireResult? = null
            while (result == null) {
                val line = body.readLine() ?: break
                if (line == ".") continue // heartbeat every 5 seconds, nothing to do
                onWire("← $line")
                val words = line.split(" ")
                when (words[0]) {
                    "QUEUED" -> onQueued(words[1].toInt())
                    "GRANTED" -> result = AcquireResult.Granted(
                        platform = words[1],
                        serial = words[2],
                        name = words.drop(3).joinToString(" "), // the name may have spaces
                    )
                    "TIMEOUT" -> result = AcquireResult.TimedOut
                    "BLOCKED", "REMOVED" -> result = AcquireResult.Refused
                }
            }
            result ?: AcquireResult.Error("CDM closed the connection")
        }
    }

    /**
     * POST /cdm/renew: "I am still using my device". CDM can't see our adb commands, so without this
     * it hands the device to someone else after its idle time (15 min by default). Call it every 60s.
     * Returns false when we no longer hold anything.
     */
    suspend fun renew(): Boolean = postText("/cdm/renew") == "RENEWED"

    /** POST /cdm/release: give the device back (also leaves the line if we are waiting). */
    suspend fun release(): Boolean = postText("/cdm/release") == "RELEASED"

    /** GET /cdm/status: what we hold ("HOLDING android <serial> <name>"), our place in line, or "NONE". */
    suspend fun status(): List<String> {
        onWire("→ GET /cdm/status")
        val lines = http.get("${base()}/cdm/status") { parameter("holder", holder) }.bodyAsText().lines().filter { it.isNotBlank() }
        lines.forEach { onWire("← $it") }
        return lines
    }

    private suspend fun postText(path: String): String {
        onWire("→ POST $path")
        val reply = http.post("${base()}$path") { parameter("holder", holder) }.bodyAsText().trim()
        onWire("← $reply")
        return reply
    }

    private fun base(): String = baseUrl ?: error("Claude Device Manager is not running")

    private val json = Json { ignoreUnknownKeys = true }
}

sealed interface AcquireResult {
    /** We hold the device. [serial] is what to pass to `adb -s` (or the iOS UDID). */
    data class Granted(val platform: String, val serial: String, val name: String) : AcquireResult

    /** Waited the whole timeout (110s by default) and still in line. Call acquire again to keep waiting. */
    data object TimedOut : AcquireResult

    /** The user blocked this app, or removed it from the line, in CDM. Stop and tell the user. */
    data object Refused : AcquireResult

    data class Error(val reason: String) : AcquireResult
}

/** The JSON from GET /cdm/devices and GET /cdm/events. */
@Serializable
data class DeviceList(val devices: List<Device> = emptyList(), val queue: List<Waiting> = emptyList())

@Serializable
data class Device(
    val name: String,
    val serial: String,
    val platform: String,
    val category: String,
    val virtual: Boolean = false,
    val online: Boolean = true,
    val dontUse: Boolean = false,
    val holder: Holder? = null,
)

@Serializable
data class Holder(val id: String, val label: String)

@Serializable
data class Waiting(val position: Int, val holder: Holder)
