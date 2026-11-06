package com.programmersbox.cdm.devices

import com.programmersbox.cdm.data.AdbMode
import java.io.File

/** Where the `adb` the app runs came from. */
enum class AdbSource { AndroidSdk, Path, KnownLocation, Bundled, Custom, NotFound }

data class AdbLocation(val path: String, val source: AdbSource)

/**
 * Picks the `adb` binary. An adb the user already has wins over the copy bundled with the app: adb clients and the
 * adb server must be the same version, and a mismatched client kills and restarts the server, dropping every other
 * session's connection. The bundled copy is only used when no other adb can be found.
 *
 * Search order: `ANDROID_HOME` / `ANDROID_SDK_ROOT`, `PATH`, the default Android Studio SDK folder and Homebrew
 * (apps started from Finder don't get the shell's PATH), then `<app resources>/platform-tools`.
 */
class AdbLocator(
    private val env: Map<String, String> = System.getenv(),
    private val resourcesDir: String? = System.getProperty("compose.application.resources.dir"),
    private val home: String = System.getProperty("user.home"),
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows"),
    private val isMac: Boolean = System.getProperty("os.name").startsWith("Mac"),
    private val canExecute: (File) -> Boolean = { it.isFile && it.canExecute() },
    /** Makes a shipped-but-not-executable adb runnable; returns the runnable file or null. */
    private val makeRunnable: (File) -> File? = { extractToAppHome(it, File(home, ".claude-device-manager/platform-tools")) },
) {
    private val exe = if (isWindows) "adb.exe" else "adb"

    /**
     * The adb copy shipped in the app's resources, or null in builds without one (tests, Linux arm64). The packager
     * drops the executable bit and the install folder may be read-only (AppImage, /opt), so a non-executable copy is
     * run from an extracted copy under `~/.claude-device-manager` instead.
     */
    val bundled: File? get() {
        val shipped = resourcesDir?.let { File(File(it, "platform-tools"), exe) } ?: return null
        return when {
            canExecute(shipped) -> shipped
            shipped.isFile -> makeRunnable(shipped)?.takeIf(canExecute)
            else -> null
        }
    }

    /** The adb for the user's choice; [AdbSource.NotFound] carries the path that was missing (or blank). */
    fun resolve(mode: AdbMode, customPath: String): AdbLocation = when (mode) {
        AdbMode.AUTO -> locate()
        AdbMode.BUNDLED -> bundled?.let { AdbLocation(it.path, AdbSource.Bundled) } ?: AdbLocation(exe, AdbSource.NotFound)
        AdbMode.CUSTOM -> {
            val f = File(customPath.trim())
            if (customPath.isNotBlank() && canExecute(f)) AdbLocation(f.path, AdbSource.Custom)
            else AdbLocation(customPath.trim(), AdbSource.NotFound)
        }
    }

    fun locate(): AdbLocation {
        val candidates = sequence {
            for (key in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
                env[key]?.takeIf { it.isNotBlank() }?.let { yield(File(File(it, "platform-tools"), exe) to AdbSource.AndroidSdk) }
            }
            env["PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
                .forEach { yield(File(it, exe) to AdbSource.Path) }
            knownLocations().forEach { yield(it to AdbSource.KnownLocation) }
            bundled?.let { yield(it to AdbSource.Bundled) }
        }
        return candidates.firstOrNull { canExecute(it.first) }
            ?.let { (file, source) -> AdbLocation(file.path, source) }
            ?: AdbLocation(exe, AdbSource.NotFound)
    }

    companion object {
        /**
         * Copies [source] into [dir] and marks it executable. The copy keeps the source's timestamp, so a copy with the
         * same size and timestamp is the same file and is reused. The copy goes through a temp file and an atomic
         * rename, so a running adb server keeps its old file and an app update replaces it cleanly. Returns null when
         * the copy fails.
         */
        fun extractToAppHome(source: File, dir: File): File? = runCatching {
            val target = File(dir, source.name)
            val fresh = target.isFile && target.length() == source.length() && target.lastModified() == source.lastModified()
            if (!fresh) {
                dir.mkdirs()
                val tmp = File.createTempFile("adb", ".tmp", dir)
                try {
                    source.copyTo(tmp, overwrite = true)
                    tmp.setExecutable(true, false)
                    tmp.setLastModified(source.lastModified())
                    java.nio.file.Files.move(
                        tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                } finally {
                    tmp.delete()
                }
            }
            target.setExecutable(true, false)
            target
        }.getOrNull()
    }

    private fun knownLocations(): List<File> = when {
        isWindows -> listOfNotNull(env["LOCALAPPDATA"]?.let { File(it, "Android\\Sdk\\platform-tools\\$exe") })
        isMac -> listOf(
            File(home, "Library/Android/sdk/platform-tools/adb"),
            File("/opt/homebrew/bin/adb"),
            File("/usr/local/bin/adb"),
        )
        else -> listOf(File(home, "Android/Sdk/platform-tools/adb"), File("/usr/bin/adb"), File("/usr/local/bin/adb"))
    }
}
