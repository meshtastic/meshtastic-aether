package org.meshtastic.ambient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.meshtastic.ambient.ui.LinkChoice
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

private const val CURSOR_SIZE = 16

/** A fully transparent cursor: the app shows it after a few idle seconds so a projector shows no pointer. */
private fun blankCursor(): Cursor =
    Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(CURSOR_SIZE, CURSOR_SIZE, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")

fun main(args: Array<String>) {
    val launch = parseArgs(args)
    KeepAwake.start()
    application {
        val state = rememberWindowState(placement = if (launch.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating)
        val hidden = remember { PointerIcon(blankCursor()) }
        Window(
            onCloseRequest = {
                KeepAwake.stop()
                exitApplication()
            },
            title = "Meshtastic Ambient",
            state = state,
        ) {
            App(
                initialLink = launch.link,
                startupError = launch.error,
                isFullscreen = state.placement == WindowPlacement.Fullscreen,
                hiddenCursor = hidden,
                onToggleFullscreen = {
                    state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                },
            )
        }
    }
}

/** What the command line asked for: a link to open, an argument that could not be read, and fullscreen. */
internal class LaunchArgs(val link: LinkChoice?, val error: String?, val fullscreen: Boolean)

/**
 * `--demo` starts the demo, `--tcp host[:port]` connects at launch (port defaults to the phone API's 4403),
 * `--replay` loops the bundled capture, `--fullscreen` opens fullscreen. A bad `--tcp` value is an error to show, not a link.
 */
internal fun parseArgs(args: Array<String>): LaunchArgs {
    val fullscreen = "--fullscreen" in args
    val tcpAt = args.indexOf("--tcp")
    if (tcpAt >= 0) {
        val value = args.getOrNull(tcpAt + 1).orEmpty()
        val tcp = LinkChoice.parseTcp(value)
        return if (tcp == null) {
            LaunchArgs(null, "Cannot use --tcp \"$value\": expected host or host:port with a port from 1 to 65535.", fullscreen)
        } else {
            LaunchArgs(tcp, null, fullscreen)
        }
    }
    val link = when {
        "--demo" in args -> LinkChoice.Demo
        "--replay" in args -> LinkChoice.Replay
        else -> null
    }
    return LaunchArgs(link, null, fullscreen)
}
