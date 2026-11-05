package com.jacobrein.cdm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.data.AppSettings
import com.jacobrein.cdm.data.SettingsRepository
import com.jacobrein.cdm.install.InstallStatus
import com.jacobrein.cdm.install.Installer
import com.jacobrein.cdm.server.ServerControl
import com.jacobrein.cdm.server.ServerStatus
import com.jacobrein.cdm.ui.UiActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The outcome of an installer action, shown under its button. */
data class ActionResult(val ok: Boolean, val text: String)

data class SettingsState(
    /** False until the stored settings have been read into the fields. */
    val loaded: Boolean = false,
    val saved: AppSettings = AppSettings(),
    val port: String = "",
    val idle: String = "",
    val editing: String = "",
    val portError: String? = null,
    val idleError: String? = null,
    val editingError: String? = null,
    val saving: Boolean = false,
    /** Set when saving failed. */
    val saveResult: ActionResult? = null,
    val serverError: String? = null,
    /** Null while being read. */
    val hookStatus: InstallStatus? = null,
    val hookResult: ActionResult? = null,
    val cdmResult: ActionResult? = null,
    val snippet: String = "",
    val copied: Boolean = false,
    val confirmAppend: Boolean = false,
    val appendResult: ActionResult? = null,
) {
    /** The fields differ from what's stored. */
    val dirty: Boolean get() = loaded && (port != "${saved.port}" || idle != "${saved.idleMinutes}" || editing != "${saved.editingMinutes}")
    val valid: Boolean get() = portError == null && idleError == null && editingError == null

    /** Save does something: there are edits, or the server is down and Save retries it. */
    val canSave: Boolean get() = loaded && valid && !saving && (dirty || serverError != null)

    /** The HTTP server is up (no start failure recorded). */
    val listening: Boolean get() = serverError == null
}

/**
 * Settings: the three numbers are edited as text and applied together with Save (applying per keystroke would set
 * a 1-minute timer on the way to typing 15, or restart the server on every digit). Every write runs on the app scope
 * (Ruling R) so it completes even if the screen closes mid-way; the app scope is on Dispatchers.Default, so the
 * installer's small file reads and writes stay off the UI thread.
 */
