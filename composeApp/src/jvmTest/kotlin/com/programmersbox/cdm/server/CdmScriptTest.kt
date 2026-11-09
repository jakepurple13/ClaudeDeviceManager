package com.programmersbox.cdm.server

import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.Platform
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
    fun deviceFlagAndDevicesCommand() {
        val pixel = com.programmersbox.cdm.core.allocator.AllocDevice(
            com.programmersbox.cdm.core.model.DeviceKey("HW"), "Pixel 8", Category.PHONE, false, listOf("SER-A"),
        )
        val m = object : FakeManager() {
            override val snapshot = kotlinx.coroutines.flow.MutableStateFlow(
                com.programmersbox.cdm.core.allocator.AllocatorSnapshot(listOf(pixel), emptyMap(), emptyList(), emptyMap()),
            )
        }
        m.acquireImpl = { _, _, _, _, onPos -> onPos(1); AcquireResult.TimedOut(1) }
        withServer(m) { pf ->
            val base = arrayOf("--holder", "s:main", "--cwd", "/x")
            val r = run(pf, "acquire", *base, "--device", "SER-A")
            assertEquals(Wants(setOf(Category.ANY), Platform.ANDROID, null, pixel.key), m.lastWants)
            assertTrue("Waiting for SER-A… position 1" in r.out, r.out)
            val bad = run(pf, "acquire", *base, "--device", "nope")
            assertEquals(2, bad.code, bad.out)
            assertTrue("Device Manager refused the request: No device matches \"nope\"" in bad.out, bad.out)
            val list = run(pf, "devices", *base)
            assertEquals("SER-A  Pixel 8 · Android real · phone · free\n", list.out)
        }
    }

    @Test
    fun liveAcquireQueuedThenGranted() {
        val m = FakeManager().apply {
            acquireImpl = { _, _, _, _, onPos ->
                onPos(2)
                AcquireResult.Granted("SER1", "Pixel Watch", Platform.ANDROID)
            }
        }
        withServer(m) { pf ->
            val r = run(pf, "acquire", "--holder", "s:main", "--cwd", "/my dir", "--category", "watch")
            assertEquals(0, r.code, r.out)
            assertEquals(
                "Waiting for a watch device… position 2\nGot Pixel Watch (SER1). Your adb and Gradle commands are now pinned to it.\n",
                r.out,
            )
            assertEquals(Wants(setOf(Category.WATCH)), m.lastWants)
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
            m.statusImpl = { HolderStatus.Queued(1, Wants.ANY) }
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

    @Test
    fun cdmScriptParsesNewFlags() {
        val m = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.TimedOut(1) } }
        withServer(m) { pf ->
            val base = arrayOf("--holder", "s:main", "--cwd", "/x")
            run(pf, "acquire", *base, "--category", "tablet", "--ios", "--real")
            assertEquals(Wants(setOf(Category.TABLET), Platform.IOS, virtual = false), m.lastWants)
            run(pf, "acquire", *base, "--android", "--virtual")
            assertEquals(Wants(emptySet(), Platform.ANDROID, virtual = true), m.lastWants)
            run(pf, "acquire", *base, "--category", "phone")
            assertEquals(Wants(setOf(Category.PHONE)), m.lastWants)
            // --ios with --android is now a two-device set; the one-platform check stays for release.
            val both = run(pf, "release", *base, "--ios", "--android")
            assertEquals(2, both.code, both.out)
            assertEquals(2, run(pf, "acquire", *base, "--real", "--virtual").code)

            m.statusImpl = { HolderStatus.Queued(1, Wants.ANY) }
            assertEquals("Released your iOS device.\n", run(pf, "release", *base, "--ios").out)
            assertEquals("Released your Android device.\n", run(pf, "release", *base, "--android").out)
            assertEquals("Released your device.\n", run(pf, "release", *base).out)
            val h = com.programmersbox.cdm.core.model.HolderId("s", "main")
            assertEquals(listOf(h to Platform.IOS, h to Platform.ANDROID, h to null), m.released)
        }
    }

    @Test
    fun cdmScriptIosGrantMessage() {
        val m = FakeManager().apply {
            acquireImpl = { _, _, _, _, onPos ->
                onPos(1)
                AcquireResult.Granted("UDID-1", "iPhone 16 Pro", Platform.IOS)
            }
        }
        withServer(m) { pf ->
            val r = run(pf, "acquire", "--holder", "s:main", "--cwd", "/x", "--category", "phone", "--ios", "--real")
            assertEquals(0, r.code, r.out)
            assertEquals(
                "Waiting for a phone device (iOS, real only)… position 1\n" +
                    "Got iPhone 16 Pro (UDID-1). Your iOS commands get CDM_IOS_UDID=UDID-1; pass it as " +
                    "-destination id=\$CDM_IOS_UDID, simctl … \$CDM_IOS_UDID or maestro --device \$CDM_IOS_UDID.\n",
                r.out,
            )
            m.statusImpl = { HolderStatus.Queued(2, Wants.ANY) }
            m.holdingsImpl = {
                listOf(HolderStatus.Holding(com.programmersbox.cdm.core.model.DeviceKey("UDID-1"), kotlin.time.Instant.fromEpochMilliseconds(0), kotlin.time.Instant.fromEpochMilliseconds(0), false, Platform.IOS))
            }
            assertEquals("Holding UDID-1 (UDID-1, iOS).\nQueued at position 2.\n", run(pf, "status", "--holder", "s:main", "--cwd", "/x").out)
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

    /** Final whole-branch review: an unknown flag is an error, not silently dropped. */
    @Test
    fun unknownFlagPrintsUsage() {
        for (args in listOf(
            arrayOf("acquire", "--holder", "s:main", "--cwd", "/x", "--tablet"),
            arrayOf("release", "--holder", "s:main", "--cwd", "/x", "--iOS"),
            arrayOf("status", "-x", "--holder", "s:main"),
        )) {
            val r = run(dir.resolve("nope"), *args)
            assertEquals(2, r.code, r.out)
            assertTrue(r.out.startsWith("Unknown option: "), r.out)
            assertTrue("Usage: cdm acquire" in r.out, r.out)
        }
    }

    /** Final whole-branch review: a 4xx answer (no verdict) ends the wait at once instead of re-POSTing forever. */
    @Test
    fun acquireStopsOnClientError() {
        val m = FakeManager()
        withServer(m) { pf ->
            // A blank holder passes the script's own check but the server answers 400 "missing holder".
            val p = start(pf, "acquire", "--holder", " ", "--cwd", "/x")
            val done = p.waitFor(20, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            assertTrue(done, "cdm acquire kept retrying after a 400")
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(2, p.exitValue(), out)
            assertTrue("missing holder" in out, out)
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
        val second = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.Granted("SER1", "Pixel Watch", Platform.ANDROID) } }
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
            assertEquals(Wants(setOf(Category.WATCH)), second.lastWants)
        } finally {
            proc.destroyForcibly()
            s1.stop()
            s2?.stop()
        }
    }

    private val base = arrayOf("--holder", "s:main", "--cwd", "/x")

    @Test
    fun setAcquirePrintsMembers() {
        val m = FakeManager().apply {
            acquireSetImpl = { _, _, _, _, onPos ->
                onPos(1)
                AcquireResult.GrantedSet(
                    listOf(
                        com.programmersbox.cdm.service.GrantedMember("android1", Platform.ANDROID, "SER1", "Pixel 8", null),
                        com.programmersbox.cdm.service.GrantedMember("android2", Platform.ANDROID, "SER2", "Galaxy S23", null),
                        com.programmersbox.cdm.service.GrantedMember("ios1", Platform.IOS, "UDID-1", "iPhone 15", null),
                    ),
                )
            }
        }
        withServer(m) { pf ->
            val r = run(pf, "acquire", *base, "--android", "2", "--ios")
            assertEquals(0, r.code, r.out)
            assertEquals(
                "Waiting for your devices\u2026 position 1\n" +
                    "Got your devices:\n" +
                    "  android1  Pixel 8 (SER1)\n" +
                    "  android2  Galaxy S23 (SER2)\n" +
                    "  ios1      iPhone 15 (UDID-1)\n" +
                    "Run commands on one device with `cdm run <name> -- <command>`, for example `cdm run android2 -- adb shell input tap 100 200`.\n" +
                    "For iOS, still pass \$CDM_IOS_UDID: `cdm run ios1 -- xcrun simctl launch \$CDM_IOS_UDID com.example.app`.\n",
                r.out,
            )
        }
    }

    @Test
    fun setAcquireSendsMemberParams() {
        val m = FakeManager().apply { acquireSetImpl = { _, _, _, _, _ -> AcquireResult.TimedOut(1) } }
        withServer(m) { pf ->
            run(pf, "acquire", *base, "--android", "2:watch", "--ios", "--real")
            assertEquals(
                listOf(
                    Wants(setOf(Category.WATCH), Platform.ANDROID, virtual = false),
                    Wants(setOf(Category.WATCH), Platform.ANDROID, virtual = false),
                    Wants(emptySet(), Platform.IOS, virtual = false),
                ),
                m.lastSet?.members,
            )
            run(pf, "acquire", *base, "--android", "--android")
            assertEquals(listOf(Platform.ANDROID, Platform.ANDROID), m.lastSet?.members?.map { it.platform })
        }
    }

    @Test
    fun setRefusalExits2() {
        val m = FakeManager().apply { acquireSetImpl = { _, _, _, _, _ -> AcquireResult.Refused("only 1 android connected") } }
        withServer(m) { pf ->
            val r = run(pf, "acquire", *base, "--android", "2")
            assertEquals(2, r.code, r.out)
            assertTrue("Device Manager refused the request: only 1 android connected" in r.out, r.out)
        }
    }

    @Test
    fun legacyCountOneWithValueStaysSingle() {
        val m = FakeManager().apply { acquireImpl = { _, _, _, _, _ -> AcquireResult.TimedOut(1) } }
        withServer(m) { pf ->
            run(pf, "acquire", *base, "--android", "1:watch")
            assertEquals(Wants(setOf(Category.WATCH), Platform.ANDROID), m.lastWants)
        }
    }

    @Test
    fun runWithoutHookExecsCommand() {
        val pb = ProcessBuilder(listOf(script.toString(), "run", "android1", "--", "echo", "hi"))
        pb.environment()["CDM_PORT_FILE"] = dir.resolve("nope").toString()
        val q = pb.start()
        val out = q.inputStream.bufferedReader().readText()
        val err = q.errorStream.bufferedReader().readText()
        assertTrue(q.waitFor(60, TimeUnit.SECONDS))
        assertEquals(0, q.exitValue(), err)
        assertEquals("hi\n", out)
        assertEquals("Device Manager not running; running without a device lock.\n", err)
    }

    @Test
    fun runMissingDashDashExits2() {
        val r = run(dir.resolve("nope"), "run", "android1", "echo", "hi")
        assertEquals(2, r.code, r.out)
        assertTrue("cdm run <name|all> -- <command>" in r.out, r.out)
        assertEquals(2, run(dir.resolve("nope"), "run", "android1", "--").code)
    }

    @Test
    fun statusPrintsMembers() {
        val m = FakeManager().apply {
            val t = kotlin.time.Instant.fromEpochMilliseconds(0)
            statusImpl = {
                HolderStatus.HoldingSet(
                    com.programmersbox.cdm.core.allocator.HeldSet(
                        listOf(
                            com.programmersbox.cdm.core.allocator.SetMember("android1", Platform.ANDROID, com.programmersbox.cdm.core.model.DeviceKey("SER1")),
                            com.programmersbox.cdm.core.allocator.SetMember("ios1", Platform.IOS, com.programmersbox.cdm.core.model.DeviceKey("UDID-1")),
                        ),
                        t, t, false,
                    ),
                )
            }
        }
        withServer(m) { pf ->
            assertEquals("Holding android1: SER1 (SER1).\nHolding ios1: UDID-1 (UDID-1, iOS).\n", run(pf, "status", *base).out)
        }
    }
}
