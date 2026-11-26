package org.meshtastic.aether.ui

import org.meshtastic.aether.mesh.LinkState

/** The one name for each link state, shared by the status line and the overlay. */
internal fun linkStatusText(s: LinkState): String = when (s) {
    LinkState.Connecting -> "Connecting"
    LinkState.WarmingUp -> "Listening"
    LinkState.Connected -> "Connected"
    LinkState.Simulated -> "Demo mesh"
    LinkState.Replaying -> "Replaying a capture"
    LinkState.Lost -> "Link lost"
}

/** The top-of-screen line: the open error when there is one, else the link state while it is not settled; null hides it. */
internal fun statusLineText(link: LinkState, error: String?): String? = when {
    error != null -> error
    link == LinkState.Connecting || link == LinkState.WarmingUp -> linkStatusText(link)
    link == LinkState.Lost -> "${linkStatusText(link)} - retrying"
    else -> null
}

/** What the engine shows while a link keeps failing to open; the raw exception goes to the log only. */
internal fun unreachableText(target: String?): String = if (target == null) "Can't open the link - retrying" else "Can't reach $target - retrying"

/** Why a session did not start, for the connect screen: our own checks say so, anything else gets a plain line. */
internal fun startFailureText(e: Throwable): String =
    if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: GENERIC_START_FAILURE else GENERIC_START_FAILURE

private const val GENERIC_START_FAILURE = "Could not start this link"

/** The first-seconds hint: a pointer, with the key where a keyboard is the usual input, else a tap. */
internal fun controlsHint(hasKeyboard: Boolean, isTelevision: Boolean = false): String = when {
    isTelevision -> "Press Select for controls"
    hasKeyboard -> "Click or press H for controls"
    else -> "Tap for controls"
}

/** How long the status line stays after its text changes, and again at the start of each period. */
internal const val STATUS_SHOW_MILLIS = 10_000L

/** The status line returns for [STATUS_SHOW_MILLIS] each period, counted from the last change. */
internal const val STATUS_PERIOD_MILLIS = 60_000L

private const val STATUS_SHIFT_POSITIONS = 4

/** Whether the status line shows [sinceChangeMillis] after its text last changed: the first 10 s, then 10 s of each minute. */
internal fun statusLineVisible(sinceChangeMillis: Long): Boolean =
    sinceChangeMillis >= 0 && sinceChangeMillis % STATUS_PERIOD_MILLIS < STATUS_SHOW_MILLIS

/** Which of four vertical positions the line takes in the period containing [sinceChangeMillis], so it never repeats pixels back to back. */
internal fun statusLineShiftSteps(sinceChangeMillis: Long): Int =
    ((sinceChangeMillis.coerceAtLeast(0) / STATUS_PERIOD_MILLIS) % STATUS_SHIFT_POSITIONS).toInt()
