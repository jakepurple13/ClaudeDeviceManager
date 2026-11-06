package com.programmersbox.cdm.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** What the screen shows, and what its buttons do. Each button calls one [CdmClient] function. */
class SampleState(holder: String, private val scope: CoroutineScope) {

    /** Every line sent to and received from CDM. */
    val log = mutableStateListOf<String>()

    val client = CdmClient(holder) { line -> log += line }

    /** null while checking, then true or false. */
    var connected by mutableStateOf<Boolean?>(null)
        private set

    /** Kept up to date by GET /cdm/events. */
    var devices by mutableStateOf(DeviceList())
        private set

    /** The device we hold, if any. */
    var hold by mutableStateOf<AcquireResult.Granted?>(null)
        private set

    /** Set while we wait in line, e.g. "Number 2 in line". */
    var waiting by mutableStateOf<String?>(null)
        private set

    /** The last thing that went wrong, shown under the buttons. */
    var problem by mutableStateOf<String?>(null)
        private set

    private var connection: Job? = null
    private var acquiring: Job? = null

    /** Find CDM, clean up after a crashed run, then follow device changes and keep our hold alive. */
    fun connect() {
        connection?.cancel()
        connection = scope.launch {
            connected = null
            if (!client.ping()) {
                // CDM is closed. A real app keeps working without it; it just can't share devices.
                connected = false
                return@launch
            }
            connected = true

            // If a previous run crashed while holding a device, give that device back first.
            client.release()

            // Renew every 60 seconds while we hold a device, or CDM will think we are done with it.
            launch {
                while (true) {
                    delay(60.seconds)
                    if (hold != null) safely { if (!client.renew()) lostHold() }
                }
            }

            // Live device list. This ends when CDM quits.
            try {
                client.events().collect { devices = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log += "! lost connection: ${e.message}"
            }
            connected = false
            hold = null
            acquiring?.cancel()
        }
    }

    /** Wait in line for a device that matches. Leave [device] null to take any match. */
    fun acquire(category: String, platform: String?, virtual: Boolean?, device: String? = null) {
        problem = null
        acquiring = scope.launch {
            waiting = "Waiting for a device…"
            try {
                safely {
                    while (true) {
                        val result = client.acquire(category, platform, virtual, device) { waiting = "Number $it in line" }
                        when (result) {
                            is AcquireResult.Granted -> hold = result
                            AcquireResult.TimedOut -> continue // still in line: ask again to keep our spot
                            AcquireResult.Refused -> problem = "CDM said no: this app was blocked or removed from the line."
                            is AcquireResult.Error -> problem = result.reason
                        }
                        break
                    }
                }
            } finally {
                waiting = null
            }
        }
    }

    /** Leave the line: stop waiting, and tell CDM right away (release also takes us out of the line). */
    fun cancel() {
        acquiring?.cancel()
        scope.launch { safely { client.release() } }
    }

    fun renew() = scope.launch { safely { if (!client.renew()) lostHold() } }

    fun status() = scope.launch { safely { client.status() } }

    fun release() = scope.launch {
        safely { client.release() }
        hold = null
    }

    /** For closing the window: give the device back before the app exits. */
    fun releaseNow() = runBlocking {
        withTimeoutOrNull(2.seconds) { runCatching { client.release() } }
    }

    /** Renew answered NONE: someone took the device back in CDM. Stop using it. */
    private fun lostHold() {
        hold = null
        problem = "CDM took the device back. Stop using it, then acquire again."
    }

    /** Run a CDM call; if it fails, show why instead of crashing. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problem = "Couldn't reach CDM: ${e.message}"
            log += "! ${e.message}"
        }
    }
}
