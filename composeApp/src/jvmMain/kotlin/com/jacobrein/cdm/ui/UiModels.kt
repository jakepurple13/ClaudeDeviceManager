package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.AllocDevice
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.allocator.HolderStatus
import com.jacobrein.cdm.core.allocator.Timers
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.data.ActivityEntry
import com.jacobrein.cdm.data.DeviceConfig
import com.jacobrein.cdm.devices.TrackedDevice
import com.jacobrein.cdm.service.HolderInfo
import kotlin.time.Duration
import kotlin.time.Instant

/** Everything the screens are built from, at one instant. */
data class World(
    val snapshot: AllocatorSnapshot,
    val tracked: List<TrackedDevice>,
    val info: Map<HolderId, HolderInfo>,
    val configs: List<DeviceConfig>,
    /** Newest first. */
    val activity: List<ActivityEntry>,
    val timers: Timers,
    val now: Instant,
)

enum class DeviceState { FREE, IN_USE, OFFLINE_HELD, DONT_USE, OFFLINE }

data class DeviceRow(
    val key: DeviceKey,
    val name: String,
    val category: Category,
    val categoryOverridden: Boolean,
    val state: DeviceState,
    val holderLabel: String?,
    val heldFor: Duration?,
    /** "idle 1m" or "editing · releases in 4m"; null when nobody holds the device. */
    val timerLabel: String?,
    /** Marked don't use while held: it stops being handed out once the current holder releases it. */
    val dontUsePending: Boolean = false,
)

data class QueueRow(val holder: HolderId, val label: String, val position: Int, val wants: Set<Category>, val waited: Duration)

/** One device a queued holder could use, and why it can't have it yet. */
data class WaitReason(val device: String, val reason: String)

data class DeviceRef(val key: DeviceKey, val name: String)

data class ActivityRow(val id: Long, val at: Instant, val text: String)

data class OverviewCounts(val free: Int, val inUse: Int, val dontUse: Int, val waiting: Int)

/** "gabb-messenger #a41f", plus " · <agent type>" for subagents. */
fun holderLabel(h: HolderId, cwd: String?, agentType: String?): String {
    val project = cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val base = "${project ?: "session"} #${h.sessionId.take(4)}"
    val agent = agentType ?: h.agentId.takeIf { it != HolderId.MAIN }
    return if (agent != null) "$base · $agent" else base
}

fun World.labelOf(h: HolderId): String = info[h].let { holderLabel(h, it?.cwd?.ifBlank { null }, it?.agentType) }

fun World.deviceName(key: DeviceKey): String =
    snapshot.devices.firstOrNull { it.key == key }?.name
        ?: configs.firstOrNull { it.key == key }?.displayName
        ?: tracked.firstOrNull { it.key == key }?.model?.ifBlank { null }
        ?: key.hardwareSerial

fun World.holding(h: HolderId): HolderStatus.Holding? = snapshot.statuses[h] as? HolderStatus.Holding

/** Time left before the holder's auto-release timer fires. */
fun World.releasesIn(st: HolderStatus.Holding): Duration =
    ((if (st.editing) timers.editing else timers.idle) - (now - st.lastActivity)).coerceAtLeast(Duration.ZERO)

fun World.timerLabel(st: HolderStatus.Holding): String =
    if (st.editing) "editing · releases in ${minutesCeil(releasesIn(st))}" else "idle ${short(now - st.lastActivity)}"

fun deviceRows(w: World): List<DeviceRow> {
    val trackedKeys = w.tracked.mapTo(HashSet()) { it.key }
    val overrides = w.configs.filter { it.categoryOverride != null }.mapTo(HashSet()) { it.key }
    return w.snapshot.devices.map { d ->
        val holder = w.snapshot.holds[d.key]
        val st = holder?.let { w.holding(it) }
        val online = d.key in trackedKeys && d.connectedSerials.isNotEmpty()
        val state = when {
            holder != null && !online -> DeviceState.OFFLINE_HELD
            holder != null -> DeviceState.IN_USE
            d.dontUse -> DeviceState.DONT_USE
            !online -> DeviceState.OFFLINE
            else -> DeviceState.FREE
        }
        DeviceRow(
            key = d.key,
            name = d.name,
            category = d.category,
            categoryOverridden = d.key in overrides,
            state = state,
            holderLabel = holder?.let { w.labelOf(it) },
            heldFor = st?.let { w.now - it.since },
            timerLabel = st?.let { w.timerLabel(it) },
            dontUsePending = holder != null && d.dontUse,
        )
    }
}

