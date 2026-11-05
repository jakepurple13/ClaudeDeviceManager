package com.jacobrein.cdm.service

import com.jacobrein.cdm.core.allocator.AllocDevice
import com.jacobrein.cdm.core.allocator.Allocator
import com.jacobrein.cdm.core.allocator.AllocatorEvent
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.GrabResult
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.allocator.ReleaseReason
import com.jacobrein.cdm.core.allocator.Timers
import com.jacobrein.cdm.core.classify.CommandClassifier
import com.jacobrein.cdm.core.classify.CommandKind
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.data.ActivityRepository
import com.jacobrein.cdm.data.AppSettings
import com.jacobrein.cdm.data.BlockedRepository
import com.jacobrein.cdm.data.DeviceConfigRepository
import com.jacobrein.cdm.data.ProjectRepository
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.devices.DeviceTracker
import com.jacobrein.cdm.devices.TrackedDevice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the [Allocator] (behind [mutex]), device scanning, persistence, and `cdm acquire` waiting.
 * Every allocator call, [HookDecider] call included (Ruling C), runs inside [locked].
 */
class DeviceManagerService(
    private val tracker: DeviceTracker,
    private val deviceConfigs: DeviceConfigRepository,
    private val projects: ProjectRepository,
    private val settings: SettingsRepository,
    private val activity: ActivityRepository,
    private val blockedRepo: BlockedRepository,
    private val mirror: PermissionMirror,
    private val clock: () -> Instant = { Clock.System.now() },
    private val scanInterval: Duration = 2.seconds,
    private val tickInterval: Duration = 5.seconds,
    private val settingsRetryDelay: Duration = 1.seconds,
) : DeviceManager {
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)

    @Volatile private var timers = Timers()

    /** Allocator events, persisted by the consumer launched in [start]. */
    private val events = Channel<AllocatorEvent>(Channel.UNLIMITED)

    // Replaced once in start() after the blocked set loads; guarded by mutex.
    private var allocator = newAllocator(emptySet())
    private var decider = newDecider()

    private val book = HolderBook(clock)

    /**
     * Project default categories, fetched before taking [mutex] (no DB reads under the lock on the
     * hook path: a slow DB would time the 2 s hook out, and hooks fail open).
     */
    private val defaultCategories = ConcurrentHashMap<String, Category>()

    /** Bumped (under [mutex]) on every UI config write, so a scan that read configs earlier skips its stale write. */
    private var configVersion = 0L
    private val lastUpserted = HashMap<DeviceKey, Instant>()

    private val _snapshot = MutableStateFlow(allocator.snapshot())
    override val snapshot: StateFlow<AllocatorSnapshot> = _snapshot.asStateFlow()

    private val _tracked = MutableStateFlow<List<TrackedDevice>>(emptyList())
    override val tracked: StateFlow<List<TrackedDevice>> = _tracked.asStateFlow()

    override val holderInfo: StateFlow<Map<HolderId, HolderInfo>> = book.info

    private fun newAllocator(blocked: Set<HolderId>) =
        Allocator(clock = clock, blocked = blocked, timers = { timers }, onEvent = { events.trySend(it) })

    private fun newDecider() = HookDecider(allocator, { timers }) { cwd -> defaultCategories[cwd] ?: Category.ANY }

    private suspend fun prefetchDefaultCategory(cwd: String): Category {
        val c = safely("read default category for $cwd") { projects.defaultCategoryFor(cwd) } ?: Category.ANY
        defaultCategories[cwd] = c
        return c
    }

    /** Loads the blocked set and settings, then scans every [scanInterval], ticks every [tickInterval], and persists events. */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        // UNDISPATCHED: the mutex is taken before start() returns, so no call sees the pre-load allocator.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            locked {
                safely("load settings") { settings.current() }?.let { timers = it.toTimers() }
                allocator = newAllocator(safely("load blocked holders") { blockedRepo.all() } ?: emptySet())
                decider = newDecider()
            }
        }
        scope.launch {
            // Resubscribe after a DB error instead of losing timer updates for good.
            settings.settings
                .retryWhen { e, _ ->
                    if (e is CancellationException) return@retryWhen false
                    logFailure("observe settings", e)
                    delay(settingsRetryDelay)
                    true
                }
                .collect { timers = it.toTimers() }
        }
        scope.launch { for (e in events) persist(e) }
        scope.launch {
            while (isActive) {
                scanOnce()
                delay(scanInterval)
            }
        }
        scope.launch {
            while (isActive) {
                delay(tickInterval)
                locked { allocator.tick() }
                book.prune()
            }
        }
    }

    // ---- hooks ----

    override suspend fun preToolUse(input: HookInput): HookDecision {
        val holder = input.holder
        if (input.toolName == "Bash" && input.command != null) prefetchDefaultCategory(input.cwd)
        val (reply, device) = locked {
            val r = when (input.toolName) {
                "Bash" -> input.command?.let { decider.onBash(holder, input.cwd, it) } ?: HookReply.NoOpinion
                in EDIT_TOOLS -> decider.onEdit(holder)
                else -> HookReply.NoOpinion
            }
            r to (allocator.statusOf(holder) as? HolderStatus.Holding)?.device
        }
        val deviceCommand = input.command?.takeIf {
            input.toolName == "Bash" && reply !is HookReply.Deny && device != null && isDeviceCommand(it)
        }
        book.touch(holder, input.cwd, input.agentType, deviceCommand)
        if (deviceCommand != null && device != null) safely("record command") { activity.recordCommand(holder, device, deviceCommand, clock()) }
        // After the lock is released: reads settings files.
        val permission = (reply as? HookReply.Rewrite)?.let {
            mirror.decide(it.original, it.kind, input.cwd, input.permissionMode)
        }
        return HookDecision(reply, permission)
    }

    override suspend fun subagentStop(sessionId: String, agentId: String) {
        val holder = HolderId(sessionId, agentId)
        locked { allocator.endHolder(holder, ReleaseReason.SUBAGENT_STOP) }
        book.end(holder)
    }

    override suspend fun sessionEnd(sessionId: String) {
        locked { allocator.endSession(sessionId) }
        book.endSession(sessionId)
    }

    // ---- cdm ----

    override suspend fun acquire(
        holder: HolderId,
        cwd: String,
        wants: Set<Category>,
        timeout: Duration,
        onPosition: suspend (Int) -> Unit,
    ): AcquireResult {
        book.touch(holder, cwd, null)
        val w = wants.ifEmpty { setOf(prefetchDefaultCategory(cwd)) }
        val first = locked {
            when (val g = allocator.tryGrab(holder, w)) {
                is GrabResult.Granted -> AcquireResult.Granted(g.serial, g.device.name)
                GrabResult.Blocked -> AcquireResult.Blocked
                else -> {
                    allocator.enqueue(holder, w)
                    allocator.attachWaiter(holder)
                    null
                }
            }
        }
        if (first != null) return first
        try {
            var last = -1
            withTimeoutOrNull(timeout) {
                snapshot.first { snap ->
                    val st = snap.statuses[holder]
                    if (st is HolderStatus.Queued) {
                        if (st.position != last) {
                            last = st.position
                            onPosition(st.position)
                        }
                        false
                    } else true
                }
            }
            return locked { resultFor(holder) }
        } finally {
            withContext(NonCancellable) { locked { allocator.detachWaiter(holder) } }
        }
    }

    private fun resultFor(holder: HolderId): AcquireResult = when (val st = allocator.statusOf(holder)) {
        is HolderStatus.Holding -> {
            val d = allocator.snapshot().devices.firstOrNull { it.key == st.device }
            AcquireResult.Granted(allocator.serialFor(holder) ?: st.device.hardwareSerial, d?.name ?: st.device.hardwareSerial)
        }
        HolderStatus.Blocked -> AcquireResult.Blocked
        is HolderStatus.Queued -> AcquireResult.TimedOut(st.position)
        else -> AcquireResult.TimedOut(0)
    }

    override suspend fun release(holder: HolderId) {
        locked {
            allocator.removeFromQueue(holder)
            allocator.release(holder, ReleaseReason.SELF_RELEASE)
        }
        book.touch(holder, null, null)
    }

    override suspend fun status(holder: HolderId): HolderStatus = locked { allocator.statusOf(holder) }

    // ---- UI actions ----

    override suspend fun releaseDevice(key: DeviceKey, block: Boolean) = locked { allocator.releaseDevice(key, block) }

    override suspend fun handOff(key: DeviceKey, to: HolderId) = locked { allocator.handOff(key, to) }

    /** DB first, outside the lock; the allocator changes only if the write succeeded. */
    override suspend fun setDontUse(key: DeviceKey, dontUse: Boolean, releaseNow: Boolean) {
        safely("persist don't use for ${key.hardwareSerial}") { deviceConfigs.setDontUse(key, dontUse) } ?: return
        locked {
            configVersion++
            allocator.setDontUse(key, dontUse, releaseNow)
        }
    }

    /** DB first, outside the lock; the allocator changes only if the write succeeded. */
    override suspend fun setCategory(key: DeviceKey, category: Category?) {
        safely("persist category for ${key.hardwareSerial}") { deviceConfigs.setCategory(key, category) } ?: return
        locked { applyCategory(key, category) }
    }

    private fun applyCategory(key: DeviceKey, category: Category?) {
        configVersion++
        val detected = _tracked.value.firstOrNull { it.key == key }?.detectedCategory
        val effective = category ?: detected
        if (effective != null) {
            allocator.setDevices(allocator.snapshot().devices.map { if (it.key == key) it.copy(category = effective) else it })
        }
    }

    override suspend fun moveInQueue(h: HolderId, index: Int) = locked { allocator.moveInQueue(h, index) }

    override suspend fun removeFromQueue(h: HolderId) = locked { allocator.removeFromQueue(h) }

    override suspend fun unblock(h: HolderId) = locked { allocator.unblock(h) }

    // ---- internals ----

    private suspend fun scanOnce() {
        val found = safely("scan devices") { tracker.scan() } ?: return
        _tracked.value = found
        val now = clock()
        for (t in found) {
            val last = lastUpserted[t.key]
            if (last == null || now - last >= UPSERT_EVERY) {
                safely("record device seen") { deviceConfigs.upsertSeen(t.key, now) }
                lastUpserted[t.key] = now
            }
        }
        val version = mutex.withLock { configVersion }
        val configs = (safely("read device configs") { deviceConfigs.all.first() } ?: return).associateBy { it.key }
        locked {
            if (version != configVersion) return@locked // a UI write raced this read; the next scan picks it up
            val current = found.map { t ->
                val c = configs[t.key]
                AllocDevice(
                    key = t.key,
                    name = c?.displayName ?: t.model.ifBlank { null } ?: t.key.hardwareSerial,
                    category = c?.categoryOverride ?: t.detectedCategory,
                    dontUse = c?.dontUse ?: false,
                    connectedSerials = t.serials,
                )
            }
            // Held devices that went away stay listed (offline · held) so the UI and deny messages can name them.
            val snap = allocator.snapshot()
            val offlineHeld = snap.devices
                .filter { d -> d.key in snap.holds && current.none { it.key == d.key } }
                .map { it.copy(connectedSerials = emptyList()) }
            allocator.setDevices(current + offlineHeld)
        }
    }

    private suspend fun persist(e: AllocatorEvent) {
        safely("record event $e") { activity.record(e) }
        when (e) {
            is AllocatorEvent.Blocked -> safely("persist block of ${e.holder.key}") { blockedRepo.add(e.holder) }
            is AllocatorEvent.Unblocked -> safely("persist unblock of ${e.holder.key}") { blockedRepo.remove(e.holder) }
            else -> Unit
        }
    }

    private suspend fun <T> locked(f: suspend () -> T): T = mutex.withLock {
        try {
            f()
        } finally {
            _snapshot.value = allocator.snapshot()
        }
    }

    private fun isDeviceCommand(command: String) = CommandClassifier.classifyAll(command)
        .any { it is CommandKind.Device || it == CommandKind.GradleNonDevice }

    private fun AppSettings.toTimers() = Timers(idle = idleMinutes.minutes, editing = editingMinutes.minutes)

    companion object {
        val EDIT_TOOLS = setOf("Edit", "Write", "MultiEdit", "NotebookEdit")
        private val UPSERT_EVERY = 1.minutes
    }
}

/** Runs [f]; a failure (I/O, DB) is logged to stderr and yields null instead of crashing the service. Cancellation still propagates. */
private suspend fun <T> safely(op: String, f: suspend () -> T): T? = try {
    f()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    logFailure(op, e)
    null
}

private fun logFailure(op: String, e: Throwable) = System.err.println("cdm: $op failed: $e")
