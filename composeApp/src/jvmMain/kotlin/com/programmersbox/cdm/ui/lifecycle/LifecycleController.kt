package com.programmersbox.cdm.ui.lifecycle

import com.programmersbox.cdm.install.IntegrationState
import com.programmersbox.cdm.install.PartState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long each beat of the animation lasts. The real work takes milliseconds, so these set the pace. */
data class LifecycleTiming(
    val startStep: Duration = 220.milliseconds,
    val stopStep: Duration = 170.milliseconds,
    val gap: Duration = 60.milliseconds,
    /** "Ready" stays on the card this long before the card hides itself. */
    val readyHold: Duration = 900.milliseconds,
    /** "Connected" stays in the title bar this long after the card hides. */
    val pillLinger: Duration = 1500.milliseconds,
    /** "All clear" shows this long before the window closes. */
    val stoppedHold: Duration = 350.milliseconds,
    /** The device step gives up waiting for the first adb scan after this long. */
    val scanTimeout: Duration = 4.seconds,
)

/**
 * Plays the start-up and quit checklists. It does not do the install itself: by the time the window shows, the
 * server has bound and [com.programmersbox.cdm.install.InstallLifecycle] has installed (or failed to), so start-up
 * replays those results one step at a time. On quit the real clean-up runs first, all at once (so a force quit
 * mid-animation still leaves Claude unhooked), and the rows then report what it did.
 */
