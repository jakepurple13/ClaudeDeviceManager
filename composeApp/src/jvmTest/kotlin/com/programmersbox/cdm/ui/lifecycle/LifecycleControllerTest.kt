package com.programmersbox.cdm.ui.lifecycle

import com.programmersbox.cdm.install.IntegrationState
import com.programmersbox.cdm.install.PartState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleControllerTest {
    private val installed = IntegrationState(PartState.Installed, PartState.Installed, PartState.Installed, port = 47823)
    private val removed = IntegrationState(port = 47823)

    private class Env(scope: TestScope, error: String? = null, integration: IntegrationState, scanned: Boolean = true) {
        val serverError = MutableStateFlow(error)
        val integration = MutableStateFlow(integration)
        val scanned = MutableStateFlow(scanned)
        var devices = 2
        var repairs = 0
        val controller = LifecycleController(
            scope = scope,
            serverError = serverError,
            integration = this.integration,
            scanned = this.scanned,
            deviceCount = { devices },
            repairIntegration = { repairs++; true },
        )
        val ui get() = controller.ui.value
        fun statuses() = ui.rows.associate { it.step to it.status }
    }

    @Test
    fun startupReplaysEachStepThenHides() = runTest {
        val env = Env(this, integration = installed)
        env.controller.startup(47823)
        runCurrent()
        assertEquals(LifecyclePhase.Starting, env.ui.phase)
        assertTrue(env.ui.cardVisible)
        assertEquals(StepStatus.Running, env.statuses()[LifecycleStep.SERVER])
        assertEquals(StepStatus.Pending, env.statuses()[LifecycleStep.HOOKS])
        assertEquals(":47823", env.ui.rows.first().detail)

        advanceTimeBy(300)
        assertEquals(StepStatus.Done, env.statuses()[LifecycleStep.SERVER])
        assertEquals(StepStatus.Running, env.statuses()[LifecycleStep.HOOKS])
        assertEquals(0.2f, env.ui.progress)

        advanceTimeBy(5 * 280L)
        assertEquals(LifecyclePhase.Ready, env.ui.phase)
        assertTrue(env.statuses().values.all { it == StepStatus.Done })
        assertEquals("2 found", env.ui.rows.last().detail)
        assertEquals(2, env.ui.deviceCount)

        advanceUntilIdle()
        assertEquals(LifecyclePhase.Idle, env.ui.phase)
        assertFalse(env.ui.cardVisible)
    }

    @Test
    fun deviceStepWaitsForFirstScan() = runTest {
        val env = Env(this, integration = installed, scanned = false)
        env.controller.startup(47823)
        advanceTimeBy(4 * 280L + 250)
        assertEquals(StepStatus.Running, env.statuses()[LifecycleStep.DEVICES])
        env.devices = 1
        env.scanned.value = true
        advanceTimeBy(100)
        assertEquals(StepStatus.Done, env.statuses()[LifecycleStep.DEVICES])
        assertEquals("1 found", env.ui.rows.last().detail)
    }

    @Test
    fun serverFailureSkipsTheInstallAndStaysUp() = runTest {
        val env = Env(this, error = "Port 47823 is in use — change the port below.", integration = IntegrationState())
        env.controller.startup(47823)
        advanceUntilIdle()
        val s = env.statuses()
        assertEquals(StepStatus.Failed("Port 47823 is in use — change the port below."), s[LifecycleStep.SERVER])
        assertEquals(StepStatus.Skipped, s[LifecycleStep.HOOKS])
        assertEquals(StepStatus.Skipped, s[LifecycleStep.CDM])
        assertEquals(StepStatus.Skipped, s[LifecycleStep.SNIPPET])
        assertEquals(LifecyclePhase.StartFailed, env.ui.phase)
        assertTrue(env.ui.cardVisible)
        assertTrue(env.ui.serverFailed)
        assertFalse(env.ui.canRepair)
    }

    @Test
    fun failedPartCanBeRepaired() = runTest {
        val env = Env(this, integration = installed.copy(hooks = PartState.Failed("settings.json can't be read")))
        env.controller.startup(47823)
        advanceUntilIdle()
        assertEquals(StepStatus.Failed("settings.json can't be read"), env.statuses()[LifecycleStep.HOOKS])
        assertEquals(StepStatus.Done, env.statuses()[LifecycleStep.CDM])
        assertTrue(env.ui.canRepair)

        env.integration.value = installed
        env.controller.repair()
        advanceTimeBy(6 * 280L)
        assertEquals(1, env.repairs)
        assertEquals(LifecyclePhase.Ready, env.ui.phase)
    }

    @Test
    fun dismissedCardKeepsProgressAndCanReopen() = runTest {
        val env = Env(this, integration = installed)
        env.controller.startup(47823)
        advanceTimeBy(400)
        env.controller.dismiss()
        assertFalse(env.ui.cardVisible)
        advanceTimeBy(300)
        assertEquals(LifecyclePhase.Starting, env.ui.phase)
        env.controller.reopen()
        assertTrue(env.ui.cardVisible)
        env.controller.dismiss()

        advanceTimeBy(3 * 280L)
        assertEquals(LifecyclePhase.Ready, env.ui.phase)
        // No reopening once it's done.
        env.controller.reopen()
        assertFalse(env.ui.cardVisible)
    }

    @Test
    fun shutdownCleansUpFirstThenPlaysThenExits() = runTest {
        val env = Env(this, integration = installed)
        env.controller.startup(47823)
        advanceUntilIdle()

        var cleanups = 0
        var exits = 0
        env.controller.shutdown(cleanup = { cleanups++; env.integration.value = removed }) { exits++ }
        assertEquals(1, cleanups) // before any animation
        assertEquals(LifecyclePhase.Stopping, env.ui.phase)
        assertTrue(env.ui.cardVisible)
        assertEquals(SHUTDOWN_STEPS, env.ui.rows.map { it.step })
        assertEquals(1f, env.ui.progress)

        // A second close while it plays changes nothing.
        env.controller.shutdown(cleanup = { cleanups++ }) { exits++ }
        assertEquals(1, cleanups)

        advanceTimeBy(250)
        assertEquals(StepStatus.Removed, env.statuses()[LifecycleStep.HOOKS])
        assertEquals(0.75f, env.ui.progress)
        assertEquals(0, exits)

        advanceUntilIdle()
        assertTrue(env.statuses().values.all { it == StepStatus.Removed })
        assertEquals(1, exits)
    }

    @Test
    fun partsNeverInstalledAreSkippedOnQuit() = runTest {
        val env = Env(this, error = "Port in use", integration = IntegrationState())
        var exits = 0
        env.controller.shutdown(cleanup = {}) { exits++ }
        advanceUntilIdle()
        assertTrue(env.statuses().values.all { it == StepStatus.Skipped })
        assertEquals(1, exits)
    }

    @Test
    fun failedCleanupWaitsThenSecondCloseQuits() = runTest {
        val env = Env(this, integration = installed)
        var exits = 0
        env.controller.shutdown(cleanup = { env.integration.value = removed.copy(snippet = PartState.Failed("CLAUDE.md is read-only")) }) { exits++ }
        advanceUntilIdle()
        assertEquals(StepStatus.Failed("CLAUDE.md is read-only"), env.statuses()[LifecycleStep.SNIPPET])
        assertEquals(LifecyclePhase.StopFailed, env.ui.phase)
        assertEquals(0, exits)

        env.controller.shutdown(cleanup = { error("must not run twice") }) { exits++ }
        assertEquals(1, exits)
        env.controller.quitNow()
        assertEquals(1, exits)
    }

    @Test
    fun hubPartsFollowStepStatus() {
        assertEquals(PartVisual(), partVisual(StepStatus.Pending))
        assertEquals(PartVisual(seen = 1f, drawn = 1f, glow = 1f), partVisual(StepStatus.Running))
        assertEquals(PartVisual(seen = 1f, drawn = 1f, lit = 1f), partVisual(StepStatus.Done))
        assertEquals(PartVisual(seen = 1f, drift = 1f), partVisual(StepStatus.Removed))
        assertEquals(PartVisual(seen = 1f, drawn = 1f, error = 1f), partVisual(StepStatus.Failed("x")))
    }
}
