package com.programmersbox.cdm.devices

import com.programmersbox.cdm.data.AdbMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * The adb the app currently runs, re-resolved whenever the stored adb setting changes. [current] is null until the
 * first [apply], and [path] waits for it, so the first device scan never runs an adb the user has opted out of.
 */
class AdbSelection(private val locator: AdbLocator = AdbLocator()) {
    private val state = MutableStateFlow<AdbLocation?>(null)
    val current: StateFlow<AdbLocation?> = state.asStateFlow()

    /** The bundled copy, or null when this build has none. */
    val bundledPath: String? get() = locator.bundled?.path

    fun apply(mode: AdbMode, customPath: String) {
        state.value = locator.resolve(mode, customPath)
    }

    suspend fun path(): String = state.filterNotNull().first().path
}
