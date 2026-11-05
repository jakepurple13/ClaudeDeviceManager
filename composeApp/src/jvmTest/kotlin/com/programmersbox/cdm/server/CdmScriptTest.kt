package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.service.AcquireResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class CdmScriptTest {
    private val dir: Path = Files.createTempDirectory("cdm-script")
    private val script: Path = dir.resolve("cdm").also {
        Files.copy(checkNotNull(javaClass.classLoader.getResourceAsStream("cdm")) { "cdm resource missing" }, it)
        it.toFile().setExecutable(true)
    }

    private class Result(val code: Int, val out: String)

    private fun start(portFile: Path, vararg args: String, retry: Int = 1): Process {
        val pb = ProcessBuilder(listOf(script.toString()) + args).redirectErrorStream(true)
        pb.environment()["CDM_PORT_FILE"] = portFile.toString()
        pb.environment()["CDM_RETRY_SECONDS"] = retry.toString()
        pb.environment().remove("CDM_ACQUIRE_TIMEOUT")
        return pb.start()
    }

    private fun run(portFile: Path, vararg args: String, retry: Int = 1): Result {
        val p = start(portFile, *args, retry = retry)
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return Result(p.exitValue(), out)
    }

    @Test
    fun appDownProceeds() {
        val pf = dir.resolve("port").also { Files.writeString(it, "1") } // nothing listens on port 1
        val r = run(pf, "acquire", "--holder", "s:main", "--cwd", "/x")
        assertEquals(0, r.code, r.out)
        assertTrue("proceeding without a device lock" in r.out, r.out)
    }

    @Test
    fun missingPortFileProceeds() {
        val r = run(dir.resolve("nope"), "status", "--holder", "s:main", "--cwd", "/x")
        assertEquals(0, r.code, r.out)
        assertTrue("Device Manager not running; proceeding without a device lock." in r.out, r.out)
    }

    @Test
    fun missingHolderExplains() {
        val r = run(dir.resolve("nope"), "acquire")
        assertEquals(2, r.code)
        assertTrue("cdm must be run through Claude Code's Bash tool" in r.out, r.out)
    }

    private fun withServer(m: FakeManager, f: (Path) -> Unit) {
        val pf = dir.resolve("live-port")
        val server = CdmServer(m, port = 0, portFile = pf)
        runBlocking { server.start() }
        try {
            f(pf)
        } finally {
            server.stop()
        }
        assertTrue(!Files.exists(pf), "port file deleted on stop")
    }

    @Test
    fun liveAcquireQueuedThenGranted() {
        val m = FakeManager().apply {
            acquireImpl = { _, _, _, _, onPos ->
                onPos(2)
                AcquireResult.Granted("SER1", "Pixel Watch")
            }
        }
        withServer(m) { pf ->
            val r = run(pf, "acquire", "--holder", "s:main", "--cwd", "/my dir", "--category", "watch")
            assertEquals(0, r.code, r.out)
            assertEquals(
                "Waiting for a watch device… position 2\nGot Pixel Watch (SER1). Your adb and Gradle commands are now pinned to it.\n",
                r.out,
            )
            assertEquals(setOf(Category.WATCH), m.lastWants)
        }
    }

    @Test
    fun liveTimeoutBlockedReleaseStatus() {
        val m = FakeManager()
        withServer(m) { pf ->
            val base = arrayOf("--holder", "s:main", "--cwd", "/x")
            m.acquireImpl = { _, _, _, _, _ -> AcquireResult.TimedOut(3) }
            assertEquals("Still queued at position 3. Run `cdm acquire` again to keep waiting.\n", run(pf, "acquire", *base).out)
            m.acquireImpl = { _, _, _, _, _ -> AcquireResult.Blocked }
            val b = run(pf, "acquire", *base)
            assertEquals(1, b.code)
            assertEquals("You're blocked in Claude Device Manager. Ask the user to unblock you.\n", b.out)
            m.statusImpl = { HolderStatus.Queued(1, setOf(Category.ANY)) }
            assertEquals("Queued at position 1.\n", run(pf, "status", *base).out)
            assertTrue(run(pf, "release", *base).out.startsWith("Released"))
            assertEquals(1, m.released.size)
        }
    }

    @Test
    fun killedClientCancelsAcquireViaHeartbeat() {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
        val m = FakeManager().apply {
            acquireImpl = { _, _, _, _, onPos ->
                onPos(1)
                started.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        }
        val pf = dir.resolve("hb-port")
        val server = CdmServer(m, port = 0, portFile = pf, heartbeat = kotlin.time.Duration.parse("200ms"))
        val port = runBlocking { server.start() }
        try {
            java.net.Socket("127.0.0.1", port).use { sock ->
                sock.getOutputStream().write(
                    "POST /cdm/acquire?holder=s:main&cwd=/x HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n".toByteArray(),
                )
                sock.getOutputStream().flush()
                runBlocking { kotlinx.coroutines.withTimeout(5_000) { started.await() } }
            } // socket closed mid-wait
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { cancelled.await() } }
        } finally {
            server.stop()
        }
    }

    /** Ruling Y: the default wait stays under Claude Code's 2-minute Bash timeout. */
    @Test
    fun defaultAcquireTimeoutIs110Seconds() {
        val m = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.TimedOut(1) } }
        withServer(m) { pf ->
            run(pf, "acquire", "--holder", "s:main", "--cwd", "/x")
            assertEquals(kotlin.time.Duration.parse("110s"), m.lastTimeout)
        }
        assertEquals(110L, DEFAULT_ACQUIRE_TIMEOUT_SEC)
    }

    /** Final review #11. */
    @Test
    fun removedFromQueueExplains() {
        val m = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.Removed } }
        withServer(m) { pf ->
            val r = run(pf, "acquire", "--holder", "s:main", "--cwd", "/x")
            assertEquals(1, r.code, r.out)
            assertEquals("The user removed you from the device queue. Ask them before trying again.\n", r.out)
        }
    }

    /** Final review #7: the app restarting mid-wait doesn't end the wait; cdm reconnects and re-POSTs. */
    @Test
    fun acquireSurvivesAppRestartMidWait() {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val first = FakeManager().apply {
            acquireImpl = { _, _, _, _, onPos ->
                onPos(1)
                started.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val second = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.Granted("SER1", "Pixel Watch") } }
        val pf = dir.resolve("restart-port")
        val s1 = CdmServer(first, port = 0, portFile = pf)
        val port = runBlocking { s1.start() }
        val proc = start(pf, "acquire", "--holder", "s:main", "--cwd", "/x", "--category", "watch", retry = 30)
        var s2: CdmServer? = null
        try {
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { started.await() } }
            s1.stop()
            Thread.sleep(1_500)
            s2 = CdmServer(second, port = port, portFile = pf).also { runBlocking { it.start() } }
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue(proc.waitFor(60, TimeUnit.SECONDS))
            assertEquals(0, proc.exitValue(), out)
            assertTrue(out.endsWith("Got Pixel Watch (SER1). Your adb and Gradle commands are now pinned to it.\n"), out)
            assertEquals(setOf(Category.WATCH), second.lastWants)
        } finally {
            proc.destroyForcibly()
            s1.stop()
            s2?.stop()
        }
    }
}
