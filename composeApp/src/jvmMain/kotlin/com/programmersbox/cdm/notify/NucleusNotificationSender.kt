package com.programmersbox.cdm.notify

import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native notifications through Nucleus (UNUserNotificationCenter on macOS, toasts on Windows, libnotify on Linux).
 * Clicking one calls [onActivated] (off the UI thread).
 *
 * Nucleus needs a packaged app on macOS: under `./gradlew run` it reports unavailable, and every send goes to
 * [fallback] (the Compose tray) instead, so dev runs still try to show something.
 */
class NucleusNotificationSender(
    private val fallback: NotificationSender,
    private val onActivated: () -> Unit,
) : NotificationSender {
    private val available: Boolean by lazy { runCatching { NotificationManager.isAvailable() }.getOrDefault(false) }
    private val authorizationRequested = AtomicBoolean(false)

    /**
     * Asks macOS for permission the first time (the system prompt shows once; later calls just report the choice).
     * Nucleus's send doesn't ask on its own, so a notification sent before the user allows them is dropped.
     */
    fun requestPermission() {
        if (!available || !authorizationRequested.compareAndSet(false, true)) return
        runCatching {
            if (NotificationCenter.isAvailable) {
                NotificationCenter.requestAuthorization(setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND)) { granted, error ->
                    if (!granted) System.err.println("cdm: notifications not allowed${error?.let { ": $it" } ?: ""}")
                }
            }
        }.onFailure { System.err.println("cdm: notification permission request failed: $it") }
    }

    override fun send(title: String, body: String) {
        if (!available) return fallback.send(title, body)
        requestPermission()
        val result = runCatching { NotificationManager.send(notification(title, body, onActivated = onActivated)) }
            .getOrElse { NotificationResult.Failure(it.toString()) }
        if (result is NotificationResult.Failure) {
            System.err.println("cdm: native notification failed (${result.reason}); using the tray")
            fallback.send(title, body)
        }
    }
}
