package com.programmersbox.cdm.notify

import kotlin.test.Test
import kotlin.test.assertEquals

class NucleusNotificationSenderTest {
    @Test
    fun unbundledProcessFallsBackToTheTray() {
        // A test JVM is not a packaged .app, so Nucleus reports unavailable and the fallback gets the notification.
        val sent = mutableListOf<Pair<String, String>>()
        val sender = NucleusNotificationSender(fallback = { t, b -> sent += t to b }, onActivated = {})
        sender.requestPermission()
        sender.send("Pixel 8 grabbed", "my-app now holds Pixel 8.")
        assertEquals(listOf("Pixel 8 grabbed" to "my-app now holds Pixel 8."), sent)
    }
}
