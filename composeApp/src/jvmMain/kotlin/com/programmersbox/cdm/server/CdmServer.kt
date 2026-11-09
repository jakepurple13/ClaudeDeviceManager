package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.AllocDevice
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.SetRequest
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
            val timeout = (call.request.queryParameters["timeoutSec"]?.toLongOrNull() ?: DEFAULT_ACQUIRE_TIMEOUT_SEC).seconds
            fun bad(e: Throwable) = "${e.message}\n"
            // Member specs first, then one member per `device=`, each in the order given.
            val members = call.request.queryParameters.getAll("member").orEmpty().flatMap { m ->
                memberWants(m, categories, virtual).getOrElse { return@post call.respondText(bad(it), status = HttpStatusCode.BadRequest) }
            }
            val asked = call.request.queryParameters.getAll("device").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            val devices = manager.snapshot.value.devices
            if (members.size + asked.size > 1) {
                val byDevice = asked.map { a ->
                    deviceWants(a, devices, categories, null, virtual)
                        .getOrElse { return@post call.respondText(bad(it), status = HttpStatusCode.BadRequest) }
                }
                val req = SetRequest(members + byDevice)
                return@post call.respondAcquire(heartbeat) { manager.acquireSet(holder, cwd, req, timeout, it) }
            }
            // One member: today's single request.
            val wants = when {
                members.isNotEmpty() -> members.single()
                asked.isNotEmpty() -> deviceWants(asked.single(), devices, categories, platform, virtual)
                    .getOrElse { return@post call.respondText(bad(it), status = HttpStatusCode.BadRequest) }
                else -> Wants(categories, platform, virtual)
            }
            call.respondAcquire(heartbeat) { manager.acquire(holder, cwd, wants, timeout, it) }
        }
        post("/cdm/release") {
            val holder = call.holder() ?: return@post call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val platform = call.platform().getOrElse { return@post call.respondText("bad platform\n", status = HttpStatusCode.BadRequest) }
            val status = manager.status(holder)
            val queuedFor = (status as? HolderStatus.Queued)?.wants
            val set = (status as? HolderStatus.HoldingSet)?.set
            // A queued set has no platform in its wants, so any release takes it out of the queue.
            val had = manager.holdings(holder).any { platform == null || it.platform == platform } ||
                (queuedFor != null && (platform == null || queuedFor.platform == null || queuedFor.platform == platform)) ||
                set?.members?.any { platform == null || it.platform == platform } == true
            manager.release(holder, platform)
            call.respondText(if (had) "RELEASED\n" else "NONE\n")
        }
        get("/cdm/list") {
            // `holder` is optional: with it, that holder's own devices read "yours".
            val holder = call.holder()
            call.respondText(deviceListText(manager.snapshot.value, holder))
        }
        get("/cdm/status") {
            val holder = call.holder() ?: return@get call.respondText("missing holder\n", status = HttpStatusCode.BadRequest)
            val status = manager.status(holder)
            val lines = if (status == HolderStatus.Blocked) listOf("BLOCKED") else if (status is HolderStatus.HoldingSet) {
                val devices = manager.snapshot.value.devices
                status.set.members.map { m ->
                    val d = devices.firstOrNull { it.key == m.device }
                    "MEMBER ${m.name} ${m.platform.wire} ${d?.connectedSerials?.firstOrNull() ?: m.device.hardwareSerial} ${d?.name ?: m.device.hardwareSerial}"
                }
            } else {
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

/**
 * Wants for `cdm acquire --device <asked>`. [asked] matches a connected ADB serial, a hardware serial or UDID
 * (case-insensitive), or else a device name (case-insensitive). The device's platform is filled in, and the
 * categories default to any, not the project's default. Fails with an agent-readable message when nothing matches,
 * a name matches more than one device, the device is marked don't use, or the other flags rule it out.
 */
internal fun deviceWants(
    asked: String,
    devices: List<AllocDevice>,
    categories: Set<Category>,
    platform: Platform?,
    virtual: Boolean?,
): Result<Wants> {
    fun fail(msg: String) = Result.failure<Wants>(IllegalArgumentException(msg))
    val bySerial = devices.filter { d ->
        asked in d.connectedSerials || d.key.hardwareSerial.equals(asked, ignoreCase = true)
    }
    val matches = bySerial.ifEmpty { devices.filter { it.name.equals(asked, ignoreCase = true) } }
    val d = when (matches.size) {
        0 -> return fail("No device matches \"$asked\". Run `cdm devices` to list them.")
        1 -> matches.single()
        else -> return fail(
            "More than one device is named \"$asked\" (${matches.joinToString { it.connectedSerials.firstOrNull() ?: it.key.hardwareSerial }}). " +
                "Pass its serial instead.",
        )
    }
    val label = "${d.name} (${d.connectedSerials.firstOrNull() ?: d.key.hardwareSerial})"
    if (d.dontUse) return fail("$label is marked don't use in Device Manager. Ask the user, or pick another device.")
    val wants = Wants(categories.ifEmpty { setOf(Category.ANY) }, platform ?: d.platform, virtual, d.key)
    if (!wants.fits(d)) {
        val os = if (d.platform == Platform.IOS) "iOS" else "Android"
        val kind = when {
            !d.virtual -> "real device"
            d.platform == Platform.IOS -> "simulator"
            else -> "emulator"
        }
        return fail("$label is an $os $kind in category ${d.category.id}, so the other flags rule it out. Drop them, or pick another device.")
    }
    return Result.success(wants)
}

/**
 * `cdm devices`: one line per device, `<serial>  <name> · <platform> <kind> · <category> · <state>`, where the
 * serial is what `cdm acquire --device` takes. "No devices." when there are none.
 */
internal fun deviceListText(snap: AllocatorSnapshot, holder: HolderId?): String {
    if (snap.devices.isEmpty()) return "No devices.\n"
    val serials = snap.devices.map { it.connectedSerials.firstOrNull() ?: it.key.hardwareSerial }
    val width = serials.maxOf { it.length }
    return snap.devices.zip(serials).joinToString("") { (d, serial) ->
        val os = if (d.platform == Platform.IOS) "iOS" else "Android"
        val kind = when {
            !d.virtual -> "real"
            d.platform == Platform.IOS -> "simulator"
            else -> "emulator"
        }
        val by = snap.holds[d.key]
        val state = when {
            by != null && by == holder -> "yours"
            by != null -> "in use by ${by.key}"
            d.dontUse -> "don't use"
            d.connectedSerials.isEmpty() -> "offline"
            else -> "free"
        }
        "${serial.padEnd(width)}  ${d.name} · $os $kind · ${d.category.id} · $state\n"
    }
}

/**
 * Answers `/cdm/acquire`. An answer that comes at once (a grant, a refusal) is sent as a plain response, so a
 * [AcquireResult.Refused] can answer HTTP 400. Once the holder is queued, or [heartbeat] passes without an answer, the
 * response streams: `QUEUED <n>` lines, `.` heartbeats, then the result lines.
 */
private suspend fun ApplicationCall.respondAcquire(
    heartbeat: Duration,
    acquire: suspend (onPosition: suspend (Int) -> Unit) -> AcquireResult,
) = coroutineScope {
    val positions = Channel<Int>(Channel.UNLIMITED)
    val firstEvent = CompletableDeferred<Unit>()
    val queued = AtomicBoolean(false)
    val result = async {
        try {
            acquire { p ->
                queued.set(true)
                positions.send(p)
                firstEvent.complete(Unit)
            }
        } finally {
            positions.close()
        }
    }
    // Only once the Deferred itself is complete, so the check below can't see an immediate answer as still pending.
    result.invokeOnCompletion { firstEvent.complete(Unit) }
    withTimeoutOrNull(heartbeat) { firstEvent.await() }
    if (result.isCompleted && !queued.get()) {
        when (val r = result.await()) {
            is AcquireResult.Refused -> respondText("${r.message}\n", status = HttpStatusCode.BadRequest)
            else -> respondText(r.wireLines().joinToString("") { it + "\n" })
        }
        return@coroutineScope
    }
    respondTextWriter(ContentType.Text.Plain) {
        val out = Mutex()
        suspend fun line(s: String) = out.withLock { write(s + "\n"); flush() }
        try {
            coroutineScope {
                // Heartbeat: a write to a dead client fails, which cancels the acquire (which detaches in its
                // finally). Without it a disconnect goes unnoticed until the next real line.
                val beat = launch {
                    while (true) {
                        delay(heartbeat)
                        line(".")
                    }
                }
                for (p in positions) line("QUEUED $p") // ends when the acquire returns
                val r = result.await()
                beat.cancel()
                r.wireLines().forEach { line(it) }
            }
        } catch (e: Throwable) {
            result.cancel()
            throw e
        }
    }
}

/**
 * The result lines of the `cdm` wire protocol. A set grant is one `MEMBER <name> <platform> <serial> <device name>`
 * line per member (with a trailing ` pair=<udid>` for a paired watch), then `DONE`. A refusal is normally answered as HTTP 400 before streaming; mid-stream it is
 * `REFUSED <message>`.
 */
private fun AcquireResult.wireLines(): List<String> = when (this) {
    is AcquireResult.Granted -> listOf("GRANTED ${platform.wire} $serial $name")
    is AcquireResult.TimedOut -> listOf("TIMEOUT $position")
    AcquireResult.Blocked -> listOf("BLOCKED")
    AcquireResult.Removed -> listOf("REMOVED")
    is AcquireResult.GrantedSet -> members.map {
        "MEMBER ${it.name} ${it.platform.wire} ${it.serial} ${it.deviceName}" + it.pairSerial?.let { p -> " pair=$p" }.orEmpty()
    } + "DONE"
    is AcquireResult.Refused -> listOf("REFUSED $message")
}

private val COUNT = Regex("-?[0-9]+")
private val CATEGORY = Regex("[A-Za-z0-9_-]+")

/** The most devices one `member=` may ask for. */
private const val MAX_MEMBER_COUNT = 64

/**
 * One `member=<android|ios>:<value>` of `/cdm/acquire`, expanded into one [Wants] per device. The value is empty
 * (one device), `N` (N devices), `cat` (one device of that category) or `N:cat`; `cat` may be a comma list, meaning
 * any of them. A member without a category takes [categories] (`--category`, empty for the project default).
 * [virtual] applies to every member. Fails with a short message for anything else, a count below 1 included.
 */
internal fun memberWants(param: String, categories: Set<Category>, virtual: Boolean?): Result<List<Wants>> {
    fun fail(why: String) = Result.failure<List<Wants>>(IllegalArgumentException("bad member \"$param\": $why"))
    val platformName = param.substringBefore(':')
    val platform = Platform.entries.firstOrNull { it.wire == platformName } ?: return fail("platform must be android or ios")
    val value = param.substringAfter(':', "")
    val parts = if (value.isEmpty()) emptyList() else value.split(':')
    val (countText, catText) = when (parts.size) {
        0 -> null to null
        1 -> if (COUNT.matches(parts[0])) parts[0] to null else null to parts[0]
        2 -> parts[0] to parts[1]
        else -> return fail("expected N, a category, or N:category")
    }
    val count = if (countText == null) 1 else {
        if (!COUNT.matches(countText)) return fail("count must be a number")
        countText.toIntOrNull()?.takeIf { it in 1..MAX_MEMBER_COUNT } ?: return fail("count must be 1..$MAX_MEMBER_COUNT")
    }
    val cats = if (catText == null) categories else {
        val ids = catText.split(',').map { it.trim() }
        if (ids.any { !CATEGORY.matches(it) || COUNT.matches(it) }) return fail("bad category")
        ids.map(::Category).toSet()
    }
    return Result.success(List(count) { Wants(cats, platform, virtual) })
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
