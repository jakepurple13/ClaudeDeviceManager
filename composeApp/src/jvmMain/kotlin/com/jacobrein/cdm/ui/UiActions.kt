package com.jacobrein.cdm.ui

import com.jacobrein.cdm.service.DeviceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs the user's device-manager actions on the app-lifetime scope, not a view model's, so an action still
 * completes when its screen closes (Remove from queue closes the pane at once; setDontUse writes the DB, then
 * the allocator, and must not stop halfway).
 */
class UiActions(val manager: DeviceManager, private val appScope: CoroutineScope) {
    fun run(action: suspend DeviceManager.() -> Unit) {
        appScope.launch { manager.action() }
    }
}
