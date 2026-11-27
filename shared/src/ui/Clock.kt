package org.meshtastic.aether.ui

import org.meshtastic.aether.localOffsetMinutes
import org.meshtastic.aether.music.LocalMoment
import kotlin.time.Clock

/** Wall-clock epoch milliseconds. */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private const val MINUTE_MS = 60_000L
private const val DAY_MINUTES = 24 * 60L
private const val WEEK_DAYS = 7L

/** 1970-01-01 was a Thursday (ISO 4): three days after the Monday that starts its ISO week. */
private const val EPOCH_DAYS_AFTER_MONDAY = 3

/** The wall clock the composer needs: ISO weekday (1 = Monday) and minute of the local day. */
internal fun localMoment(epochMillis: Long, offsetMinutes: Int = localOffsetMinutes(epochMillis)): LocalMoment {
    val localMinutes = epochMillis.floorDiv(MINUTE_MS) + offsetMinutes
    val days = localMinutes.floorDiv(DAY_MINUTES)
    val minuteOfDay = (localMinutes - days * DAY_MINUTES).toInt()
    val dayOfWeek = ((days + EPOCH_DAYS_AFTER_MONDAY).mod(WEEK_DAYS) + 1).toInt()
    return LocalMoment(dayOfWeek, minuteOfDay, epochMillis)
}
