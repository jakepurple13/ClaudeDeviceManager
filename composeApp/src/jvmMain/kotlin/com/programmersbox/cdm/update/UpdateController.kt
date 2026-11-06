package com.programmersbox.cdm.update

import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface UpdateState {
    /** Running from Gradle or a format that can't update itself: no checks. */
    data object Unsupported : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Failed(val message: String) : UpdateState
    /** [error] is set when the last download of it failed. */
    data class Available(val version: String, val error: String? = null) : UpdateState
    data class Downloading(val version: String, val fraction: Float) : UpdateState
    /** Downloaded and verified; installing restarts the app. */
    data class Ready(val version: String) : UpdateState
}

/** The version an Available, Downloading or Ready state is about. */
val UpdateState.pendingVersion: String?
    get() = when (this) {
        is UpdateState.Available -> version
        is UpdateState.Downloading -> version
        is UpdateState.Ready -> version
        else -> null
    }

/**
 * Checks GitHub for a newer release at start and every [interval], then downloads and installs it when asked.
 * Nothing downloads or restarts on its own: restarting drops every device hold, so the user picks the moment.
 * All work runs on [scope] (the app scope), so it outlives any screen.
 */
class UpdateController(
    private val updater: AppUpdater,
    private val scope: CoroutineScope,
    private val interval: Duration = 6.hours,
) {
    private val _state = MutableStateFlow(if (updater.supported) UpdateState.Idle else UpdateState.Unsupported)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = updater.currentVersion

    private val dismissed = MutableStateFlow<String?>(null)

    /** The app-wide banner: an update is pending and the user hasn't hidden it for this version. */
    val bannerVisible: StateFlow<Boolean> = combine(_state, dismissed) { s, d -> s.pendingVersion.let { it != null && it != d } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _justUpdatedTo = MutableStateFlow<String?>(null)

    /** Set on the first launch after an update, until dismissed. */
    val justUpdatedTo: StateFlow<String?> = _justUpdatedTo.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var installer: File? = null
    private var pending: Release? = null
    private var started = false

    fun start() {
        if (started || !updater.supported) return
        started = true
        _justUpdatedTo.value = updater.consumeJustUpdated()
        scope.launch {
            while (isActive) {
                runCheck()
                delay(interval)
            }
        }
    }

    fun checkNow() {
        if (!updater.supported) return
        scope.launch { runCheck() }
    }

    private suspend fun runCheck() {
        // A download in progress or finished stays put; a second check while one runs is a no-op.
        when (_state.value) {
            UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Ready -> return
            else -> Unit
        }
        val previous = _state.value
        _state.value = UpdateState.Checking
        val result = try {
            updater.check()
        } catch (e: CancellationException) {
            _state.value = previous
            throw e
        } catch (e: Throwable) {
            CheckResult.Failed(e.message ?: e.toString())
        }
        _state.value = when (result) {
            is CheckResult.Available -> {
                pending = result.release
                UpdateState.Available(result.release.version)
            }
            CheckResult.UpToDate -> UpdateState.UpToDate
            is CheckResult.Failed -> UpdateState.Failed(result.message)
        }
    }

    fun download() {
        val release = pending ?: return
        if (_state.value !is UpdateState.Available) return
        _state.value = UpdateState.Downloading(release.version, 0f)
        downloadJob = scope.launch {
            try {
                var file: File? = null
                updater.download(release).collect { step ->
                    if (step.file != null) file = step.file
                    _state.value = UpdateState.Downloading(release.version, step.fraction)
                }
                val done = file ?: error("The download finished without an installer.")
                installer = done
                _state.value = UpdateState.Ready(release.version)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release.version)
                throw e
            } catch (e: Throwable) {
                System.err.println("cdm: update download failed: $e")
                _state.value = UpdateState.Available(release.version, error = e.message ?: e.toString())
            }
        }
    }

    /** Runs the installer and exits; the JVM shutdown hook removes the Claude Code integration on the way out. */
    fun installAndRestart() {
        val file = installer ?: return
        if (_state.value !is UpdateState.Ready) return
        scope.launch {
            try {
                updater.installAndRestart(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                System.err.println("cdm: update install failed: $e")
                _state.update { s -> if (s is UpdateState.Ready) UpdateState.Failed("Install failed: ${e.message ?: e}") else s }
            }
        }
    }

    fun dismissBanner() {
        dismissed.value = _state.value.pendingVersion
    }

    fun dismissJustUpdated() {
        _justUpdatedTo.value = null
    }
}
