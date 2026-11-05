package com.programmersbox.cdm.install

import com.programmersbox.cdm.server.FakeManager
import com.programmersbox.cdm.server.ServerController
import com.programmersbox.cdm.server.ServerStatus
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Every test uses a temp home: nothing here touches the real ~/.claude. */
class InstallLifecycleTest {
    private val home: Path = Files.createTempDirectory("cdm-life-home")
    private val installer = Installer(home)
    private val settings = home.resolve(".claude/settings.json")
    private val md = home.resolve(".claude/CLAUDE.md")
    private val cdm = home.resolve(".local/bin/cdm")

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun assertAllInstalled(life: InstallLifecycle, port: Int) {
        assertEquals(InstallStatus.Installed, installer.hookStatus(port))
        assertTrue(installer.cdmInstalled())
        assertTrue(installer.snippetInstalled())
        val st = life.state.value
        assertEquals(PartState.Installed, st.hooks)
        assertEquals(PartState.Installed, st.cdm)
        assertEquals(PartState.Installed, st.snippet)
        assertEquals(port, st.port)
    }

    @Test
    fun lifecycleInstallsThenUninstallsOnce() = runBlocking {
        val user = """{"hooks":{"Stop":[{"hooks":[{"type":"command","command":"say done"}]}]}}"""
        settings.parent.createDirectories()
        settings.writeText(user)
        md.writeText("# mine\n")
        val life = InstallLifecycle(installer)
        life.onStarted(47823)
        assertAllInstalled(life, 47823)

        life.onExit()
        assertEquals(Json.parseToJsonElement(user), Json.parseToJsonElement(settings.readText()))
        assertEquals("# mine\n", md.readText())
        assertFalse(cdm.exists())
        assertEquals(PartState.NotInstalled, life.state.value.hooks)

        // The second exit (shutdown hook after window close) is a no-op: it leaves a fresh install alone.
        installer.installHooks(47823).getOrThrow()
        life.onExit()
        assertEquals(InstallStatus.Installed, installer.hookStatus(47823))
        // And nothing installs after exit (a restart racing the shutdown).
        installer.uninstallHooks().getOrThrow()
        life.onStarted(47823)
        assertEquals(InstallStatus.NotInstalled, installer.hookStatus(47823))
    }

