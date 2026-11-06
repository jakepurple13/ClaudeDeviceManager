package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.service.AcquireResult
import com.programmersbox.cdm.service.DeviceManager
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
    fun clear() { _error.value = null }
}

/** Under Claude Code's default 2-minute Bash timeout (Ruling Y); `cdm` sends its own value anyway. */
const val DEFAULT_ACQUIRE_TIMEOUT_SEC = 110L

fun defaultPortFile(): Path = Path(System.getProperty("user.home"), ".claude-device-manager", "port")

/** Hooks and `cdm` talk to this. Binds 127.0.0.1 only. Start it only after the service's `start()`. */
class CdmServer(
    private val manager: DeviceManager,
    private val port: Int,
    private val portFile: Path = defaultPortFile(),
    private val heartbeat: Duration = 5.seconds,
) {
    private var engine: EmbeddedServer<*, *>? = null

    /** Only a server that wrote the port file deletes it: a failed start must not remove another instance's file. */
    @Volatile private var wroteFile = false

    /** Binds, then writes the port file. Returns the bound port. */
    suspend fun start(): Int {
        val e = embeddedServer(CIO, port = port, host = "127.0.0.1") { cdmModule(manager, heartbeat) }
        e.start(wait = false)
        engine = e
        val bound = e.engine.resolvedConnectors().first().port
        Files.createDirectories(portFile.parent)
        Files.writeString(portFile, bound.toString())
        wroteFile = true
        return bound
    }

    fun stop() {
        engine?.stop(100, 1000)
        engine = null
        if (wroteFile) {
            wroteFile = false
            runCatching { Files.deleteIfExists(portFile) }
        }
    }
}

private val EMPTY = "{}"

fun Application.cdmModule(manager: DeviceManager, heartbeat: Duration = 5.seconds) {
    routing {
        get("/cdm/ping") { call.respondText("cdm-ok\n") }
        integrationRoutes(manager)
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
            val categories = call.request.queryParameters["category"].orEmpty()
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { Category(it) }.toSet()
            val platform = call.platform().getOrElse { return@post call.respondText("bad platform\n", status = HttpStatusCode.BadRequest) }
            val virtual = when (call.request.queryParameters["virtual"].orEmpty()) {
                "" -> null
                "true" -> true
                "false" -> false
                else -> return@post call.respondText("bad virtual\n", status = HttpStatusCode.BadRequest)
            }
            val wants = Wants(categories, platform, virtual)
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
                        is AcquireResult.Granted -> line("GRANTED ${r.platform.wire} ${r.serial} ${r.name}")
                        is AcquireResult.TimedOut -> line("TIMEOUT ${r.position}")
                        AcquireResult.Blocked -> line("BLOCKED")
                        AcquireResult.Removed -> line("REMOVED")
                    }
                }
            }
        }
        post("/cdm/release") {
            val holder = call.holder() ?: return@post call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val platform = call.platform().getOrElse { return@post call.respondText("bad platform\n", status = HttpStatusCode.BadRequest) }
            val queuedFor = (manager.status(holder) as? HolderStatus.Queued)?.wants
            val had = manager.holdings(holder).any { platform == null || it.platform == platform } ||
                (queuedFor != null && (platform == null || queuedFor.platform == null || queuedFor.platform == platform))
            manager.release(holder, platform)
            call.respondText(if (had) "RELEASED\n" else "NONE\n")
        }
        get("/cdm/status") {
            val holder = call.holder() ?: return@get call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val status = manager.status(holder)
            val lines = if (status == HolderStatus.Blocked) listOf("BLOCKED") else {
                // One line per hold, then the queue spot: a holder can hold one platform while queued for the other.
                val devices = manager.snapshot.value.devices
                manager.holdings(holder).map { h ->
                    val d = devices.firstOrNull { it.key == h.device }
                    "HOLDING ${h.platform.wire} ${d?.connectedSerials?.firstOrNull() ?: h.device.hardwareSerial} ${d?.name ?: h.device.hardwareSerial}"
                } + listOfNotNull((status as? HolderStatus.Queued)?.let { "QUEUED ${it.position}" })
            }
            call.respondText(lines.ifEmpty { listOf("NONE") }.joinToString("") { it + "\n" })
        }
    }
}

/** The platform's name in the `cdm` wire protocol: `android` or `ios`. */
private val Platform.wire: String get() = name.lowercase()

/** The `platform` query parameter: absent or empty is null; anything but `android` or `ios` fails. */
internal fun ApplicationCall.platform(): Result<Platform?> {
    val raw = request.queryParameters["platform"].orEmpty()
    if (raw.isEmpty()) return Result.success(null)
    return Platform.entries.firstOrNull { it.wire == raw }?.let { Result.success(it) }
        ?: Result.failure(IllegalArgumentException("bad platform $raw"))
}

internal fun ApplicationCall.holder(): HolderId? =
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
