package com.programmersbox.cdm.notify

import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native notifications through Nucleus: [MacNotifications] on macOS, Nucleus's cross-platform manager (toasts on
 * Windows, libnotify on Linux) elsewhere. Clicking one opens its target; a notice with [SystemNotice.release] gets a
 * Release button, the rest an Open button.
 *
 * Nucleus needs a packaged app on macOS: under `./gradlew run` it reports unavailable, and every send goes to
 * [fallback] (the Compose tray) instead, so dev runs still try to show something.
 */
class NucleusNotificationSender(
    private val fallback: NotificationSender,
    private val actions: NoticeActions,
) : NotificationSender {
    private val mac: MacNotifications? by lazy {
        if (runCatching { NotificationCenter.isAvailable }.getOrDefault(false)) MacNotifications(actions) else null
    }
    private val otherAvailable: Boolean by lazy {
        mac == null && runCatching { NotificationManager.isAvailable() }.getOrDefault(false)
    }
    private val setUp = AtomicBoolean(false)

    /**
     * Registers the macOS buttons and asks for permission, once. Called at launch: Nucleus doesn't ask on its own,
     * and a notification sent before the user allows them (or before its buttons are registered) is lost.
     */
    fun requestPermission() {
        val m = mac ?: return
        if (!setUp.compareAndSet(false, true)) return
        runCatching { m.setUp() }.onFailure { System.err.println("cdm: notification setup failed: $it") }
    }

    override fun send(notice: SystemNotice) {
        val m = mac
        when {
            m != null -> {
                requestPermission()
                runCatching { m.send(notice) { error -> failed(notice, error) } }.onFailure { failed(notice, it.toString()) }
            }
            otherAvailable -> sendOther(notice)
            else -> fallback.send(notice)
        }
    }

    private fun sendOther(notice: SystemNotice) {
        val n = notification(notice.title, notice.body, onActivated = { actions.open(notice.open) }) {
            val held = notice.release
            when {
                held != null -> button("Release") { actions.release(held) }
                notice.open != null -> button("Open") { actions.open(notice.open) }
            }
        }
        val result = runCatching { NotificationManager.send(n) }.getOrElse { NotificationResult.Failure(it.toString()) }
        if (result is NotificationResult.Failure) failed(notice, result.reason)
    }

    private fun failed(notice: SystemNotice, reason: String) {
        System.err.println("cdm: native notification failed ($reason); using the tray")
        fallback.send(notice)
    }
}
