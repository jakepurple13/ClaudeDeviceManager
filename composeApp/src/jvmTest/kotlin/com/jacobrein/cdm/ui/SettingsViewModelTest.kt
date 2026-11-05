package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.install.InstallStatus
import com.jacobrein.cdm.install.Installer
import com.jacobrein.cdm.server.ServerControl
import com.jacobrein.cdm.server.ServerStatus
import com.jacobrein.cdm.ui.settings.SettingsViewModel
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

/** Records restarts instead of binding sockets. */
class RecordingServer : ServerControl {
    val restarts = mutableListOf<Int>()
    override suspend fun restart(port: Int) { restarts += port }
}

/** A settings table that reads as empty (defaults) and whose writes fail. */
class FailingSettingDao : com.jacobrein.cdm.data.SettingDao {
    override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<com.jacobrein.cdm.data.SettingEntity>())
    override suspend fun getAll() = emptyList<com.jacobrein.cdm.data.SettingEntity>()
    override suspend fun put(entity: com.jacobrein.cdm.data.SettingEntity): Unit = error("disk full")
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
    private val server = RecordingServer()
    private val status = ServerStatus()
    private val manager = RecordingManager(MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap())))

    private fun vm() = SettingsViewModel(testActions(manager), settings, Installer(home), server, status).also { vms += it }

    private suspend fun SettingsViewModel.loaded() = withTimeout(5_000) { uiState.first { it.loaded } }

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
    fun appendNeedsConfirm() = runBlocking {
        val vm = vm()
        val md = home.resolve(".claude/CLAUDE.md")
        vm.onAppendSnippet()
        assertTrue(vm.uiState.value.confirmAppend)
        assertFalse(md.exists(), "nothing written before confirmation")
        vm.onConfirmAppend()
        withTimeout(5_000) { vm.uiState.first { it.appendResult != null } }
        assertTrue(md.readText().contains("cdm acquire"))
        assertFalse(vm.uiState.value.confirmAppend)
    }

    @Test
    fun cancelAppendWritesNothing() = runBlocking {
        val vm = vm()
        vm.onAppendSnippet()
        vm.onCancelAppend()
        assertFalse(vm.uiState.value.confirmAppend)
        assertFalse(home.resolve(".claude/CLAUDE.md").exists())
    }

    @Test
    fun portChangePersistsRestartsAndHooksGoOutdated() = runBlocking {
        Installer(home).installHooks(47823).getOrThrow()
        val vm = vm()
        withTimeout(5_000) { vm.uiState.first { it.hookStatus == InstallStatus.Installed } }
        vm.onPortChanged("47900")
        vm.onSave()
        withTimeout(5_000) { vm.uiState.first { it.hookStatus is InstallStatus.Outdated } }
        assertEquals(47900, settings.current().port)
        assertEquals(listOf(47900), server.restarts)
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
    fun installHooksReportsResultAndRefreshesStatus() = runBlocking {
        val vm = vm()
        withTimeout(5_000) { vm.uiState.first { it.hookStatus == InstallStatus.NotInstalled } }
        vm.onInstallHooks()
        val s = withTimeout(5_000) { vm.uiState.first { it.hookResult != null && it.hookStatus == InstallStatus.Installed } }
        assertTrue(s.hookResult!!.ok)
    }

    @Test
    fun saveFailureIsShown() = runBlocking {
        val failing = SettingsRepository(FailingSettingDao())
        val vm = SettingsViewModel(testActions(manager), failing, Installer(home), server, status).also { vms += it }
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
}
