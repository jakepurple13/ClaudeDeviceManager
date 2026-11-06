package com.programmersbox.cdm.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    private class FakeUpdater(
        override val supported: Boolean = true,
        var justUpdated: String? = null,
    ) : AppUpdater {
        override val currentVersion = "1.0.0"
        val results = ArrayDeque<CheckResult>()
        var checks = 0
        var gate: CompletableDeferred<Unit>? = null
        var steps: List<DownloadStep> = listOf(DownloadStep(0.5f), DownloadStep(1f, File("/tmp/x.zip")))
        var failDownload: Throwable? = null
        var installed: File? = null

        override suspend fun check(): CheckResult {
            checks++
            gate?.await()
            return results.removeFirstOrNull() ?: CheckResult.UpToDate
        }

        override fun download(release: Release): Flow<DownloadStep> = flow {
            steps.forEach { emit(it) }
            failDownload?.let { throw it }
        }

        override fun installAndRestart(installer: File) { installed = installer }

        override fun consumeJustUpdated(): String? = justUpdated.also { justUpdated = null }
    }

    private fun TestScope.controller(updater: AppUpdater) = UpdateController(updater, backgroundScope, interval = 6.hours)

    @Test
    fun unsupportedNeverChecks() = runTest {
        val u = FakeUpdater(supported = false)
        val c = controller(u)
        c.start()
        c.checkNow()
        runCurrent()
        assertEquals(UpdateState.Unsupported, c.state.value)
        assertEquals(0, u.checks)
    }

    @Test
    fun startChecksNowAndEveryInterval() = runTest {
        val u = FakeUpdater()
        val c = controller(u)
        c.start()
        runCurrent()
        assertEquals(1, u.checks)
        assertEquals(UpdateState.UpToDate, c.state.value)
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(2, u.checks)
    }

    @Test
    fun availableThenDownloadThenReadyThenInstall() = runTest {
        val u = FakeUpdater().apply { results += CheckResult.Available(Release("1.1.0")) }
        val c = controller(u)
        c.start()
        runCurrent()
        assertEquals(UpdateState.Available("1.1.0"), c.state.value)
        c.download()
        runCurrent()
        assertEquals(UpdateState.Ready("1.1.0"), c.state.value)
        c.installAndRestart()
        runCurrent()
        assertEquals(File("/tmp/x.zip"), u.installed)
    }

    @Test
    fun downloadFailureGoesBackToAvailableWithError() = runTest {
        val u = FakeUpdater().apply {
            results += CheckResult.Available(Release("1.1.0"))
            failDownload = IllegalStateException("checksum mismatch")
        }
        val c = controller(u)
        c.start()
        runCurrent()
        c.download()
        runCurrent()
        val s = assertIs<UpdateState.Available>(c.state.value)
        assertEquals("1.1.0", s.version)
        assertEquals("checksum mismatch", s.error)
    }

    @Test
    fun downloadWithoutFileIsAnError() = runTest {
        val u = FakeUpdater().apply {
            results += CheckResult.Available(Release("1.1.0"))
            steps = listOf(DownloadStep(1f))
        }
        val c = controller(u)
        c.start()
        runCurrent()
        c.download()
        runCurrent()
        assertTrue(assertIs<UpdateState.Available>(c.state.value).error != null)
    }

    @Test
    fun periodicCheckDoesNotDiscardADownloadedUpdate() = runTest {
        val u = FakeUpdater().apply { results += CheckResult.Available(Release("1.1.0")) }
        val c = controller(u)
        c.start()
        runCurrent()
        c.download()
        runCurrent()
        advanceTimeBy(6.hours)
        runCurrent()
        assertEquals(UpdateState.Ready("1.1.0"), c.state.value)
    }

    @Test
    fun checkNowWhileCheckingIsIgnored() = runTest {
        val u = FakeUpdater().apply { gate = CompletableDeferred() }
        val c = controller(u)
        c.checkNow()
        runCurrent()
        assertEquals(UpdateState.Checking, c.state.value)
        c.checkNow()
        runCurrent()
        assertEquals(1, u.checks)
        u.gate!!.complete(Unit)
        runCurrent()
        assertEquals(UpdateState.UpToDate, c.state.value)
    }

    @Test
    fun failedCheckIsShown() = runTest {
        val u = FakeUpdater().apply { results += CheckResult.Failed("HTTP 404") }
        val c = controller(u)
        c.checkNow()
        runCurrent()
        assertEquals(UpdateState.Failed("HTTP 404"), c.state.value)
    }

    @Test
    fun dismissHidesBannerForThatVersionOnly() = runTest {
        val u = FakeUpdater().apply {
            results += CheckResult.Available(Release("1.1.0"))
            results += CheckResult.Available(Release("1.1.0"))
            results += CheckResult.Available(Release("1.2.0"))
        }
        val c = controller(u)
        c.checkNow()
        runCurrent()
        assertTrue(c.bannerVisible.value)
        c.dismissBanner()
        runCurrent()
        assertTrue(!c.bannerVisible.value)
        c.checkNow()
        runCurrent()
        assertTrue(!c.bannerVisible.value)
        c.checkNow()
        runCurrent()
        assertTrue(c.bannerVisible.value)
    }

    @Test
    fun justUpdatedIsReportedOnce() = runTest {
        val u = FakeUpdater(justUpdated = "1.1.0")
        val c = controller(u)
        c.start()
        runCurrent()
        assertEquals("1.1.0", c.justUpdatedTo.value)
        c.dismissJustUpdated()
        assertNull(c.justUpdatedTo.value)
    }
}
