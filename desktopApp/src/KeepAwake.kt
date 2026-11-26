package org.meshtastic.ambient

/**
 * Keeps the machine and display awake while the app runs, with the OS's own tool: `caffeinate` on macOS and
 * `systemd-inhibit` on Linux. Windows has none reachable from the JVM without native code, so the screen may blank
 * there; failure to start the tool is ignored.
 */
internal object KeepAwake {
    private var process: Process? = null

    fun start() {
        val pid = ProcessHandle.current().pid().toString()
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            "mac" in os -> listOf("caffeinate", "-d", "-i", "-w", pid)
            "linux" in os -> listOf(
                "systemd-inhibit", "--what=idle:sleep", "--who=Meshtastic Ambient", "--why=Ambient display", "--mode=block",
                "tail", "--pid=$pid", "-f", "/dev/null",
            )
            else -> return
        }
        process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: Exception) {
            null
        }
    }

    fun stop() {
        process?.destroy()
        process = null
    }
}
