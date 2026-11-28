package org.meshtastic.aether

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.meshtastic.aether.ble.AndroidBlePermissions
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.ui.parseLaunchArgs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        var pending: CompletableDeferred<Boolean>? = null
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            pending?.complete(granted.isNotEmpty() && granted.values.all { it })
        }
        val ask: suspend (Array<String>) -> Boolean = { permissions ->
            val result = CompletableDeferred<Boolean>()
            pending = result
            launcher.launch(permissions)
            result.await()
        }
        AndroidBlePermissions.request = { ask(BLE_PERMISSIONS) }
        AndroidLocalNetworkPermission.request = { ask(arrayOf(LOCAL_NETWORK_PERMISSION)) }
        AndroidAppContext.context = applicationContext
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i("keepawake", "screen-on flag set")
        var immersive = true
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val launch = parseLaunchArgs(launchArgsFrom(intent))
        val isTelevision = (getSystemService(UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val xr = xrSpatialHost(this)
        setContent {
            App(
                launch = LaunchOptions(link = launch.link, startupError = launch.error, look = launch.look, ask = launch.ask),
                host = HostWindow(
                    onToggleFullscreen = if (isTelevision) {
                        null
                    } else {
                        {
                            immersive = !immersive
                            if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
                        }
                    },
                    onEnterSpatial = xr?.let { it::enter },
                    commands = Playback.commands,
                ),
            )
        }
        // Android 12 and later refuse a foreground service started from the background, so a session that starts behind
        // another app (the auto-connect countdown) gets its service on the next return to the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { Playback.commands.sessionRunning }.collect { running -> if (running) startPlayback() }
            }
        }
    }

    override fun onDestroy() {
        // The session lives in this activity's composition and ends with it.
        stopService(Intent(this, PlaybackService::class.java))
        super.onDestroy()
    }

    private fun startPlayback() {
        try {
            startForegroundService(Intent(this, PlaybackService::class.java))
        } catch (e: IllegalStateException) {
            Log.w("playback", "foreground service refused", e)
        }
    }
}

/**
 * The desktop flags, from intent extras, so a TV with no keyboard can be started from adb:
 * `am start -n org.meshtastic.aether/.MainActivity --es tcp host:port` (also `--es serial <port>`, `--ez demo true`, `--ez replay true`,
 * `--es look oled`, `--ez ask true`).
 */
internal fun launchArgsFrom(intent: android.content.Intent?): Array<String> = buildList {
    intent?.getStringExtra("tcp")?.let { add("--tcp"); add(it) }
    intent?.getStringExtra("serial")?.let { add("--serial"); add(it) }
    intent?.getStringExtra("look")?.let { add("--look"); add(it) }
    if (intent?.getBooleanExtra("demo", false) == true) add("--demo")
    if (intent?.getBooleanExtra("replay", false) == true) add("--replay")
    if (intent?.getBooleanExtra("ask", false) == true) add("--ask")
}.toTypedArray()

private val BLE_PERMISSIONS: Array<String> =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
