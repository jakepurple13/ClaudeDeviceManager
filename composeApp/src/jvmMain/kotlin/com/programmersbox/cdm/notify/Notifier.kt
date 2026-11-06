package com.programmersbox.cdm.notify

import com.programmersbox.cdm.core.allocator.AllocatorEvent
import com.programmersbox.cdm.core.allocator.ReleaseReason
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.data.NotificationSettings
import com.programmersbox.cdm.service.DeviceManager
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Shows one system notification. */
fun interface NotificationSender {
    fun send(title: String, body: String)
}

/** The Settings screen's "Send test notification". */
fun interface NotificationTester {
    fun sendTest()
}

data class SystemNotice(val title: String, val body: String)

/**
 * Turns allocator events into system notifications, filtered by the user's [NotificationSettings].
 *
 * A queued holder that gets a device shows "queue started" instead of "grabbed", so one moment never shows two
 * notifications; with "queue started" off, it falls back to the grab notification (if that one is on).
 */
class Notifier(
    private val events: Flow<AllocatorEvent>,
    private val settings: Flow<NotificationSettings>,
    private val manager: DeviceManager,
    private val sender: NotificationSender,
) : NotificationTester {
    @Volatile private var enabled = NotificationSettings()

    /** When each currently queued holder joined the queue; touched only by the event collector. */
    private val queuedAt = HashMap<HolderId, Instant>()

    fun start(scope: CoroutineScope) {
        scope.launch { settings.collect { enabled = it } }
        scope.launch { events.collect { e -> notice(e)?.let { send(it) } } }
    }

    override fun sendTest() {
        val on = enabled
        if (on.grab) send(SystemNotice("Pixel 8 grabbed", "my-app now holds Pixel 8. (Test notification)"))
        if (on.release) send(SystemNotice("Pixel 8 released", "my-app released it. (Test notification)"))
        if (on.queued) send(SystemNotice("Waiting for a phone", "my-app is #2 in line. (Test notification)"))
        if (on.queueStart) send(SystemNotice("my-app is up", "Got Pixel 8 after waiting 4 min 12 s. (Test notification)"))
    }

    private fun send(n: SystemNotice) {
        runCatching { sender.send(n.title, n.body) }
            .onFailure { System.err.println("cdm: notification failed: $it") }
    }

    /** Maps one event to its notification and updates queue tracking; null when there is none or its switch is off. */
    internal fun notice(e: AllocatorEvent): SystemNotice? {
        val on = enabled
        return when (e) {
            is AllocatorEvent.Queued -> {
                queuedAt[e.holder] = e.at
                if (on.queued) queued(e.holder, e.wants) else null
            }
            is AllocatorEvent.LeftQueue -> {
                queuedAt.remove(e.holder)
                null
            }
            is AllocatorEvent.Granted -> {
                val since = queuedAt.remove(e.holder)
                when {
                    since != null && on.queueStart -> queueStarted(e.holder, e.device, e.at - since)
                    on.grab -> SystemNotice("${device(e.device)} grabbed", "${holder(e.holder)} now holds ${device(e.device)}.")
                    else -> null
                }
            }
            is AllocatorEvent.Released ->
                if (on.release) SystemNotice("${device(e.device)} released", releaseBody(holder(e.holder), e.reason)) else null
            is AllocatorEvent.Blocked, is AllocatorEvent.Unblocked -> null
        }
    }

    private fun queued(h: HolderId, wants: Set<Category>): SystemNotice {
        val what = if (wants.isEmpty() || Category.ANY in wants) "a device" else "a " + wants.joinToString(" or ") { it.id }
        val position = manager.snapshot.value.queue.indexOf(h) + 1
        val body = if (position > 0) "${holder(h)} is #$position in line." else "${holder(h)} is in line."
        return SystemNotice("Waiting for $what", body)
    }

    private fun queueStarted(h: HolderId, d: DeviceKey, waited: Duration) =
        SystemNotice("${holder(h)} is up", "Got ${device(d)} after waiting ${waitText(waited)}.")

    private fun device(key: DeviceKey): String =
        manager.snapshot.value.devices.firstOrNull { it.key == key }?.name ?: key.hardwareSerial

    /** The project folder name when known, else a short session id; subagents are marked. */
    private fun holder(h: HolderId): String {
        val cwd = manager.holderInfo.value[h]?.cwd?.takeIf { it.isNotBlank() }
        val name = cwd?.let { runCatching { Path.of(it).fileName?.toString() }.getOrNull() }
            ?: "Session ${h.sessionId.take(8)}"
        return if (h.agentId == HolderId.MAIN) name else "$name (subagent)"
    }

    internal companion object {
        fun releaseBody(who: String, reason: ReleaseReason): String = when (reason) {
            ReleaseReason.IDLE_TIMEOUT -> "$who was idle too long."
            ReleaseReason.EDIT_TIMEOUT -> "$who was editing too long without using it."
            ReleaseReason.SELF_RELEASE -> "$who released it."
            ReleaseReason.USER_RELEASE -> "You released it from $who."
            ReleaseReason.USER_HANDOFF -> "You handed it off from $who."
            ReleaseReason.DONT_USE -> "You marked it don't use; $who let it go."
            ReleaseReason.SUBAGENT_STOP -> "$who finished."
            ReleaseReason.SESSION_END -> "$who ended its session."
        }

        fun waitText(d: Duration): String {
            val s = d.inWholeSeconds.coerceAtLeast(0)
            return when {
                s < 60 -> "$s s"
                s % 60 == 0L -> "${s / 60} min"
                else -> "${s / 60} min ${s % 60} s"
            }
        }
    }
}
