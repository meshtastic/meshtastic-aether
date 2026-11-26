@file:OptIn(ExperimentalWasmJsInterop::class)

package org.meshtastic.ambient

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => { if (document.fullscreenElement) document.exitFullscreen(); else document.documentElement.requestFullscreen(); }")
private external fun toggleBrowserFullscreen()

@JsFun("() => { try { if (navigator.wakeLock) navigator.wakeLock.request('screen').catch(() => {}); } catch (e) {} }")
private external fun requestWakeLock()

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    requestWakeLock()
    ComposeViewport {
        App(onToggleFullscreen = ::toggleBrowserFullscreen)
    }
}
