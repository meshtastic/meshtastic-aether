package org.meshtastic.ambient.ui

import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import org.meshtastic.ambient.music.LocalMoment
import kotlin.time.Clock
import kotlin.time.Instant

fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** The wall clock the composer needs: ISO weekday (1 = Monday) and minute of the local day. */
fun localMoment(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): LocalMoment {
    val local = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    return LocalMoment(local.dayOfWeek.isoDayNumber, local.hour * 60 + local.minute, epochMillis)
}