fun overviewCounts(rows: List<DeviceRow>, waiting: Int) = OverviewCounts(
    free = rows.count { it.state == DeviceState.FREE },
    inUse = rows.count { it.state == DeviceState.IN_USE || it.state == DeviceState.OFFLINE_HELD },
    dontUse = rows.count { it.state == DeviceState.DONT_USE },
    waiting = waiting,
)

/** When [h] last joined the queue, from the activity log. */
fun World.queuedAt(h: HolderId): Instant? = activity.firstOrNull { it.kind == "queued" && it.holderKey == h.key }?.at

fun queueRows(w: World): List<QueueRow> = w.snapshot.queue.mapIndexed { i, h ->
    val wants = (w.snapshot.statuses[h] as? HolderStatus.Queued)?.wants.orEmpty()
    QueueRow(h, w.labelOf(h), i + 1, wants, w.queuedAt(h)?.let { w.now - it }?.coerceAtLeast(Duration.ZERO) ?: Duration.ZERO)
}

private fun fits(wants: Set<Category>, d: AllocDevice) = Category.ANY in wants || d.category in wants

private fun World.wantsOf(h: HolderId): Set<Category> = (snapshot.statuses[h] as? HolderStatus.Queued)?.wants.orEmpty()

/** Every device [h] could use, in device order, with what keeps it from [h]. */
fun waitReasons(h: HolderId, w: World): List<WaitReason> {
    val wants = w.wantsOf(h)
    return w.snapshot.devices.filter { fits(wants, it) }.map { d ->
        val holder = w.snapshot.holds[d.key]
        val reason = when {
            d.dontUse -> "marked don't use"
            holder != null -> {
                val st = w.holding(holder)
                val detail = when {
                    st == null -> ""
                    st.editing -> " (editing, releases in ≤${minutesCeil(w.releasesIn(st))})"
                    else -> " (idle ${short(w.now - st.lastActivity)}, releases in ≤${minutesCeil(w.releasesIn(st))})"
                }
                "held by ${w.labelOf(holder)}$detail"
            }
            d.connectedSerials.isEmpty() -> "offline"
            else -> "free"
        }
        WaitReason(d.name, reason)
    }
}

/** Devices "Hand it <device> now" can give [h]: connected, usable, and of a category it wants. */
fun handOffTargets(h: HolderId, w: World): List<DeviceRef> {
    val wants = w.wantsOf(h)
    return w.snapshot.devices
        .filter { fits(wants, it) && !it.dontUse && it.connectedSerials.isNotEmpty() && w.snapshot.holds[it.key] != h }
        .map { DeviceRef(it.key, it.name) }
}

/** The activity feed, rendered with holder labels and device names; unknown kinds fall back to the stored text. */
fun activityRows(w: World, entries: List<ActivityEntry> = w.activity): List<ActivityRow> = entries.map { e ->
    val holder = e.holderKey?.let { w.labelOf(HolderId.parse(it)) }
    val device = e.deviceKey?.let { w.deviceName(DeviceKey(it)) }
    val text = when {
        e.kind == "granted" && holder != null && device != null -> "$holder took $device"
        e.kind == "released" && device != null -> "$device released (${releaseReason(e.text, w.timers)})"
        e.kind == "queued" && holder != null -> "$holder queued (${e.text.substringAfter(" wants ", "any")})"
        e.kind == "left_queue" && holder != null -> "$holder left the queue"
        e.kind == "blocked" && holder != null -> "$holder blocked"
        e.kind == "unblocked" && holder != null -> "$holder unblocked"
        else -> e.text
    }
    ActivityRow(e.id, e.at, text)
}

private fun releaseReason(text: String, t: Timers): String =
    when (text.substringAfterLast('(').substringBefore(')')) {
        "IDLE_TIMEOUT" -> "idle ${short(t.idle)}"
        "EDIT_TIMEOUT" -> "editing ${short(t.editing)}"
        "SELF_RELEASE" -> "by holder"
        "USER_RELEASE" -> "by you"
        "USER_HANDOFF" -> "handed off"
        "DONT_USE" -> "don't use"
        "SUBAGENT_STOP" -> "subagent stopped"
        "SESSION_END" -> "session ended"
        else -> "released"
    }

/** "42s", "12m", "1h 5m": whole units, rounded down. */
fun short(d: Duration): String {
    val s = d.inWholeSeconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s % 3600 / 60 == 0L -> "${s / 3600}h"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }
}

/** Minutes rounded up, for "releases in" labels: 4m 1s reads "5m". */
fun minutesCeil(d: Duration): String {
    val m = (d.inWholeSeconds.coerceAtLeast(0) + 59) / 60
    return if (m < 60) "${m}m" else short(Duration.parse("${m}m"))
}
