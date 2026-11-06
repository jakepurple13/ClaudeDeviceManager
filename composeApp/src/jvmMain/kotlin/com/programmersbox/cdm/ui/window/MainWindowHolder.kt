package com.programmersbox.cdm.ui.window

import java.awt.Frame
import java.awt.Window
import javax.swing.SwingUtilities

/** The main window, once composed, so the tray menu and a clicked notification can raise it. */
class MainWindowHolder {
    @Volatile var window: Window? = null

    /** Un-minimizes the window and brings it to the front; safe from any thread. */
    fun bringToFront() {
        SwingUtilities.invokeLater {
            val w = window ?: return@invokeLater
            if (w is Frame && w.extendedState and Frame.ICONIFIED != 0) w.extendedState = w.extendedState and Frame.ICONIFIED.inv()
            w.isVisible = true
            w.toFront()
            w.requestFocus()
        }
    }
}
