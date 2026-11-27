@file:OptIn(ExperimentalWasmJsInterop::class)

package org.meshtastic.aether

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => { if (document.fullscreenElement) document.exitFullscreen(); else document.documentElement.requestFullscreen(); }")
private external fun toggleBrowserFullscreen()

@JsFun(
    """() => {
  let lock = null;
  const request = async () => {
    if (lock && !lock.released) return;
    if (!navigator.wakeLock) { console.warn('keepawake: the browser has no wake lock; the screen may blank'); return; }
    try {
      lock = await navigator.wakeLock.request('screen');
      console.info('keepawake: wake lock held');
    } catch (e) {
      console.warn('keepawake: wake lock refused: ' + e);
    }
  };
  request();
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') request(); });
}""",
)
private external fun keepAwake()

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    keepAwake()
    ComposeViewport {
        App(host = HostWindow(onToggleFullscreen = ::toggleBrowserFullscreen))
    }
}
