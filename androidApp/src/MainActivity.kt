package org.meshtastic.aether

import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CompletableDeferred
import org.meshtastic.aether.ble.AndroidBlePermissions
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.ui.parseLaunchArgs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        var pending: CompletableDeferred<Boolean>? = null
        val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            pending?.complete(granted.values.all { it })
        }
        AndroidBlePermissions.request = {
            val result = CompletableDeferred<Boolean>()
            pending = result
            launcher.launch(BLE_PERMISSIONS)
            result.await()
        }
        AndroidAppContext.context = applicationContext
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i("keepawake", "screen-on flag set")
        var immersive = true
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        val launch = parseLaunchArgs(launchArgsFrom(intent))
        val isTelevision = (getSystemService(UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
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
                ),
            )
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
