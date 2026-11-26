package org.meshtastic.aether.log

actual fun platformLogSinks(): List<LogSink> = listOf(LogSink { _, line -> println(line) })
