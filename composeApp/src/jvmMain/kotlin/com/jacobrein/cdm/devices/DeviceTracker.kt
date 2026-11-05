package com.jacobrein.cdm.devices

import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.core.model.DeviceKey
import kotlinx.coroutines.CancellationException

data class TrackedDevice(
    val key: DeviceKey,
    val serials: List<String>,
    val model: String,
    val androidVersion: String,
    val transport: String,
    val detectedCategory: Category,
)

internal fun isWifiSerial(serial: String) =
    ':' in serial || (serial.startsWith("adb-") && "_adb-tls-connect" in serial)

class DeviceTracker(private val adb: AdbClient) {
    private data class Props(val hardware: String, val model: String, val version: String, val category: Category)

    private val cache = mutableMapOf<String, Props>()

    suspend fun scan(): List<TrackedDevice> {
        val serials = adb.devicesLong().lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" }
            .map { it[0] }
            .toList()
        cache.keys.retainAll(serials.toSet())

        val resolved = mutableListOf<Pair<String, Props>>()
        for (s in serials) {
            val props = cache[s] ?: try {
                read(s).also { cache[s] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            resolved += s to props
        }
        return resolved.groupBy { it.second.hardware }.map { (hw, rows) ->
            val ordered = rows.map { it.first }.sortedBy { isWifiSerial(it) } // stable: USB first
            val p = rows.first().second
            TrackedDevice(
                DeviceKey(hw), ordered, p.model, p.version,
                if (ordered.any { !isWifiSerial(it) }) "usb" else "wifi", p.category,
            )
        }
    }

    private suspend fun read(serial: String): Props {
        val hw = adb.shell(serial, "getprop", "ro.serialno").trim()
        require(hw.isNotEmpty()) { "empty ro.serialno for $serial" }
        val model = adb.shell(serial, "getprop", "ro.product.model").trim()
        val version = adb.shell(serial, "getprop", "ro.build.version.release").trim()
        val watch = adb.shell(serial, "pm", "has-feature", "android.hardware.type.watch").trim() == "true"
        return Props(hw, model, version, if (watch) Category.WATCH else Category.PHONE)
    }
}
