package com.programmersbox.cdm.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.programmersbox.cdm.data.AdbMode
import com.programmersbox.cdm.data.AppSettings
import com.programmersbox.cdm.data.NotificationSettings
import com.programmersbox.cdm.notify.NotificationTester
import com.programmersbox.cdm.data.PinnedPermission
import com.programmersbox.cdm.data.SettingsRepository
import com.programmersbox.cdm.devices.AdbLocation
import com.programmersbox.cdm.devices.AdbSelection
import com.programmersbox.cdm.install.InstallLifecycle
import com.programmersbox.cdm.install.IntegrationState
import com.programmersbox.cdm.server.ServerControl
import com.programmersbox.cdm.server.ServerStatus
import com.programmersbox.cdm.ui.UiActions
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The outcome of an action, shown under its button. */
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
    /** Hooks, cdm and the CLAUDE.md snippet: installed while the app runs, removed on exit. */
    val integration: IntegrationState = IntegrationState(),
    val repairing: Boolean = false,
    val repairResult: ActionResult? = null,
    val snippet: String = "",
    val copied: Boolean = false,
    /** The pinned-command permission mode shown; applied on selection, not with Save. */
    val pinnedPermission: PinnedPermission = PinnedPermission.MIRROR,
    /** Set when storing a pinned-command mode failed. */
    val pinnedResult: ActionResult? = null,
    /** The notification switches shown; applied on toggle, not with Save. */
    val notifications: NotificationSettings = NotificationSettings(),
    /** Set after a test send, or when storing a switch failed. */
    val notificationResult: ActionResult? = null,
    /** The adb choice shown; applied on selection, not with Save. */
    val adbMode: AdbMode = AdbMode.AUTO,
    /** The custom adb path field; stored with Use or Browse. */
    val adbPath: String = "",
    /** The adb the app runs now (null before the first resolve). */
    val adbInUse: AdbLocation? = null,
    /** The bundled adb, or null when this build has none. */
    val bundledAdb: String? = null,
    /** Set when storing the adb choice failed. */
    val adbResult: ActionResult? = null,
) {
    /** The path field differs from the stored custom path. */
    val adbPathDirty: Boolean get() = adbPath.trim() != saved.adbPath

    /** The fields differ from what's stored. */
    val dirty: Boolean get() = loaded && (port != "${saved.port}" || idle != "${saved.idleMinutes}" || editing != "${saved.editingMinutes}")
    val valid: Boolean get() = portError == null && idleError == null && editingError == null

    /** Save does something: there are edits, or the server is down and Save retries it. */
    val canSave: Boolean get() = loaded && valid && !saving && (dirty || serverError != null)

    /** The HTTP server is up (no start failure recorded). */
    val listening: Boolean get() = serverError == null

    /** Repair re-runs the startup install, which only makes sense while the server is up. */
    val canRepair: Boolean get() = listening && !repairing && integration.port != null
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
    private val integration: InstallLifecycle,
    private val server: ServerControl,
    serverStatus: ServerStatus,
    private val notificationTester: NotificationTester,
    private val adb: AdbSelection,
) : ViewModel() {
    private val state = MutableStateFlow(
        SettingsState(snippet = integration.snippet, integration = integration.state.value, bundledAdb = adb.bundledPath),
    )
    val uiState: StateFlow<SettingsState> = state.asStateFlow()
    private var copiedJob: Job? = null

    /** A selected mode not yet seen back from the DB, so a stale emission doesn't flick the radio back. */
    @Volatile private var pendingPinned: PinnedPermission? = null
    @Volatile private var pendingAdb: AdbMode? = null

    /** Switches toggled but not yet seen back from the DB, so a stale emission doesn't flick them back. */
    @Volatile private var pendingNotifications: NotificationSettings? = null

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                if (pendingPinned == s.pinnedPermission) pendingPinned = null
                val pinned = pendingPinned ?: s.pinnedPermission
                if (pendingNotifications == s.notifications) pendingNotifications = null
                val notifications = pendingNotifications ?: s.notifications
                if (pendingAdb == s.adbMode) pendingAdb = null
                val adbMode = pendingAdb ?: s.adbMode
                state.update { st ->
                    // The adb path field follows the store unless the user is mid-edit.
                    val path = if (!st.loaded || !st.adbPathDirty) s.adbPath else st.adbPath
                    // Fill the fields on first load, and after a save; never over the user's unsaved edits.
                    if (!st.loaded || !st.dirty) {
                        st.copy(loaded = true, saved = s, port = "${s.port}", idle = "${s.idleMinutes}", editing = "${s.editingMinutes}", portError = null, idleError = null, editingError = null, pinnedPermission = pinned, notifications = notifications, adbMode = adbMode, adbPath = path)
                    } else st.copy(saved = s, pinnedPermission = pinned, notifications = notifications, adbMode = adbMode, adbPath = path)
                }
            }
        }
        viewModelScope.launch { serverStatus.serverError.collect { e -> state.update { it.copy(serverError = e) } } }
        viewModelScope.launch { integration.state.collect { i -> state.update { it.copy(integration = i) } } }
        viewModelScope.launch { adb.current.collect { a -> state.update { it.copy(adbInUse = a) } } }
        // Pick up hand edits made since the last install; off the UI thread (app scope, Dispatchers.Default + IO).
        actions.launch { integration.refresh() }
    }

    fun onPortChanged(text: String) = state.update { it.copy(port = text, portError = portError(text)) }
    fun onIdleChanged(text: String) = state.update { it.copy(idle = text, idleError = minutesError(text)) }
    fun onEditingChanged(text: String) = state.update { it.copy(editing = text, editingError = minutesError(text)) }

    /**
     * Persists valid, changed fields; a new port also restarts the server, whose successful bind re-installs the hooks
     * for that port (InstallLifecycle listens to the server). While the
     * server is down (a failed start), Save also retries it on the saved port even with nothing edited.
     */
    fun onSave() {
        val st = state.value
        val pe = portError(st.port)
        val ie = minutesError(st.idle)
        val ee = minutesError(st.editing)
        state.update { it.copy(portError = pe, idleError = ie, editingError = ee) }
        if (pe != null || ie != null || ee != null || st.saving || !st.loaded) return
        val new = st.saved.copy(port = st.port.trim().toInt(), idleMinutes = st.idle.trim().toInt(), editingMinutes = st.editing.trim().toInt())
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
            }
        }
    }

    /** Stores the pinned-command mode at once (no Save); the hook reads it on its next call. */
    fun onPinnedPermissionSelected(mode: PinnedPermission) {
        if (mode == state.value.pinnedPermission) return
        pendingPinned = mode
        state.update { it.copy(pinnedPermission = mode, pinnedResult = null) }
        actions.launch {
            try {
                settings.update { it.copy(pinnedPermission = mode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (pendingPinned == mode) pendingPinned = null
                state.update { it.copy(pinnedPermission = it.saved.pinnedPermission, pinnedResult = ActionResult(false, "Could not save: ${e.message ?: e}")) }
            }
        }
    }

    /** Stores the adb choice at once (no Save); the next device scan runs the newly chosen adb. */
    fun onAdbModeSelected(mode: AdbMode) {
        if (mode == state.value.adbMode) return
        storeAdb(mode, path = null)
    }

    fun onAdbPathChanged(text: String) = state.update { it.copy(adbPath = text, adbResult = null) }

    /** Use: store the typed path and switch to it. */
    fun onAdbPathSubmit() = storeAdb(AdbMode.CUSTOM, state.value.adbPath.trim())

    /** Browse picked a file: fill the field and switch to it. */
    fun onAdbPathPicked(path: String) {
        state.update { it.copy(adbPath = path) }
        storeAdb(AdbMode.CUSTOM, path)
    }

    private fun storeAdb(mode: AdbMode, path: String?) {
        pendingAdb = mode
        state.update { it.copy(adbMode = mode, adbResult = null) }
        actions.launch {
            try {
                settings.update { s -> s.copy(adbMode = mode, adbPath = path ?: s.adbPath) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (pendingAdb == mode) pendingAdb = null
                state.update { it.copy(adbMode = it.saved.adbMode, adbResult = ActionResult(false, "Could not save: ${e.message ?: e}")) }
            }
        }
    }

    /** Stores the notification switches at once (no Save). */
    fun onNotificationsChanged(new: NotificationSettings) {
        if (new == state.value.notifications) return
        pendingNotifications = new
        state.update { it.copy(notifications = new, notificationResult = null) }
        actions.launch {
            try {
                settings.update { it.copy(notifications = new) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (pendingNotifications == new) pendingNotifications = null
                state.update { it.copy(notifications = it.saved.notifications, notificationResult = ActionResult(false, "Could not save: ${e.message ?: e}")) }
            }
        }
    }

    /** Sends one sample of each enabled notification, so the user can check the OS lets them through. */
    fun onSendTestNotification() {
        val n = state.value.notifications
        val count = listOf(n.grab, n.release, n.queued, n.queueStart).count { it }
        if (count == 0) return
        notificationTester.sendTest()
        val plural = if (count > 1) "s" else ""
        state.update { it.copy(notificationResult = ActionResult(true, "Sent $count test notification$plural. None showing? Check the OS notification settings for this app.")) }
    }

    /** The error banner's Retry: start the server again on the stored port. */
    fun onRetryServer() {
        val port = state.value.saved.port
        actions.launch { server.restart(port) }
    }

    /** Re-runs the startup install (hooks for the current port, cdm, snippet). */
    fun onRepair() {
        if (!state.value.canRepair) return
        state.update { it.copy(repairing = true, repairResult = null) }
        actions.launch {
            var result: ActionResult
            try {
                result = if (integration.repair()) ActionResult(true, "Repaired.")
                else ActionResult(false, "Some parts could not be installed; see above.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                result = ActionResult(false, e.message ?: e.toString())
            }
            state.update { it.copy(repairing = false, repairResult = result) }
        }
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

    private companion object {
        const val COPIED_MS = 1_500L

        fun portError(text: String): String? = if (inRange(text, 1024..65535)) null else "Port must be 1024–65535"

        fun minutesError(text: String): String? = if (inRange(text, 1..240)) null else "Must be 1–240 minutes"

        private fun inRange(text: String, range: IntRange): Boolean = text.trim().toIntOrNull()?.let { it in range } == true
    }
}