class SettingsViewModel(
    private val actions: UiActions,
    private val settings: SettingsRepository,
    private val installer: Installer,
    private val server: ServerControl,
    serverStatus: ServerStatus,
) : ViewModel() {
    private val state = MutableStateFlow(SettingsState(snippet = installer.claudeMdSnippet))
    val uiState: StateFlow<SettingsState> = state.asStateFlow()
    private var copiedJob: Job? = null

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                val wasLoaded = state.value.loaded
                state.update { st ->
                    // Fill the fields on first load, and after a save; never over the user's unsaved edits.
                    if (!st.loaded || !st.dirty) {
                        st.copy(loaded = true, saved = s, port = "${s.port}", idle = "${s.idleMinutes}", editing = "${s.editingMinutes}", portError = null, idleError = null, editingError = null)
                    } else st.copy(saved = s)
                }
                if (!wasLoaded) refreshHooks()
            }
        }
        viewModelScope.launch { serverStatus.serverError.collect { e -> state.update { it.copy(serverError = e) } } }
    }

    fun onPortChanged(text: String) = state.update { it.copy(port = text, portError = portError(text)) }
    fun onIdleChanged(text: String) = state.update { it.copy(idle = text, idleError = minutesError(text)) }
    fun onEditingChanged(text: String) = state.update { it.copy(editing = text, editingError = minutesError(text)) }

    /**
     * Persists valid, changed fields; a new port also restarts the server (and leaves the hooks Outdated). While the
     * server is down (a failed start), Save also retries it on the saved port even with nothing edited.
     */
    fun onSave() {
        val st = state.value
        val pe = portError(st.port)
        val ie = minutesError(st.idle)
        val ee = minutesError(st.editing)
        state.update { it.copy(portError = pe, idleError = ie, editingError = ee) }
        if (pe != null || ie != null || ee != null || st.saving || !st.loaded) return
        val new = AppSettings(st.port.trim().toInt(), st.idle.trim().toInt(), st.editing.trim().toInt())
        // "030" -> "30", so a value equal to the stored one doesn't leave the form dirty forever.
        state.update { it.copy(port = "${new.port}", idle = "${new.idleMinutes}", editing = "${new.editingMinutes}") }
        val changed = new != st.saved
        val portChanged = new.port != st.saved.port
        val restart = portChanged || st.serverError != null
        if (!changed && !restart) return
        state.update { it.copy(saving = true, saveResult = null) }
        actions.launch {
            var result: ActionResult? = null
            try {
                if (changed) settings.update { it.copy(port = new.port, idleMinutes = new.idleMinutes, editingMinutes = new.editingMinutes) }
                if (restart) server.restart(new.port)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                result = ActionResult(false, "Could not save settings: ${e.message ?: e}")
            } finally {
                state.update { it.copy(saving = false, saveResult = result) }
                if (portChanged && result == null) refreshHooksNow(new.port)
            }
        }
    }

    /** The error banner's Retry: start the server again on the stored port. */
    fun onRetryServer() {
        val port = state.value.saved.port
        actions.launch { server.restart(port) }
    }

    fun onInstallHooks() = actions.launch {
        val port = settings.current().port
        val r = installer.installHooks(port)
        val result = r.fold(
            { path ->
                val where = if (path.fileName.toString() == "settings.json") "Created $path." else "Previous file backed up to $path."
                ActionResult(true, "Hooks installed for port $port. $where")
            },
            { ActionResult(false, it.message ?: it.toString()) },
        )
        state.update { it.copy(hookResult = result) }
        refreshHooksNow(port)
    }

    fun onInstallCdm() = actions.launch {
        val result = try {
            val path = installer.installCdm()
            val hint = if (installer.localBinOnPath()) "" else
                " ~/.local/bin isn't on your PATH: add `export PATH=\"\$HOME/.local/bin:\$PATH\"` to your shell profile."
            ActionResult(true, "Installed $path.$hint")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ActionResult(false, e.message ?: e.toString())
        }
        state.update { it.copy(cdmResult = result) }
    }

    /** The screen has put the snippet on the clipboard; show "Copied" for a moment. */
    fun onCopied() {
        state.update { it.copy(copied = true) }
        copiedJob?.cancel()
        copiedJob = viewModelScope.launch {
            delay(COPIED_MS)
            state.update { it.copy(copied = false) }
        }
    }

    /** Appending writes ~/.claude/CLAUDE.md, so ask first (in the page, no OS dialog). */
    fun onAppendSnippet() = state.update { it.copy(confirmAppend = true, appendResult = null) }
    fun onCancelAppend() = state.update { it.copy(confirmAppend = false) }

    fun onConfirmAppend() {
        if (!state.value.confirmAppend) return
        state.update { it.copy(confirmAppend = false) }
        actions.launch {
            val r = installer.appendSnippet()
            val result = r.fold(
                { ActionResult(true, "Added to ~/.claude/CLAUDE.md.") },
                { ActionResult(false, it.message ?: it.toString()) },
            )
            state.update { it.copy(appendResult = result) }
        }
    }

    /** Off the UI thread: the app scope runs on Dispatchers.Default. */
    private fun refreshHooks() {
        val port = state.value.saved.port
        actions.launch { refreshHooksNow(port) }
    }

    private suspend fun refreshHooksNow(port: Int) {
        val s = installer.hookStatus(port)
        state.update { it.copy(hookStatus = s) }
    }

    private companion object {
        const val COPIED_MS = 1_500L

        fun portError(text: String): String? = if (inRange(text, 1024..65535)) null else "Port must be 1024–65535"

        fun minutesError(text: String): String? = if (inRange(text, 1..240)) null else "Must be 1–240 minutes"

        private fun inRange(text: String, range: IntRange): Boolean = text.trim().toIntOrNull()?.let { it in range } == true
    }
}
