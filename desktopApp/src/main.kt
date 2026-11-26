package org.meshtastic.ambient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.meshtastic.ambient.ui.LinkChoice

fun main(args: Array<String>) = application {
    val state = rememberWindowState()
    Window(onCloseRequest = ::exitApplication, title = "Meshtastic Ambient", state = state) {
        App(
            initialLink = linkFromArgs(args),
            onToggleFullscreen = {
                state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
            },
        )
    }
}

/** `--tcp host[:port]` connects at launch (port defaults to the phone API's 4403). */
private fun linkFromArgs(args: Array<String>): LinkChoice? {
    val value = args.indexOf("--tcp").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } ?: return null
    val host = value.substringBeforeLast(':')
    val port = value.substringAfterLast(':', "").toIntOrNull()
    return if (port == null) LinkChoice.Tcp(value, 4403) else LinkChoice.Tcp(host, port)
}
