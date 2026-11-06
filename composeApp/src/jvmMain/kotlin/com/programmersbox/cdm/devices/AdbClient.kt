package com.programmersbox.cdm.devices

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AdbClient {
    suspend fun devicesLong(): String
    suspend fun shell(serial: String, vararg args: String): String

    /** Returns stdout regardless of exit code; still throws on timeout or launch failure. */
    suspend fun shellOutput(serial: String, vararg args: String): String
}

/** Runs the `adb` chosen in Settings (see [AdbSelection]), 5 s timeout per call; throws on failure. */
class ProcessAdbClient(
    private val adbPath: suspend () -> String,
    private val timeoutSeconds: Long = 5,
) : AdbClient {
    override suspend fun devicesLong(): String = run(listOf(adbPath(), "devices", "-l"))

    override suspend fun shell(serial: String, vararg args: String): String =
        run(listOf(adbPath(), "-s", serial, "shell") + args)

    override suspend fun shellOutput(serial: String, vararg args: String): String =
        run(listOf(adbPath(), "-s", serial, "shell") + args, checkExit = false)

    private suspend fun run(cmd: List<String>, checkExit: Boolean = true): String = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw java.io.IOException("adb timed out: ${cmd.joinToString(" ")}")
        }
        reader.join(1000)
        if (checkExit && p.exitValue() != 0) throw java.io.IOException("adb failed (${p.exitValue()}): $out")
        out.toString()
    }
}
