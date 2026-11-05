package com.programmersbox.cdm.ui.window

/** Which title bar the main window draws: the app's own (Jewel decorated window) or the OS one. */
enum class WindowChrome { Custom, System }

/** Picks the title bar: the custom one whenever this runtime can draw it. */
fun windowChrome(supported: Boolean = customTitleBarSupported): WindowChrome =
    if (supported) WindowChrome.Custom else WindowChrome.System

/** Jewel 0.41 is Java 25 bytecode and its decorated window needs JetBrains Runtime. */
internal fun jewelUsable(jbrAvailable: Boolean, javaFeature: Int): Boolean = jbrAvailable && javaFeature >= 25

/**
 * True when this runtime can draw the custom title bar: JetBrains Runtime 25 or newer, and Jewel's window class
 * loads. False on a stock JDK, on an older JBR (an IDE run on the compile JDK), or if anything throws. Reflection
 * because jbr-api is only on the runtime classpath (via skiko). Checked once per process.
 */
val customTitleBarSupported: Boolean by lazy {
    runCatching {
        val jbr = Class.forName("com.jetbrains.JBR").getMethod("isAvailable").invoke(null) as Boolean
        // Loading without initialising still verifies the class file version.
        jewelUsable(jbr, Runtime.version().feature()) &&
            Class.forName("org.jetbrains.jewel.window.DecoratedWindowKt", false, WindowChrome::class.java.classLoader) != null
    }.getOrDefault(false)
}
