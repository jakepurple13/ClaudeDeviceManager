package com.programmersbox.cdm.update

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** An updater for UI tests: never checks the network, as in a Gradle run. */
object UnsupportedUpdater : AppUpdater {
    override val currentVersion = "1.0.0"
    override val supported = false
    override suspend fun check(): CheckResult = CheckResult.UpToDate
    override fun download(release: Release): Flow<DownloadStep> = emptyFlow()
    override fun installAndRestart(installer: File) = Unit
    override fun consumeJustUpdated(): String? = null
}
