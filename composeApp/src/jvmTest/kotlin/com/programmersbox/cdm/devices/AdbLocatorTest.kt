package com.programmersbox.cdm.devices

import com.programmersbox.cdm.data.AdbMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AdbLocatorTest {
    private val sep = File.pathSeparator

    private fun locator(
        existing: Set<String>,
        env: Map<String, String> = emptyMap(),
        resourcesDir: String? = "/app/resources",
        isWindows: Boolean = false,
        isMac: Boolean = true,
    ) = AdbLocator(
        env = env,
        resourcesDir = resourcesDir,
        home = "/home/me",
        isWindows = isWindows,
        isMac = isMac,
        canExecute = { it.path in existing },
    )

    @Test
    fun androidHomeWinsOverPathAndBundled() {
        val loc = locator(
            existing = setOf("/sdk/platform-tools/adb", "/usr/bin/adb", "/app/resources/platform-tools/adb"),
            env = mapOf("ANDROID_HOME" to "/sdk", "PATH" to "/usr/bin"),
        ).locate()
        assertEquals(AdbLocation("/sdk/platform-tools/adb", AdbSource.AndroidSdk), loc)
    }

    @Test
    fun pathWinsOverBundled() {
        val loc = locator(
            existing = setOf("/usr/bin/adb", "/app/resources/platform-tools/adb"),
            env = mapOf("PATH" to "/nope$sep/usr/bin"),
        ).locate()
        assertEquals(AdbLocation("/usr/bin/adb", AdbSource.Path), loc)
    }

    @Test
    fun finderLaunchFindsAndroidStudioSdkWithoutPath() {
        val loc = locator(
            existing = setOf("/home/me/Library/Android/sdk/platform-tools/adb", "/app/resources/platform-tools/adb"),
            env = mapOf("PATH" to "/usr/bin$sep/bin"),
        ).locate()
        assertEquals(AdbSource.KnownLocation, loc.source)
        assertEquals("/home/me/Library/Android/sdk/platform-tools/adb", loc.path)
    }

    @Test
    fun fallsBackToBundledCopy() {
        val loc = locator(existing = setOf("/app/resources/platform-tools/adb"), env = mapOf("PATH" to "/usr/bin")).locate()
        assertEquals(AdbLocation("/app/resources/platform-tools/adb", AdbSource.Bundled), loc)
    }

    @Test
    fun windowsLooksForAdbExe() {
        val bundled = File(File("/app/resources", "platform-tools"), "adb.exe").path
        val loc = locator(existing = setOf(bundled), isWindows = true, isMac = false).locate()
        assertEquals(AdbLocation(bundled, AdbSource.Bundled), loc)
    }

    @Test
    fun nothingFoundReturnsBareName() {
        val loc = locator(existing = emptySet(), resourcesDir = null).locate()
        assertEquals(AdbLocation("adb", AdbSource.NotFound), loc)
    }

    @Test
    fun bundledModeSkipsTheUsersAdb() {
        val loc = locator(existing = setOf("/usr/bin/adb", "/app/resources/platform-tools/adb"), env = mapOf("PATH" to "/usr/bin"))
            .resolve(AdbMode.BUNDLED, "")
        assertEquals(AdbLocation("/app/resources/platform-tools/adb", AdbSource.Bundled), loc)
    }

    @Test
    fun bundledModeWithoutBundledCopyIsNotFound() {
        val loc = locator(existing = setOf("/usr/bin/adb"), env = mapOf("PATH" to "/usr/bin")).resolve(AdbMode.BUNDLED, "")
        assertEquals(AdbSource.NotFound, loc.source)
    }

    @Test
    fun customModeUsesTheChosenFileOnly() {
        val l = locator(existing = setOf("/usr/bin/adb", "/opt/sdk/adb"), env = mapOf("PATH" to "/usr/bin"))
        assertEquals(AdbLocation("/opt/sdk/adb", AdbSource.Custom), l.resolve(AdbMode.CUSTOM, " /opt/sdk/adb "))
        assertEquals(AdbLocation("/missing/adb", AdbSource.NotFound), l.resolve(AdbMode.CUSTOM, "/missing/adb"))
        assertEquals(AdbLocation("", AdbSource.NotFound), l.resolve(AdbMode.CUSTOM, ""))
    }

    @Test
    fun selectionWaitsForFirstApply() = kotlinx.coroutines.runBlocking {
        val sel = AdbSelection(locator(existing = setOf("/app/resources/platform-tools/adb")))
        assertEquals(null, sel.current.value)
        sel.apply(AdbMode.BUNDLED, "")
        assertEquals("/app/resources/platform-tools/adb", sel.path())
    }

    @Test
    fun nonExecutableBundledCopyIsExtractedAndReused() {
        val res = kotlin.io.path.createTempDirectory("cdm-res").toFile()
        val home = kotlin.io.path.createTempDirectory("cdm-home").toFile()
        val shipped = File(res, "platform-tools/adb").apply { parentFile.mkdirs(); writeText("#!/bin/sh\necho adb\n") }
        shipped.setExecutable(false, false)
        val loc = AdbLocator(env = emptyMap(), resourcesDir = res.path, home = home.path, isWindows = false, isMac = true)
            .resolve(AdbMode.BUNDLED, "")
        val extracted = File(home, ".claude-device-manager/platform-tools/adb")
        assertEquals(AdbLocation(extracted.path, AdbSource.Bundled), loc)
        kotlin.test.assertTrue(extracted.canExecute())
        assertEquals(shipped.lastModified(), extracted.lastModified())
        // A second resolve reuses the copy; a changed shipped file (app update) replaces it.
        val firstCopyTime = extracted.lastModified()
        AdbLocator.extractToAppHome(shipped, extracted.parentFile)
        assertEquals(firstCopyTime, extracted.lastModified())
        shipped.writeText("#!/bin/sh\necho newer adb\n")
        shipped.setLastModified(firstCopyTime + 10_000)
        AdbLocator.extractToAppHome(shipped, extracted.parentFile)
        assertEquals(shipped.readText(), extracted.readText())
    }

    @Test
    fun blankSdkVariableIsIgnored() {
        val loc = locator(existing = setOf("/platform-tools/adb", "/usr/bin/adb"), env = mapOf("ANDROID_HOME" to "", "PATH" to "/usr/bin")).locate()
        assertEquals(AdbSource.Path, loc.source)
    }
}
