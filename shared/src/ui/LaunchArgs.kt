package org.meshtastic.aether.ui

import org.meshtastic.aether.theme.PaletteChoice

/**
 * What the command line asked for: a link to open, an argument that could not be read, fullscreen, a look and whether
 * to ask.
 */
class LaunchArgs(
    val link: LinkChoice?,
    val error: String?,
    val fullscreen: Boolean,
    val look: PaletteChoice? = null,
    val ask: Boolean = false,
)

/** `standard`, `projector` or `oled` (also `oled-night`), any case; null for anything else. */
fun parseLook(text: String): PaletteChoice? = when (text.lowercase().filter { it.isLetterOrDigit() }) {
    "standard" -> PaletteChoice.Standard
    "projector" -> PaletteChoice.Projector
    "oled", "olednight" -> PaletteChoice.OledNight
    else -> null
}

/** The look in force: the launch flag, else the saved choice, else Projector for a fullscreen launch, else Standard. */
fun resolveLook(flag: PaletteChoice?, saved: PaletteChoice?, fullscreenLaunch: Boolean): PaletteChoice =
    flag ?: saved ?: if (fullscreenLaunch) PaletteChoice.Projector else PaletteChoice.Standard

/**
 * `--demo` starts the demo, `--serial PORT` opens a USB serial radio, `--tcp host[:port]` connects at launch (port
 * defaults to the phone API's 4403), `--replay` loops the bundled capture, `--fullscreen` opens fullscreen,
 * `--look standard|projector|oled` picks the look, `--ask` ignores the saved link and any `--demo`, `--replay` or
 * `--tcp` and shows the connect screen. A bad `--tcp` or `--look` value is an error to show, not a choice.
 */
fun parseLaunchArgs(args: Array<String>): LaunchArgs {
    val fullscreen = "--fullscreen" in args
    val ask = "--ask" in args
    val lookValue = valueAfter(args, "--look")
    val look = lookValue?.let(::parseLook)
    val launch = if (lookValue != null && look == null) {
        LaunchLink(null, "Cannot use --look \"$lookValue\": expected standard, projector or oled.")
    } else {
        launchLink(args)
    }
    return LaunchArgs(launch.link, launch.error, fullscreen, look, ask)
}

/** The link a launch flag asks for, or the error its bad value is; both null when no flag names a link. */
private class LaunchLink(val link: LinkChoice?, val error: String?)

/** The argument after [flag], empty when the flag ends the line; null when [flag] is absent. */
private fun valueAfter(args: Array<String>, flag: String): String? {
    val at = args.indexOf(flag)
    return if (at >= 0) args.getOrNull(at + 1).orEmpty() else null
}

/** `--serial` wins over `--tcp`, which wins over `--demo` and then `--replay`. */
private fun launchLink(args: Array<String>): LaunchLink {
    val serial = valueAfter(args, "--serial")
    val tcp = valueAfter(args, "--tcp")
    return when {
        serial != null -> serialLink(serial.trim())
        tcp != null -> tcpLink(tcp)
        "--demo" in args -> LaunchLink(LinkChoice.Demo, null)
        "--replay" in args -> LaunchLink(LinkChoice.Replay, null)
        else -> LaunchLink(null, null)
    }
}

private fun serialLink(port: String): LaunchLink = if (port.isEmpty() || port.startsWith("--")) {
    LaunchLink(null, "Cannot use --serial: expected the port, for example /dev/ttyACM0 or COM3.")
} else {
    LaunchLink(LinkChoice.Serial(port, port), null)
}

private fun tcpLink(value: String): LaunchLink {
    val tcp = LinkChoice.parseTcp(value)
    val error = "Cannot use --tcp \"$value\": expected host or host:port with a port from 1 to 65535."
    return if (tcp == null) LaunchLink(null, error) else LaunchLink(tcp, null)
}

/**
 * The saved link the countdown reconnects to: none with `--ask`, a launch link, a launch error or where a tap comes
 * first.
 */
internal fun autoConnectLink(
    saved: LinkChoice?,
    ask: Boolean,
    autoConnects: Boolean,
    hasLaunchLink: Boolean,
    hasLaunchError: Boolean,
): LinkChoice? {
    val launchDecides = hasLaunchLink || hasLaunchError
    val countdownAllowed = autoConnects && !ask
    return if (countdownAllowed && !launchDecides) saved else null
}
