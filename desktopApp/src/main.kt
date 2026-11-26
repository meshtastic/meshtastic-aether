package org.meshtastic.aether

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.ui.parseLaunchArgs
import java.awt.Cursor
import java.awt.Point
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

private const val ICON_RESOURCE = "icon.png"
private const val ICON_TAG = "icon"

private const val CURSOR_SIZE = 16

/** A fully transparent cursor: the app shows it after a few idle seconds so a projector shows no pointer. */
private fun blankCursor(): Cursor =
    Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(CURSOR_SIZE, CURSOR_SIZE, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")

/** The app icon from the module's resources, or null with a log line when it is missing or unreadable. */
private fun loadIcon(): BufferedImage? = try {
    Thread.currentThread().contextClassLoader.getResourceAsStream(ICON_RESOURCE)?.use { ImageIO.read(it) }
        ?: run {
            Log.w(ICON_TAG, "resource $ICON_RESOURCE not found; the default window icon stays")
            null
        }
} catch (e: Exception) {
    Log.w(ICON_TAG, "could not read $ICON_RESOURCE", e)
    null
}

/** On macOS the dock shows the process icon, which a window icon does not set. */
private fun setDockIcon(icon: BufferedImage) {
    try {
        if ("mac" in System.getProperty("os.name").orEmpty().lowercase() &&
            Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)
        ) {
            Taskbar.getTaskbar().iconImage = icon
        }
    } catch (e: Exception) {
        Log.w(ICON_TAG, "could not set the dock icon", e)
    }
}

fun main(args: Array<String>) {
    val launch = parseLaunchArgs(args)
    KeepAwake.start()
    val icon = loadIcon()
    icon?.let(::setDockIcon)
    val windowIcon = icon?.let { BitmapPainter(it.toComposeImageBitmap()) }
    application {
        val state = rememberWindowState(placement = if (launch.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating)
        val hidden = remember { PointerIcon(blankCursor()) }
        Window(
            onCloseRequest = {
                KeepAwake.stop()
                exitApplication()
            },
            title = "Meshtastic Aether",
            icon = windowIcon,
            state = state,
        ) {
            App(
                initialLink = launch.link,
                startupError = launch.error,
                isFullscreen = state.placement == WindowPlacement.Fullscreen,
                hiddenCursor = hidden,
                look = launch.look,
                launchedFullscreen = launch.fullscreen,
                onToggleFullscreen = {
                    state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                },
            )
        }
    }
}
