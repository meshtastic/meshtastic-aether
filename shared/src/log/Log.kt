package org.meshtastic.aether.log

import kotlin.time.Clock

enum class Level { Debug, Info, Warn, Error }

fun interface LogSink {
    fun write(level: Level, line: String)
}

/** Dependency-free logging: one formatted line per call to every sink the platform installs. */
object Log {
    var minLevel: Level = Level.Info

    /** Replaced in tests; the platform's sinks by default. */
    var sinks: List<LogSink> = platformLogSinks()

    fun d(tag: String, message: String) = log(Level.Debug, tag, message, null)

    fun i(tag: String, message: String) = log(Level.Info, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.Warn, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.Error, tag, message, error)

    private fun log(level: Level, tag: String, message: String, error: Throwable?) {
        if (level < minLevel) return
        val line = format(Clock.System.now().toString(), level, tag, message, error)
        for (sink in sinks) sink.write(level, line)
    }

    internal fun format(timestamp: String, level: Level, tag: String, message: String, error: Throwable?): String {
        val base = "$timestamp ${level.name.uppercase().padEnd(5)} $tag: $message"
        return if (error == null) base else "$base (${error::class.simpleName}: ${error.message})"
    }
}

expect fun platformLogSinks(): List<LogSink>
