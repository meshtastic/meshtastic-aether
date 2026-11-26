package org.meshtastic.ambient

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIApplication

fun ViewController() = ComposeUIViewController {
    UIApplication.sharedApplication.idleTimerDisabled = true
    App()
}
