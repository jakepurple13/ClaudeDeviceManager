package com.programmersbox.cdm.devices

import kotlinx.coroutines.CancellationException

/** Merges the Android tracker with the optional iOS tracker. An iOS failure never breaks the Android scan. */
class DeviceSource(
    private val android: DeviceTracker,
    private val ios: IosTracker?,
) {
    suspend fun scan(): List<TrackedDevice> {
        val a = android.scan()
        val i = try {
            ios?.scan().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("cdm: ios scan failed: $e")
            emptyList()
        }
        return a + i
    }
}
