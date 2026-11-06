package com.programmersbox.cdm.devices

import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.DeviceKey
import com.programmersbox.cdm.core.model.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Booted simulators (`simctl`) and connected, paired physical devices (`devicectl`).
 * Each source fails independently: a failure logs one line, yields nothing for that source and backs off 30 s.
 * devicectl exposes no watch -> iPhone companion field, so [TrackedDevice.partner] is always null.
 */
class IosTracker(
    private val client: IosClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var simsFailedUntil = 0L
    private var physicalFailedUntil = 0L
    private var cached: List<TrackedDevice> = emptyList()
    private var lastScanAt: Long? = null
    private var polled = false

    /** At most one pair of xcrun calls per [POLL_TTL_MS]; scans inside the window return the last result. */
    suspend fun scan(): List<TrackedDevice> {
        val last = lastScanAt
        if (last != null && clock() - last < POLL_TTL_MS) return cached
        polled = false
        return refresh().also { cached = it; if (polled) lastScanAt = clock() } // backoff-only passes don't reset the TTL
    }

    private suspend fun refresh(): List<TrackedDevice> {
        val sims = guarded("simctl", { simsFailedUntil }, { simsFailedUntil = it }) { simulators(client.bootedSimulatorsJson()) }
        val real = guarded("devicectl", { physicalFailedUntil }, { physicalFailedUntil = it }) { physical(client.physicalDevicesJson()) }
        return sims + real
    }

    private suspend fun guarded(
        what: String,
        failedUntil: () -> Long,
        setFailedUntil: (Long) -> Unit,
        f: suspend () -> List<TrackedDevice>,
    ): List<TrackedDevice> {
        if (failedUntil() > clock()) return emptyList()
        polled = true
        return try {
            f()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("cdm: ios $what scan failed: $e")
            setFailedUntil(clock() + FAIL_BACKOFF_MS)
            emptyList()
        }
    }

    private fun simulators(text: String): List<TrackedDevice> {
        val runtimes = json.parseToJsonElement(text).jsonObject["devices"]?.jsonObject ?: return emptyList()
        val out = mutableListOf<TrackedDevice>()
        for ((runtimeKey, list) in runtimes) {
            val (family, version) = parseRuntime(runtimeKey) ?: continue
            if (family != "iOS" && family != "watchOS") continue
            for (d in (list as? JsonArray).orEmpty()) {
                val o = d.jsonObject
                if (o.str("state") != "Booted") continue
                val udid = o.str("udid") ?: continue
                val type = o.str("deviceTypeIdentifier").orEmpty()
                val category = when {
                    "iPad" in type -> Category.TABLET
                    "Watch" in type -> Category.WATCH
                    else -> Category.PHONE
                }
                out += TrackedDevice(
                    key = DeviceKey(udid),
                    serials = listOf(udid),
                    model = "${o.str("name").orEmpty()} · $family $version",
                    osVersion = version,
                    transport = "simulator",
                    detectedCategory = category,
                    platform = Platform.IOS,
                    virtual = true,
                )
            }
        }
        return out
    }

    private fun physical(text: String): List<TrackedDevice> {
        val devices = json.parseToJsonElement(text).jsonObject["result"]?.jsonObject?.get("devices") as? JsonArray
            ?: return emptyList()
        return devices.mapNotNull { d ->
            val o = d.jsonObject
            val conn = o.obj("connectionProperties")
            if (conn?.str("tunnelState") != "connected" || conn.str("pairingState") != "paired") return@mapNotNull null
            val hw = o.obj("hardwareProperties") ?: return@mapNotNull null
            val udid = hw.str("udid") ?: return@mapNotNull null
            val category = when (hw.str("deviceType")) {
                "iPhone" -> Category.PHONE
                "iPad" -> Category.TABLET
                "appleWatch" -> Category.WATCH
                else -> return@mapNotNull null
            }
            val props = o.obj("deviceProperties")
            TrackedDevice(
                key = DeviceKey(udid),
                serials = listOf(udid),
                model = props?.str("name").orEmpty(),
                osVersion = props?.str("osVersionNumber").orEmpty(),
                transport = "usb",
                detectedCategory = category,
                platform = Platform.IOS,
                virtual = false,
            )
        }
    }

    /** `com.apple.CoreSimulator.SimRuntime.iOS-18-2` -> ("iOS", "18.2"). */
    private fun parseRuntime(key: String): Pair<String, String>? {
        val tail = key.substringAfterLast("SimRuntime.", "")
        val family = tail.substringBefore('-', "")
        val version = tail.substringAfter('-', "").replace('-', '.')
        return if (family.isEmpty() || version.isEmpty()) null else family to version
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonElement)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    private companion object {
        const val FAIL_BACKOFF_MS = 30_000L
        const val POLL_TTL_MS = 5_000L
    }
}
