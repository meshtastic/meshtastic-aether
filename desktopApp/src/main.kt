package org.meshtastic.aether

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.meshtastic.aether.ui.parseLaunchArgs
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

private const val CURSOR_SIZE = 16

/** A fully transparent cursor: the app shows it after a few idle seconds so a projector shows no pointer. */
private fun blankCursor(): Cursor =
    Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(CURSOR_SIZE, CURSOR_SIZE, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")

fun main(args: Array<String>) {
    val launch = parseLaunchArgs(args)
    KeepAwake.start()
    application {
        val state = rememberWindowState(placement = if (launch.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating)
        val hidden = remember { PointerIcon(blankCursor()) }
        Window(
            onCloseRequest = {
                KeepAwake.stop()
                exitApplication()
            },
            title = "Meshtastic Aether",
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
