package com.programmersbox.cdm.ui

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.data.AdbMode
import com.programmersbox.cdm.data.NotificationSettings
import com.programmersbox.cdm.data.PinnedPermission
import com.programmersbox.cdm.notify.NotificationTester
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.devices.AdbLocation
import com.programmersbox.cdm.devices.AdbLocator
import com.programmersbox.cdm.devices.AdbSelection
import com.programmersbox.cdm.devices.AdbSource
import com.programmersbox.cdm.install.InstallLifecycle
import com.programmersbox.cdm.install.InstallStatus
import com.programmersbox.cdm.install.PartState
import com.programmersbox.cdm.install.Installer
import com.programmersbox.cdm.server.ServerControl
import com.programmersbox.cdm.server.ServerListener
import com.programmersbox.cdm.server.ServerStatus
import com.programmersbox.cdm.ui.settings.SettingsViewModel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/** Records restarts instead of binding sockets; like ServerController, a "successful bind" notifies [listener]. */
class RecordingServer(private val listener: ServerListener = ServerListener {}) : ServerControl {
    val restarts = mutableListOf<Int>()
    override suspend fun restart(port: Int) {
        restarts += port
        listener.onListening(port)
    }
}

/** A settings table that reads as empty (defaults) and whose writes fail. */
class FailingSettingDao : com.programmersbox.cdm.data.SettingDao {
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<com.programmersbox.cdm.data.SettingEntity>())
    override suspend fun getAll() = emptyList<com.programmersbox.cdm.data.SettingEntity>()
    override suspend fun put(entity: com.programmersbox.cdm.data.SettingEntity): Unit = error("disk full")
    override suspend fun putAll(entities: List<com.programmersbox.cdm.data.SettingEntity>): Unit = error("disk full")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() {
        vms.forEach { it.closeForTest() }
        Dispatchers.resetMain()
    }

    private val vms = mutableListOf<androidx.lifecycle.ViewModel>()

    /** A temp home: nothing here touches the real ~/.claude. */
    private val home = Files.createTempDirectory("cdm-settings-home")
    private val settings = SettingsRepository(openedInMemoryDatabase().settingDao())
    private val installer = Installer(home)
    private val lifecycle = InstallLifecycle(installer)
    private val server = RecordingServer(lifecycle)
    private val status = ServerStatus()
    private val manager = RecordingManager(MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap())))

    /** Only /bundled/platform-tools/adb and /tools/adb "exist". */
    private val adb = AdbSelection(
        AdbLocator(
            env = emptyMap(), resourcesDir = "/bundled", home = "/home", isWindows = false, isMac = true,
            canExecute = { it.path == "/bundled/platform-tools/adb" || it.path == "/tools/adb" },
        ),
    )

    private var testSends = 0
    private val tester = NotificationTester { testSends++ }

    private fun vm() = SettingsViewModel(testActions(manager), settings, lifecycle, server, status, tester, adb).also { vms += it }

    private suspend fun SettingsViewModel.loaded() = withTimeout(5_000) { uiState.first { it.loaded } }

    @Test
    fun adbChoicePersistsAndCustomPathIsStored(): Unit = runBlocking {
        val vm = vm()
        vm.loaded()
        assertEquals(AdbMode.AUTO, vm.uiState.value.adbMode)
        assertEquals("/bundled/platform-tools/adb", vm.uiState.value.bundledAdb)
        vm.onAdbModeSelected(AdbMode.BUNDLED)
        assertEquals(AdbMode.BUNDLED, vm.uiState.value.adbMode)
        withTimeout(5_000) { settings.settings.first { it.adbMode == AdbMode.BUNDLED } }
        vm.onAdbPathChanged("/tools/adb")
        assertTrue(vm.uiState.value.adbPathDirty)
        vm.onAdbPathSubmit()
        val stored = withTimeout(5_000) { settings.settings.first { it.adbMode == AdbMode.CUSTOM } }
        assertEquals("/tools/adb", stored.adbPath)
        withTimeout(5_000) { vm.uiState.first { !it.adbPathDirty } }
        // Switching away keeps the custom path for later.
        vm.onAdbModeSelected(AdbMode.AUTO)
        assertEquals("/tools/adb", withTimeout(5_000) { settings.settings.first { it.adbMode == AdbMode.AUTO } }.adbPath)
    }

    @Test
    fun adbInUseFollowsSelection(): Unit = runBlocking {
        val vm = vm()
        adb.apply(AdbMode.CUSTOM, "/tools/adb")
        assertEquals(AdbLocation("/tools/adb", AdbSource.Custom), withTimeout(5_000) { vm.uiState.first { it.adbInUse != null } }.adbInUse)
        adb.apply(AdbMode.AUTO, "")
        assertEquals(AdbSource.Bundled, vm.uiState.value.adbInUse?.source)
    }

    @Test
    fun pinnedPermissionDefaultsToMirrorAndSelectionPersists(): Unit = runBlocking {
        val vm = vm()
        vm.loaded()
        assertEquals(PinnedPermission.MIRROR, vm.uiState.value.saved.pinnedPermission)
        vm.onPinnedPermissionSelected(PinnedPermission.ALWAYS_ALLOW)
        assertEquals(PinnedPermission.ALWAYS_ALLOW, vm.uiState.value.pinnedPermission)
        withTimeout(5_000) { settings.settings.first { it.pinnedPermission == PinnedPermission.ALWAYS_ALLOW } }
        assertTrue(server.restarts.isEmpty())
        assertFalse(vm.uiState.value.dirty)
        vm.onPinnedPermissionSelected(PinnedPermission.ALWAYS_ASK)
        withTimeout(5_000) { settings.settings.first { it.pinnedPermission == PinnedPermission.ALWAYS_ASK } }
    }

    @Test
    fun rejectsBadPort() = runBlocking {
        val vm = vm()
        vm.loaded()
        vm.onPortChanged("80")
        assertNotNull(vm.uiState.value.portError)
        vm.onSave()
        assertEquals(47823, settings.current().port)
        assertTrue(server.restarts.isEmpty())
    }

    @Test
    fun rejectsBadMinutes() = runBlocking {
        val vm = vm()
        vm.loaded()
        vm.onIdleChanged("0")
        vm.onEditingChanged("241")
        assertNotNull(vm.uiState.value.idleError)
        assertNotNull(vm.uiState.value.editingError)
        vm.onSave()
        assertEquals(15, settings.current().idleMinutes)
    }

    @Test
    fun portChangePersistsRestartsAndReinstallsHooks() = runBlocking {
        lifecycle.onStarted(47823)
        val vm = vm()
        vm.loaded()
        withTimeout(5_000) { vm.uiState.first { it.integration.hooks == PartState.Installed && it.integration.port == 47823 } }
        vm.onPortChanged("47900")
        vm.onSave()
        withTimeout(5_000) { vm.uiState.first { it.integration.port == 47900 && it.integration.hooks == PartState.Installed } }
        assertEquals(47900, settings.current().port)
        assertEquals(listOf(47900), server.restarts)
        assertEquals(InstallStatus.Installed, installer.hookStatus(47900))
        lifecycle.onExit()
    }

    @Test
    fun timerChangeSavesWithoutRestart() = runBlocking {
        val vm = vm()
        vm.loaded()
        vm.onIdleChanged("30")
        vm.onSave()
        withTimeout(5_000) { while (settings.current().idleMinutes != 30) kotlinx.coroutines.delay(10) }
        assertTrue(server.restarts.isEmpty())
    }

    @Test
    fun repairReinstallsWhatWentMissing() = runBlocking {
        lifecycle.onStarted(47823)
        val vm = vm()
        vm.loaded()
        installer.uninstallCdm()
        installer.removeSnippet().getOrThrow()
        lifecycle.refresh()
        withTimeout(5_000) { vm.uiState.first { it.integration.cdm == PartState.NotInstalled } }
        assertTrue(vm.uiState.value.canRepair)
        vm.onRepair()
        val s = withTimeout(5_000) { vm.uiState.first { it.repairResult != null } }
        assertTrue(s.repairResult!!.ok, s.repairResult.text)
        assertTrue(s.integration.allInstalled)
        assertFalse(s.repairing)
        assertTrue(installer.cdmInstalled() && installer.snippetInstalled())
        lifecycle.onExit()
    }

    @Test
    fun repairUnavailableWhileServerDownOrNeverStarted() = runBlocking {
        val vm = vm()
        vm.loaded()
        assertFalse(vm.uiState.value.canRepair, "nothing installed yet: the server never came up")
        lifecycle.onStarted(47823)
        withTimeout(5_000) { vm.uiState.first { it.canRepair } }
        status.fail("Port 47823 is in use — change the port below.")
        withTimeout(5_000) { vm.uiState.first { !it.canRepair } }
        vm.onRepair()
        assertNull(vm.uiState.value.repairResult)
        lifecycle.onExit()
    }

    @Test
    fun saveFailureIsShown() = runBlocking {
        val failing = SettingsRepository(FailingSettingDao())
        val vm = SettingsViewModel(testActions(manager), failing, lifecycle, server, status, tester, adb).also { vms += it }
        vm.loaded()
        vm.onIdleChanged("30")
        vm.onSave()
        val r = withTimeout(5_000) { vm.uiState.first { it.saveResult != null } }.saveResult!!
        assertFalse(r.ok)
        assertTrue("disk full" in r.text, r.text)
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun saveRetriesServerOnSamePortAfterFailure() = runBlocking {
        val vm = vm()
        vm.loaded()
        status.fail("Port 47823 is in use — change the port below.")
        withTimeout(5_000) { vm.uiState.first { it.serverError != null } }
        assertTrue(vm.uiState.value.canSave, "Save must be usable to retry even with nothing edited")
        vm.onSave()
        assertEquals(listOf(47823), server.restarts)
    }

    @Test
    fun retryButtonRestartsOnSavedPort() = runBlocking {
        val vm = vm()
        vm.loaded()
        vm.onRetryServer()
        assertEquals(listOf(47823), server.restarts)
    }

    @Test
    fun fieldsNormalizedAfterSave() = runBlocking {
        val vm = vm()
        vm.loaded()
        vm.onIdleChanged("030")
        vm.onSave()
        val s = withTimeout(5_000) { vm.uiState.first { it.saved.idleMinutes == 30 && !it.saving } }
        assertEquals("30", s.idle)
        assertFalse(s.dirty)
    }

    @Test
    fun leadingZeroOnlyEditIsNotASave() = runBlocking {
        val vm = vm()
        vm.loaded()
        vm.onIdleChanged("015")
        vm.onSave()
        assertEquals("15", vm.uiState.value.idle)
        assertFalse(vm.uiState.value.dirty)
        assertTrue(server.restarts.isEmpty())
    }

    @Test
    fun serverErrorShown() = runBlocking {
        val vm = vm()
        status.fail("Port 47823 is in use — change the port below.")
        val s = withTimeout(5_000) { vm.uiState.first { it.serverError != null } }
        assertEquals("Port 47823 is in use — change the port below.", s.serverError)
    }

    @Test
    fun notificationSwitchesPersistAndTestSendsOnlyWhenOneIsOn(): Unit = runBlocking {
        val vm = vm()
        vm.loaded()
        assertEquals(NotificationSettings(), vm.uiState.value.notifications)
        vm.onSendTestNotification()
        assertEquals(1, testSends)

        val off = NotificationSettings(grab = false, release = false, queued = false, queueStart = false)
        vm.onNotificationsChanged(off.copy(release = true))
        withTimeout(5_000) { settings.settings.first { it.notifications == off.copy(release = true) } }
        vm.onSendTestNotification()
        assertEquals(2, testSends)
        assertEquals(
            "Sent 1 test notification. None showing? Check the OS notification settings for this app.",
            vm.uiState.value.notificationResult?.text,
        )

        vm.onNotificationsChanged(off)
        vm.onSendTestNotification()
        assertEquals(2, testSends)
    }
}
