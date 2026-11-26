package org.meshtastic.ambient

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    val state = rememberWindowState()
    Window(onCloseRequest = ::exitApplication, title = "Meshtastic Ambient", state = state) {
        App(
            onToggleFullscreen = {
                state.placement = if (state.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
            },
        )
    }
}
