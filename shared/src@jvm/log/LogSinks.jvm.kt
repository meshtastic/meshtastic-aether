package org.meshtastic.aether.log

import java.io.File

actual fun platformLogSinks(): List<LogSink> = listOf(
    LogSink { _, line -> println(line) },
    FileLogSink(defaultLogFile()),
)

/** `~/Library/Logs/MeshtasticAether` on macOS, `$XDG_STATE_HOME` or `~/.local/state` elsewhere. */
private fun defaultLogFile(): File {
    val home = System.getProperty("user.home")
    val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
    val dir = if (mac) {
        File(home, "Library/Logs/MeshtasticAether")
    } else {
        File(System.getenv("XDG_STATE_HOME") ?: "$home/.local/state", "meshtastic-aether")
    }
    return File(dir, "aether.log")
}

private const val DEFAULT_MAX_LOG_BYTES = 5L * 1024 * 1024

/** Appends lines to [file]; a file over [maxBytes] at startup is moved to `<name>.1` (one generation kept). */
class FileLogSink(private val file: File, private val maxBytes: Long = DEFAULT_MAX_LOG_BYTES) : LogSink {
    private val lock = Any()
    private var failed = false

    init {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() > maxBytes) file.renameTo(File(file.path + ".1"))
        }.onFailure { failed = true }
    }

    override fun write(level: Level, line: String) {
        if (failed) return
        synchronized(lock) {
            runCatching { file.appendText(line + "\n") }.onFailure { failed = true }
        }
    }
}
