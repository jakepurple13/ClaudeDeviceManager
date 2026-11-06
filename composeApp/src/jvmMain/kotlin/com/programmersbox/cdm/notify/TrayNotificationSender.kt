package com.programmersbox.cdm.notify

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends through the app's Compose [TrayState]; the OS shows it only while the Tray is in the composition.
 *
 * [TrayState.sendNotification] hands off over an unbuffered channel with `trySend`, so a second notification sent
 * before the Tray has taken the first is dropped. This queues them and hands them over [gap] apart, so a burst
 * (a release that starts the next queued session, or the test button's four) all show.
 */
class TrayNotificationSender(
    private val tray: TrayState,
    scope: CoroutineScope,
    private val gap: Duration = 250.milliseconds,
) : NotificationSender {
    private val queue = Channel<Notification>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (n in queue) {
                tray.sendNotification(n)
                delay(gap)
            }
        }
    }

    override fun send(title: String, body: String) {
        queue.trySend(Notification(title, body, Notification.Type.Info))
    }
}
