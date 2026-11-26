package org.meshtastic.aether.log

import android.util.Log as AndroidLog

actual fun platformLogSinks(): List<LogSink> = listOf(
    LogSink { level, line ->
        when (level) {
            Level.Debug -> AndroidLog.d("MeshtasticAether", line)
            Level.Info -> AndroidLog.i("MeshtasticAether", line)
            Level.Warn -> AndroidLog.w("MeshtasticAether", line)
            Level.Error -> AndroidLog.e("MeshtasticAether", line)
        }
    },
)
