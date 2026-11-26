package org.meshtastic.ambient.log

actual fun platformLogSinks(): List<LogSink> = listOf(LogSink { _, line -> println(line) })
