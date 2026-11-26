package org.meshtastic.ambient.ui

import org.meshtastic.ambient.mesh.LinkState

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
internal fun controlsHint(hasKeyboard: Boolean): String = if (hasKeyboard) "Click or press H for controls" else "Tap for controls"
