package com.programmersbox.cdm.devices

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Merges the Android tracker with the optional iOS tracker. The iOS scan runs in [scope], never inline: the Android
 * result waits at most [grace] for it and otherwise uses the last completed iOS result, so a hung xcrun can't stall it.
 */
class DeviceSource(
    private val android: DeviceTracker,
    private val ios: IosTracker?,
    private val scope: CoroutineScope,
    private val grace: Duration = 250.milliseconds,
) {
    @Volatile private var lastIos: List<TrackedDevice> = emptyList()
    private var inFlight: Job? = null

    suspend fun scan(): List<TrackedDevice> {
        val tracker = ios ?: return android.scan()
        val job = inFlight?.takeIf { it.isActive } ?: scope.launch {
            lastIos = try {
                tracker.scan()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("cdm: ios scan failed: $e")
                emptyList()
            }
        }.also { inFlight = it }
        val a = android.scan()
        withTimeoutOrNull(grace) { job.join() }
        return a + lastIos
    }
}
