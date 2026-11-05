package com.programmersbox.cdm.ui.window

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Taskbar
import javax.imageio.ImageIO
import org.jetbrains.skia.Image

/** The app icon ("hub" design), rendered from `app-icon.svg` into the `app-icon.png` resource. */
object AppIcon {
    private const val RESOURCE = "app-icon.png"

    private fun bytes(): ByteArray? =
        AppIcon::class.java.classLoader.getResourceAsStream(RESOURCE)?.use { it.readAllBytes() }

    /** Window icon; null if the resource is missing so the window still opens. */
    val painter: Painter? by lazy {
        runCatching { bytes()?.let { BitmapPainter(Image.makeFromEncoded(it).toComposeImageBitmap()) } }.getOrNull()
    }

    /** Sets the macOS Dock icon when running unpackaged (e.g. `./gradlew run`); packaged apps use the .icns. */
    fun applyToDock() {
        runCatching {
            if (!Taskbar.isTaskbarSupported()) return
            val taskbar = Taskbar.getTaskbar()
            if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) return
            val image = bytes()?.let { ImageIO.read(it.inputStream()) } ?: return
            taskbar.iconImage = image
        }
    }
}
