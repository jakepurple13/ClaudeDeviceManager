package com.programmersbox.cdm.update

import dev.nucleusframework.updater.NucleusUpdater
import dev.nucleusframework.updater.UpdateInfo
import dev.nucleusframework.updater.UpdateResult
import dev.nucleusframework.updater.provider.GenericProvider
import dev.nucleusframework.updater.provider.GitHubProvider
import dev.nucleusframework.updater.provider.UpdateProvider
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

/** A newer release found by a check. [handle] is the updater's own description of it, passed back to download it. */
class Release(val version: String, internal val handle: Any? = null)

sealed interface CheckResult {
    data class Available(val release: Release) : CheckResult
    data object UpToDate : CheckResult
    data class Failed(val message: String) : CheckResult
}

/** Download progress: [fraction] in 0..1; [file] is set on the last step, once the verified installer is on disk. */
data class DownloadStep(val fraction: Float, val file: File? = null)

/** The update operations the app uses; a seam so [UpdateController] can be tested without the network. */
interface AppUpdater {
    val currentVersion: String

    /** False when running from Gradle (`run`, tests) or from a format that can't update itself. */
    val supported: Boolean

    suspend fun check(): CheckResult

    fun download(release: Release): Flow<DownloadStep>

    /** Starts the installer and exits the JVM (shutdown hooks still run); the new version relaunches. */
    fun installAndRestart(installer: File)

    /** The version this launch was updated to, once; null if this launch wasn't the first after an update. */
    fun consumeJustUpdated(): String?
}

const val GITHUB_OWNER = "jakepurple13"
const val GITHUB_REPO = "ClaudeDeviceManager"

/**
 * Set to a folder URL holding `latest-*.yml` and the installers to update from there instead of GitHub, for testing
 * an update locally (`http` works only for localhost). Read at launch.
 */
const val UPDATE_URL_ENV = "CDM_UPDATE_URL"

private fun defaultProvider(): UpdateProvider =
    System.getenv(UPDATE_URL_ENV)?.takeIf { it.isNotBlank() }?.let(::GenericProvider)
        ?: GitHubProvider(GITHUB_OWNER, GITHUB_REPO)

/**
 * [AppUpdater] over Nucleus, reading `latest-*.yml` from this repo's GitHub releases. Builds are unsigned, so
 * macOS relies on Nucleus's ZIP swap clearing the quarantine flag on the replaced `.app`.
 */
class NucleusAppUpdater(
    private val updater: NucleusUpdater = NucleusUpdater { provider = defaultProvider() },
) : AppUpdater {
    override val currentVersion: String get() = updater.currentVersion

    override val supported: Boolean get() = runCatching { updater.isUpdateSupported() }.getOrDefault(false)

    override suspend fun check(): CheckResult = when (val r = updater.checkForUpdates()) {
        is UpdateResult.Available -> CheckResult.Available(Release(r.info.version, r.info))
        UpdateResult.NotAvailable -> CheckResult.UpToDate
        is UpdateResult.Error -> CheckResult.Failed(r.exception.message ?: r.exception.toString())
    }

    override fun download(release: Release): Flow<DownloadStep> {
        val info = release.handle as? UpdateInfo ?: return emptyFlow()
        return updater.downloadUpdate(info).map { p ->
            DownloadStep((p.percent / 100.0).toFloat().coerceIn(0f, 1f), p.file)
        }
    }

    override fun installAndRestart(installer: File) = updater.installAndRestart(installer)

    override fun consumeJustUpdated(): String? = runCatching { updater.consumeUpdateEvent()?.newVersion }.getOrNull()
}
