package org.meshtastic.aether

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import org.meshtastic.aether.log.Log
import platform.UIKit.UIApplication

fun ViewController() = ComposeUIViewController {
    remember {
        UIApplication.sharedApplication.idleTimerDisabled = true
        Log.i("keepawake", "idle timer disabled: ${UIApplication.sharedApplication.idleTimerDisabled}")
    }
    App()
}
