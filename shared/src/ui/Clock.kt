package org.meshtastic.ambient.ui

import org.meshtastic.ambient.localOffsetMinutes
import org.meshtastic.ambient.music.LocalMoment
import kotlin.time.Clock

/** Wall-clock epoch milliseconds. */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private const val MINUTE_MS = 60_000L
private const val DAY_MINUTES = 24 * 60L

/** The wall clock the composer needs: ISO weekday (1 = Monday) and minute of the local day. */
internal fun localMoment(epochMillis: Long, offsetMinutes: Int = localOffsetMinutes(epochMillis)): LocalMoment {
    val localMinutes = epochMillis.floorDiv(MINUTE_MS) + offsetMinutes
    val days = localMinutes.floorDiv(DAY_MINUTES)
    val minuteOfDay = (localMinutes - days * DAY_MINUTES).toInt()
    // 1970-01-01 was a Thursday (ISO 4).
    val dayOfWeek = ((days + 3).mod(7L) + 1).toInt()
    return LocalMoment(dayOfWeek, minuteOfDay, epochMillis)
}
