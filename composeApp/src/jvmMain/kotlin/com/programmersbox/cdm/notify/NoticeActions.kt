package com.programmersbox.cdm.notify

import com.programmersbox.cdm.ui.UiActions
import com.programmersbox.cdm.ui.nav.Navigator
import com.programmersbox.cdm.ui.nav.Route
import com.programmersbox.cdm.ui.nav.TopLevel
import com.programmersbox.cdm.ui.window.MainWindowHolder
import javax.swing.SwingUtilities

/** What a notification's click and buttons do. Called off the UI thread. */
interface NoticeActions {
    /** Raises the window and shows [target] (or just raises it when null). */
    fun open(target: NoticeTarget?)

    /** Releases the device, but only if [held]'s holder still has it: a stale notification must not free it from someone else. */
    fun release(held: HeldDevice)
}

class AppNoticeActions(
    private val navigator: Navigator,
    private val window: MainWindowHolder,
    private val actions: UiActions,
) : NoticeActions {
    override fun open(target: NoticeTarget?) {
        window.bringToFront()
        if (target == null) return
        // The Navigator's back stack is snapshot state read by the UI; change it on the UI thread.
        SwingUtilities.invokeLater {
            when (target) {
                is NoticeTarget.Device -> {
                    navigator.selectTopLevel(TopLevel.DEVICES)
                    navigator.openDetail(Route.DeviceDetail(target.key))
                }
                is NoticeTarget.Queue -> {
                    navigator.selectTopLevel(TopLevel.QUEUE)
                    navigator.openDetail(Route.QueueDetail(target.holder))
                }
                NoticeTarget.Devices -> navigator.selectTopLevel(TopLevel.DEVICES)
                NoticeTarget.QueueList -> navigator.selectTopLevel(TopLevel.QUEUE)
            }
        }
    }

    override fun release(held: HeldDevice) = actions.run {
        if (snapshot.value.holds[held.device] == held.holder) releaseDevice(held.device, block = false)
    }
}
