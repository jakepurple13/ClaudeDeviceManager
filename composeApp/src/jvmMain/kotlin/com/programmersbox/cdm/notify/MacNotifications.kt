package com.programmersbox.cdm.notify

import dev.nucleusframework.notification.ActionOption
import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.DeliveredNotification
import dev.nucleusframework.notification.NotificationAction
import dev.nucleusframework.notification.NotificationCategory
import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.NotificationCenterDelegate
import dev.nucleusframework.notification.NotificationContent
import dev.nucleusframework.notification.NotificationRequest
import dev.nucleusframework.notification.NotificationResponse
import dev.nucleusframework.notification.PresentationOption
import java.util.UUID

/**
 * macOS notifications straight through Nucleus's `NotificationCenter` (UNUserNotificationCenter), bypassing its
 * cross-platform `NotificationManager`. That one (2.5.18) registers a button category just before each send and, on
 * every new category, re-registers the earlier ones without their actions, so buttons go missing. Here both
 * categories are registered once, at [setUp], well before the first notification.
 */
class MacNotifications(private val actions: NoticeActions) {
    /** Notices still in Notification Center, by request id, so a click or button can find what it was about. */
    private val live = object : LinkedHashMap<String, SystemNotice>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SystemNotice>?) = size > MAX_LIVE
    }

    private val delegate = object : NotificationCenterDelegate {
        // Show banners even while the app is frontmost.
        override fun willPresent(notification: DeliveredNotification) =
            setOf(PresentationOption.BANNER, PresentationOption.LIST, PresentationOption.SOUND)

        override fun didReceive(response: NotificationResponse) {
            val id = response.notification.identifier
            val notice = synchronized(live) { live[id] } ?: return
            runCatching {
                when (response.actionIdentifier) {
                    NotificationAction.DEFAULT_ACTION_IDENTIFIER, OPEN -> actions.open(notice.open)
                    RELEASE -> notice.release?.let(actions::release)
                }
            }.onFailure { System.err.println("cdm: notification action failed: $it") }
        }
    }

    /** Registers the delegate and button categories, and asks for permission (the system prompt shows once). */
    fun setUp() {
        NotificationCenter.setDelegate(delegate)
        NotificationCenter.setNotificationCategories(
            setOf(
                // No FOREGROUND: Release works from the banner without pulling the app forward.
                NotificationCategory(RELEASE_CATEGORY, listOf(NotificationAction(RELEASE, "Release"))),
                NotificationCategory(OPEN_CATEGORY, listOf(NotificationAction(OPEN, "Open", setOf(ActionOption.FOREGROUND)))),
            ),
        )
        NotificationCenter.requestAuthorization(setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND)) { granted, error ->
            if (!granted) System.err.println("cdm: notifications not allowed${error?.let { ": $it" } ?: ""}")
        }
    }

    fun send(notice: SystemNotice, onError: (String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val category = when {
            notice.release != null -> RELEASE_CATEGORY
            notice.open != null -> OPEN_CATEGORY
            else -> ""
        }
        synchronized(live) { live[id] = notice }
        NotificationCenter.add(
            NotificationRequest(id, NotificationContent(title = notice.title, body = notice.body, categoryIdentifier = category)),
        ) { error ->
            if (error != null) {
                synchronized(live) { live.remove(id) }
                onError(error)
            }
        }
    }

    private companion object {
        const val RELEASE_CATEGORY = "cdm.release"
        const val OPEN_CATEGORY = "cdm.open"
        const val RELEASE = "cdm.action.release"
        const val OPEN = "cdm.action.open"
        const val MAX_LIVE = 200
    }
}