class LifecycleController(
    private val scope: CoroutineScope,
    private val serverError: StateFlow<String?>,
    private val integration: StateFlow<IntegrationState>,
    private val scanned: StateFlow<Boolean>,
    private val deviceCount: () -> Int,
    private val repairIntegration: suspend () -> Boolean,
    private val timing: LifecycleTiming = LifecycleTiming(),
) {
    private val _ui = MutableStateFlow(LifecycleUi())
    val ui: StateFlow<LifecycleUi> = _ui.asStateFlow()

    private var job: Job? = null
    private var port: Int? = null
    private var exit: (() -> Unit)? = null

    fun startup(port: Int?) {
        if (_ui.value.phase.isShutdown) return
        this.port = port
        job?.cancel()
        job = scope.launch { playStartup(port) }
    }

    /** Hides the card; the title bar keeps showing progress. */
    fun dismiss() = _ui.update { it.copy(cardVisible = false) }

    /** Brings the card back while something is running or has failed. */
    fun reopen() = _ui.update { if (it.running || it.failed) it.copy(cardVisible = true) else it }

    /** Retries the install after a failed start-up, then replays the checklist. */
    fun repair() {
        if (!_ui.value.canRepair) return
        job?.cancel()
        job = scope.launch {
            repairIntegration()
            playStartup(port)
        }
    }

    /**
     * Window close or Cmd+Q. Runs [cleanup] right away, plays the quit checklist, then calls [onExit]. A close while
     * the checklist is already playing is ignored; one after it failed quits at once.
     */
    fun shutdown(cleanup: () -> Unit, onExit: () -> Unit) {
        when (_ui.value.phase) {
            LifecyclePhase.Stopping, LifecyclePhase.Stopped -> return
            LifecyclePhase.StopFailed -> return quitNow()
            else -> {}
        }
        exit = onExit
        job?.cancel()
        // What was there before clean-up: a part we never installed is "skipped", not "removed".
        val before = integration.value
        val serverWasUp = serverError.value == null
        _ui.value = LifecycleUi(
            phase = LifecyclePhase.Stopping,
            rows = SHUTDOWN_STEPS.map { StepRow(it, StepStatus.Done, detail(it)) },
            cardVisible = true,
            deviceCount = _ui.value.deviceCount,
        )
        runCatching(cleanup).onFailure { System.err.println("cdm: clean-up failed: $it") }
        job = scope.launch { playShutdown(before, serverWasUp) }
    }

    /** "Quit anyway" after a failed clean-up. */
    fun quitNow() {
        val e = exit ?: return
        exit = null
        e()
    }

    private suspend fun playStartup(port: Int?) {
        val serverErr = serverError.value
        _ui.value = LifecycleUi(
            phase = LifecyclePhase.Starting,
            rows = STARTUP_STEPS.map { StepRow(it, StepStatus.Pending, detail(it)) },
            cardVisible = _ui.value.cardVisible || _ui.value.phase == LifecyclePhase.Idle,
        )
        for (step in STARTUP_STEPS) {
            setStatus(step, StepStatus.Running)
            delay(timing.startStep)
            val result = when (step) {
                LifecycleStep.SERVER -> serverErr?.let { StepStatus.Failed(it) } ?: StepStatus.Done
                LifecycleStep.HOOKS, LifecycleStep.CDM, LifecycleStep.SNIPPET ->
                    if (serverErr != null) StepStatus.Skipped else installed(part(integration.value, step))
                LifecycleStep.DEVICES -> {
                    withTimeoutOrNull(timing.scanTimeout) { scanned.first { it } }
                    val n = deviceCount()
                    _ui.update { it.copy(deviceCount = n) }
                    setDetail(step, "$n found")
                    StepStatus.Done
                }
            }
            setStatus(step, result)
            delay(timing.gap)
        }
        if (_ui.value.rows.any { it.status is StepStatus.Failed }) {
            _ui.update { it.copy(phase = LifecyclePhase.StartFailed) }
            return
        }
        _ui.update { it.copy(phase = LifecyclePhase.Ready) }
        if (_ui.value.cardVisible) delay(timing.readyHold)
        _ui.update { it.copy(cardVisible = false) }
        delay(timing.pillLinger)
        _ui.update { if (it.phase == LifecyclePhase.Ready) LifecycleUi(deviceCount = it.deviceCount) else it }
    }

    private suspend fun playShutdown(before: IntegrationState, serverWasUp: Boolean) {
        val after = integration.value
        for (step in SHUTDOWN_STEPS) {
            setStatus(step, StepStatus.Running)
            delay(timing.stopStep)
            val result = when (step) {
                LifecycleStep.SERVER -> if (serverWasUp) StepStatus.Removed else StepStatus.Skipped
                else -> removed(part(before, step), part(after, step))
            }
            setStatus(step, result)
            delay(timing.gap)
        }
        if (_ui.value.rows.any { it.status is StepStatus.Failed }) {
            _ui.update { it.copy(phase = LifecyclePhase.StopFailed, cardVisible = true) }
            return
        }
        _ui.update { it.copy(phase = LifecyclePhase.Stopped) }
        delay(timing.stoppedHold)
        quitNow()
    }

    private fun installed(p: PartState): StepStatus = when (p) {
        PartState.Installed -> StepStatus.Done
        PartState.NotInstalled -> StepStatus.Failed("Not installed")
        is PartState.Failed -> StepStatus.Failed(p.message)
    }

    private fun removed(before: PartState, after: PartState): StepStatus = when {
        after is PartState.Failed -> StepStatus.Failed(after.message)
        after == PartState.Installed -> StepStatus.Failed("Still installed; remove it by hand")
        before == PartState.NotInstalled -> StepStatus.Skipped
        else -> StepStatus.Removed
    }

    private fun part(s: IntegrationState, step: LifecycleStep): PartState = when (step) {
        LifecycleStep.HOOKS -> s.hooks
        LifecycleStep.CDM -> s.cdm
        LifecycleStep.SNIPPET -> s.snippet
        else -> PartState.Installed
    }

    private fun detail(step: LifecycleStep): String = when (step) {
        LifecycleStep.SERVER -> port?.let { ":$it" } ?: ""
        LifecycleStep.HOOKS -> "4 hooks"
        LifecycleStep.CDM -> "~/.local/bin"
        LifecycleStep.SNIPPET -> "~/.claude"
        LifecycleStep.DEVICES -> ""
    }

    private fun setStatus(step: LifecycleStep, status: StepStatus) = _ui.update { ui ->
        ui.copy(rows = ui.rows.map { if (it.step == step) it.copy(status = status) else it })
    }

    private fun setDetail(step: LifecycleStep, detail: String) = _ui.update { ui ->
        ui.copy(rows = ui.rows.map { if (it.step == step) it.copy(detail = detail) else it })
    }
}
