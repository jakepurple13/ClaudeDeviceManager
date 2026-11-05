package com.jacobrein.cdm.server

import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.service.AcquireResult
import com.jacobrein.cdm.service.DeviceManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the HTTP server's startup failure (e.g. port in use) for the UI to show. */
class ServerStatus {
    private val _error = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _error
    fun fail(message: String) { _error.value = message }
}

const val DEFAULT_ACQUIRE_TIMEOUT_SEC = 570L

fun defaultPortFile(): Path = Path(System.getProperty("user.home"), ".claude-device-manager", "port")

/** Hooks and `cdm` talk to this. Binds 127.0.0.1 only. Start it only after the service's `start()`. */
class CdmServer(
    private val manager: DeviceManager,
    private val port: Int,
    private val portFile: Path = defaultPortFile(),
    private val heartbeat: Duration = 5.seconds,
) {
    private var engine: EmbeddedServer<*, *>? = null

    /** Binds, then writes the port file. Returns the bound port. */
    suspend fun start(): Int {
        val e = embeddedServer(CIO, port = port, host = "127.0.0.1") { cdmModule(manager, heartbeat) }
        e.start(wait = false)
        engine = e
        val bound = e.engine.resolvedConnectors().first().port
        Files.createDirectories(portFile.parent)
        Files.writeString(portFile, bound.toString())
        return bound
    }

    fun stop() {
        engine?.stop(100, 1000)
        engine = null
        runCatching { Files.deleteIfExists(portFile) }
    }
}

private val EMPTY = "{}"

fun Application.cdmModule(manager: DeviceManager, heartbeat: Duration = 5.seconds) {
    routing {
        get("/cdm/ping") { call.respondText("cdm-ok\n") }
        post("/hooks/pre-tool-use") {
            hook {
                val p = HookJson.parse(call.receiveText()) ?: return@hook EMPTY
                if (p.input.hookEventName != "PreToolUse") return@hook EMPTY
                HookJson.render(manager.preToolUse(p.input), p.toolInput).toString()
            }
        }
        post("/hooks/subagent-stop") {
            hook {
                val p = HookJson.parse(call.receiveText())
                val agent = p?.input?.agentId
                if (p != null && agent != null) manager.subagentStop(p.input.sessionId, agent)
                EMPTY
            }
        }
        post("/hooks/session-end") {
            hook {
                val p = HookJson.parse(call.receiveText())
                if (p != null) manager.sessionEnd(p.input.sessionId)
                EMPTY
            }
        }
        post("/cdm/acquire") {
            val holder = call.holder() ?: return@post call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val cwd = call.request.queryParameters["cwd"].orEmpty()
            val wants = call.request.queryParameters["category"].orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Category(it) }.toSet()
            val timeout = (call.request.queryParameters["timeoutSec"]?.toLongOrNull() ?: DEFAULT_ACQUIRE_TIMEOUT_SEC).seconds
            call.respondTextWriter(ContentType.Text.Plain) {
                val out = Mutex()
                suspend fun line(s: String) = out.withLock { write(s + "\n"); flush() }
                coroutineScope {
                    // Heartbeat: a write to a dead client fails, which cancels this scope and with it the acquire
                    // (which detaches in its finally). Without it a disconnect goes unnoticed until the next real line.
                    val beat = launch {
                        while (true) {
                            delay(heartbeat)
                            line(".")
                        }
                    }
                    val r = manager.acquire(holder, cwd, wants, timeout) { line("QUEUED $it") }
                    beat.cancel()
                    when (r) {
                        is AcquireResult.Granted -> line("GRANTED ${r.serial} ${r.name}")
                        is AcquireResult.TimedOut -> line("TIMEOUT ${r.position}")
                        AcquireResult.Blocked -> line("BLOCKED")
                    }
                }
            }
        }
        post("/cdm/release") {
            val holder = call.holder() ?: return@post call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val had = manager.status(holder).let { it is HolderStatus.Holding || it is HolderStatus.Queued }
            manager.release(holder)
            call.respondText(if (had) "RELEASED\n" else "NONE\n")
        }
        get("/cdm/status") {
            val holder = call.holder() ?: return@get call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val line = when (val s = manager.status(holder)) {
                is HolderStatus.Holding -> {
                    val d = manager.snapshot.value.devices.firstOrNull { it.key == s.device }
                    "HOLDING ${d?.connectedSerials?.firstOrNull() ?: s.device.hardwareSerial} ${d?.name ?: s.device.hardwareSerial}"
                }
                is HolderStatus.Queued -> "QUEUED ${s.position}"
                HolderStatus.Blocked -> "BLOCKED"
                else -> "NONE"
            }
            call.respondText(line + "\n")
        }
    }
}

private fun ApplicationCall.holder(): HolderId? =
    request.queryParameters["holder"]?.takeIf { it.isNotBlank() }?.let { HolderId.parse(it) }

/** Hook endpoints never fail: any exception is logged and answered with `{}`. */
private suspend fun io.ktor.server.routing.RoutingContext.hook(body: suspend () -> String) {
    val out = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        System.err.println("cdm: hook failed: $e")
        EMPTY
    }
    call.respondText(out, ContentType.Application.Json)
}
