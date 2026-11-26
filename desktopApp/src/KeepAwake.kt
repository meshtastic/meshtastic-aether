package org.meshtastic.aether

import org.meshtastic.aether.log.Log
import java.util.concurrent.TimeUnit

private const val TAG = "keepawake"
private const val EARLY_EXIT_MILLIS = 500L

/**
 * Keeps the machine and display awake while the app runs, with the OS's own tool: `caffeinate` on macOS and
 * `systemd-inhibit` on Linux. Windows has none reachable from the JVM without native code, so the screen may blank
 * there; one log line says whether the tool is holding.
 */
internal object KeepAwake {
    private var process: Process? = null

    fun start() {
        val pid = ProcessHandle.current().pid().toString()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            "mac" in os -> listOf("caffeinate", "-d", "-i", "-w", pid)
            "linux" in os -> listOf(
                "systemd-inhibit", "--what=idle:sleep", "--who=Aether", "--why=Ambient display", "--mode=block",
                "tail", "--pid=$pid", "-f", "/dev/null",
            )
            else -> {
                Log.w(TAG, "no keep-awake tool for $os; the screen may blank")
                return
            }
        }
        process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (e: Exception) {
            Log.w(TAG, "${command.first()} did not start; the screen may blank", e)
            null
        }
        val started = process ?: return
        Thread {
            if (started.waitFor(EARLY_EXIT_MILLIS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "${command.first()} exited at once with ${started.exitValue()}; the screen may blank")
            } else {
                Log.i(TAG, "holding with ${command.first()}")
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
