package com.jacobrein.cdm.install

import com.jacobrein.cdm.server.ServerListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One piece of the Claude Code integration, as Settings shows it. */
sealed interface PartState {
    data object Installed : PartState
    data object NotInstalled : PartState
    data class Failed(val message: String) : PartState
}

data class IntegrationState(
    val hooks: PartState = PartState.NotInstalled,
    val cdm: PartState = PartState.NotInstalled,
    val snippet: PartState = PartState.NotInstalled,
    /** The port the hooks were last installed for; null before the server first came up. */
    val port: Int? = null,
    val cdmOnPath: Boolean = true,
) {
    val allInstalled: Boolean get() = hooks == PartState.Installed && cdm == PartState.Installed && snippet == PartState.Installed
}

/**
 * Spec "Automatic install and uninstall" (Ruling T): the integration exists only while the app runs.
 * [onStarted] runs after every successful bind (startup, Retry, port change) and installs hooks, cdm and the snippet,
 * then prunes backups. [onExit] runs once on window close, Cmd+Q or the JVM shutdown hook and removes them again, in
 * the order hooks, snippet, cdm. It is plain synchronous file I/O, so it is safe from a shutdown hook, and it removes
 * nothing if this instance never installed (a second instance that failed to bind must not undo the first one's).
 */
class InstallLifecycle(private val installer: Installer) : ServerListener {
    private val lock = Any()
    private val exited = AtomicBoolean(false)
    @Volatile private var installedPort: Int? = null
    /** Our integration is on disk (guarded by [lock]). Only then do we remove anything. */
    private var installed = false
    private val errors = arrayOfNulls<String>(3) // hooks, cdm, snippet: the last operation's failure

    private val _state = MutableStateFlow(IntegrationState())
    val state: StateFlow<IntegrationState> = _state.asStateFlow()

    val snippet: String get() = installer.claudeMdSnippet

    override suspend fun onListening(port: Int) = onStarted(port)

    /**
     * A restart failed and nothing listens now: remove the integration so Claude doesn't reach for a dead port. A
     * later successful Retry or port change re-installs it. A first start that failed installed nothing, so this
     * removes nothing (another instance's install stays).
     */
    override suspend fun onNotListening() {
        withContext(Dispatchers.IO) { synchronized(lock) { if (!exited.get()) uninstallLocked() } }
    }

    suspend fun onStarted(port: Int) { withContext(Dispatchers.IO) { install(port) } }

    /** Re-runs the startup install on the last port. False when there is nothing to repair (never started, or exited). */
    suspend fun repair(): Boolean = withContext(Dispatchers.IO) {
        val port = installedPort ?: return@withContext false
        install(port)
    }

    /** Re-reads what is on disk (files edited by hand) without changing anything. */
    suspend fun refresh() = withContext(Dispatchers.IO) { synchronized(lock) { publish() } }

    fun onExit() {
        if (!exited.compareAndSet(false, true)) {
            // The other caller (window close vs shutdown hook) may be mid-uninstall: don't let the JVM halt under it.
            synchronized(lock) {}
            return
        }
        synchronized(lock) { uninstallLocked() }
    }

    private fun uninstallLocked() {
        if (!installed) return
        errors[0] = installer.uninstallHooks().exceptionOrNull()?.let(::logged)
        errors[2] = installer.removeSnippet().exceptionOrNull()?.let(::logged)
        errors[1] = runCatching { installer.uninstallCdm() }.exceptionOrNull()?.let(::logged)
        installed = false
        runCatching { publish() }
    }

    private fun install(port: Int): Boolean = synchronized(lock) {
        if (exited.get()) return false
        installedPort = port
        installed = true
        errors[0] = installer.installHooks(port).exceptionOrNull()?.let(::logged)
        errors[1] = runCatching { installer.installCdm() }.exceptionOrNull()?.let(::logged)
        errors[2] = installer.appendSnippet().exceptionOrNull()?.let(::logged)
        runCatching { installer.pruneBackups() }.exceptionOrNull()?.let(::logged)
        publish()
        _state.value.allInstalled
    }

    private fun logged(e: Throwable): String {
        System.err.println("cdm: integration: $e")
        return e.message ?: e.toString()
    }

    private fun publish() {
        val port = installedPort
        val hooks = errors[0]?.let { PartState.Failed(it) } ?: when (val st = port?.let(installer::hookStatus)) {
            InstallStatus.Installed -> PartState.Installed
            InstallStatus.SettingsUnreadable -> PartState.Failed("~/.claude/settings.json can't be read; fix it by hand, then Repair.")
            is InstallStatus.Outdated -> PartState.Failed("The hooks point at port ${st.installedPort ?: "?"}, not $port; Repair.")
            InstallStatus.NotInstalled, null -> PartState.NotInstalled
        }
        val cdm = errors[1]?.let { PartState.Failed(it) }
            ?: if (runCatching { installer.cdmInstalled() }.getOrDefault(false)) PartState.Installed else PartState.NotInstalled
        val snippet = errors[2]?.let { PartState.Failed(it) }
            ?: if (runCatching { installer.snippetInstalled() }.getOrDefault(false)) PartState.Installed else PartState.NotInstalled
        _state.value = IntegrationState(hooks, cdm, snippet, port, installer.localBinOnPath())
    }
}