    @Test
    fun failedStartDoesNotInstall() = runBlocking {
        val life = InstallLifecycle(installer)
        val portFile = Files.createTempDirectory("cdm-life-port").resolve("port")
        val server = ServerController(FakeManager(), ServerStatus(), portFile, listener = life)
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            assertFalse(server.start(taken.localPort))
        }
        assertFalse(settings.exists())
        assertFalse(cdm.exists())
        assertFalse(md.exists())
        try {
            // A successful Retry installs.
            val port = freePort()
            server.restart(port)
            assertAllInstalled(life, port)
            // A port change re-installs the hooks for the new port.
            val other = freePort()
            server.restart(other)
            assertEquals(InstallStatus.Installed, installer.hookStatus(other))
            assertEquals(other, life.state.value.port)
        } finally {
            life.onExit()
            server.stop()
        }
        assertFalse(settings.exists(), "settings.json we created is deleted again")
        assertFalse(md.exists(), "CLAUDE.md we created is deleted again")
        assertFalse(cdm.exists())
        assertFalse(portFile.exists())
    }

    @Test
    fun failedRestartRemovesIntegrationAndRetryReinstalls() = runBlocking {
        md.parent.createDirectories()
        md.writeText("# mine\n")
        val life = InstallLifecycle(installer)
        val portFile = Files.createTempDirectory("cdm-life-port").resolve("port")
        val server = ServerController(FakeManager(), ServerStatus(), portFile, listener = life)
        try {
            val port = freePort()
            assertTrue(server.start(port))
            assertAllInstalled(life, port)
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
                server.restart(taken.localPort)
            }
            // The server is down: Claude must not be pointed at it.
            assertFalse(settings.exists())
            assertEquals("# mine\n", md.readText())
            assertFalse(cdm.exists())
            assertEquals(PartState.NotInstalled, life.state.value.hooks)
            val retry = freePort()
            server.restart(retry)
            assertAllInstalled(life, retry)
        } finally {
            life.onExit()
            server.stop()
        }
        assertFalse(settings.exists())
        assertEquals("# mine\n", md.readText())
    }

    @Test
    fun failedFirstStartRemovesNothing() = runBlocking {
        // Another instance's install must survive this instance failing to bind.
        installer.installHooks(47823).getOrThrow()
        val life = InstallLifecycle(installer)
        val server = ServerController(FakeManager(), ServerStatus(), Files.createTempDirectory("cdm-p").resolve("port"), listener = life)
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken -> assertFalse(server.start(taken.localPort)) }
        assertEquals(InstallStatus.Installed, installer.hookStatus(47823))
    }

    @Test
    fun losingExitCallerWaitsForTheUninstall() {
        val life = InstallLifecycle(installer)
        runBlocking { life.onStarted(47823) }
        val started = java.util.concurrent.CountDownLatch(1)
        // Hold the lifecycle's lock from outside, as an in-flight uninstall would.
        val lockField = InstallLifecycle::class.java.getDeclaredField("lock").apply { isAccessible = true }
        val lock = lockField.get(life)
        val first = Thread { life.onExit() }
        var secondReturned = false
        val second = Thread { started.countDown(); life.onExit(); secondReturned = true }
        synchronized(lock) {
            first.start()
            while (first.state != Thread.State.BLOCKED) Thread.sleep(1)
            second.start()
            started.await()
            Thread.sleep(100)
            assertFalse(secondReturned, "the losing caller returned before the uninstall finished")
        }
        first.join(5_000)
        second.join(5_000)
        assertTrue(secondReturned)
        assertEquals(InstallStatus.NotInstalled, installer.hookStatus(47823))
    }

    @Test
    fun exitWithoutInstallLeavesAnotherInstancesEntries() {
        installer.installHooks(47823).getOrThrow()
        installer.installCdm()
        installer.appendSnippet().getOrThrow()
        InstallLifecycle(installer).onExit()
        assertEquals(InstallStatus.Installed, installer.hookStatus(47823))
        assertTrue(installer.cdmInstalled())
        assertTrue(installer.snippetInstalled())
    }

    @Test
    fun failuresAreReportedAndDoNotStopTheOtherSteps() = runBlocking {
        settings.parent.createDirectories()
        settings.writeText("""{"broken":""")
        val life = InstallLifecycle(installer)
        life.onStarted(47823)
        assertIs<PartState.Failed>(life.state.value.hooks)
        assertEquals(PartState.Installed, life.state.value.cdm)
        assertEquals(PartState.Installed, life.state.value.snippet)
        life.onExit()
        assertEquals("""{"broken":""", settings.readText())
        assertFalse(cdm.exists())
    }

    @Test
    fun repairReinstallsOnTheLastPort() = runBlocking {
        val life = InstallLifecycle(installer)
        assertFalse(life.repair(), "nothing to repair before the server is up")
        assertFalse(settings.exists())
        life.onStarted(47900)
        Files.delete(cdm)
        installer.uninstallHooks().getOrThrow()
        life.refresh()
        assertEquals(PartState.NotInstalled, life.state.value.cdm)
        assertTrue(life.repair())
        assertAllInstalled(life, 47900)
        life.onExit()
        assertFalse(life.repair())
    }

    @Test
    fun startupPrunesBackups() = runBlocking {
        settings.parent.createDirectories()
        settings.writeText("{}")
        repeat(7) { installer.installHooks(47823).getOrThrow() }
        InstallLifecycle(installer).onStarted(47823)
        val backups = Files.list(settings.parent).filter { it.fileName.toString().startsWith("settings.json.cdm-backup-") }.count()
        assertEquals(5, backups)
    }
}
