package com.programmersbox.cdm.ui.update

import androidx.lifecycle.ViewModel
import com.programmersbox.cdm.update.UpdateController
import com.programmersbox.cdm.update.UpdateState
import kotlinx.coroutines.flow.StateFlow

/** The update banner and the Settings "Updates" card; all work runs in [UpdateController] on the app scope. */
class UpdateViewModel(private val controller: UpdateController) : ViewModel() {
    val state: StateFlow<UpdateState> = controller.state
    val bannerVisible: StateFlow<Boolean> = controller.bannerVisible
    val justUpdatedTo: StateFlow<String?> = controller.justUpdatedTo
    val currentVersion: String get() = controller.currentVersion

    fun onCheck() = controller.checkNow()
    fun onDownload() = controller.download()
    fun onInstall() = controller.installAndRestart()
    fun onDismissBanner() = controller.dismissBanner()
    fun onDismissJustUpdated() = controller.dismissJustUpdated()
}
