package com.programmersbox.cdm.devices

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface IosClient {
    /** `xcrun simctl list devices booted --json` */
    suspend fun bootedSimulatorsJson(): String

    /** `xcrun devicectl list devices --json-output <tmp>`; returns the file's text. */
    suspend fun physicalDevicesJson(): String
}

/** iOS detection only runs on macOS with the Xcode command line tools resolvable. */
fun iosSupported(): Boolean {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return false
    return try {
        val p = ProcessBuilder("/usr/bin/xcrun", "--find", "simctl").redirectErrorStream(true).start()
        val drain = Thread { p.inputStream.readBytes() }.apply { isDaemon = true; start() }
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            false
        } else {
            drain.join(1000)
            p.exitValue() == 0
        }
    } catch (_: Exception) {
        false
    }
}

/** Runs `xcrun`, 5 s timeout per call; throws on failure. */
class ProcessIosClient(private val timeoutSeconds: Long = 5) : IosClient {
    override suspend fun bootedSimulatorsJson(): String =
        run(listOf("/usr/bin/xcrun", "simctl", "list", "devices", "booted", "--json"))

    override suspend fun physicalDevicesJson(): String = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("cdm-devicectl", ".json")
        try {
            run(listOf("/usr/bin/xcrun", "devicectl", "list", "devices", "--json-output", tmp.path))
            tmp.readText()
        } finally {
            tmp.delete()
        }
    }

    private suspend fun run(cmd: List<String>): String = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw java.io.IOException("xcrun timed out: ${cmd.joinToString(" ")}")
        }
        reader.join(1000)
        if (p.exitValue() != 0) throw java.io.IOException("xcrun failed (${p.exitValue()}): $out")
        out.toString()
    }
}
