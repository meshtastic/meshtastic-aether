package org.meshtastic.ambient.log

import android.util.Log as AndroidLog

actual fun platformLogSinks(): List<LogSink> = listOf(
    LogSink { level, line ->
        when (level) {
            Level.Debug -> AndroidLog.d("MeshtasticAmbient", line)
            Level.Info -> AndroidLog.i("MeshtasticAmbient", line)
            Level.Warn -> AndroidLog.w("MeshtasticAmbient", line)
            Level.Error -> AndroidLog.e("MeshtasticAmbient", line)
        }
    },
)
