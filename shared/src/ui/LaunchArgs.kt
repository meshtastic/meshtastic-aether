package org.meshtastic.aether.ui

import org.meshtastic.aether.theme.PaletteChoice

/** What the command line asked for: a link to open, an argument that could not be read, fullscreen, a look and whether to ask. */
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
 * `--demo` starts the demo, `--tcp host[:port]` connects at launch (port defaults to the phone API's 4403),
 * `--replay` loops the bundled capture, `--fullscreen` opens fullscreen, `--look standard|projector|oled` picks the
 * look, `--ask` ignores the saved link and any `--demo`, `--replay` or `--tcp` and shows the connect screen. A bad `--tcp` or `--look` value is an error to show, not a choice.
 */
fun parseLaunchArgs(args: Array<String>): LaunchArgs {
    val fullscreen = "--fullscreen" in args
    val ask = "--ask" in args
    val lookAt = args.indexOf("--look")
    var look: PaletteChoice? = null
    if (lookAt >= 0) {
        val value = args.getOrNull(lookAt + 1).orEmpty()
        look = parseLook(value)
        if (look == null) return LaunchArgs(null, "Cannot use --look \"$value\": expected standard, projector or oled.", fullscreen, ask = ask)
    }
    val tcpAt = args.indexOf("--tcp")
    if (tcpAt >= 0) {
        val value = args.getOrNull(tcpAt + 1).orEmpty()
        val tcp = LinkChoice.parseTcp(value)
        return if (tcp == null) {
            LaunchArgs(null, "Cannot use --tcp \"$value\": expected host or host:port with a port from 1 to 65535.", fullscreen, look, ask)
        } else {
            LaunchArgs(tcp, null, fullscreen, look, ask)
        }
    }
    val link = when {
        "--demo" in args -> LinkChoice.Demo
        "--replay" in args -> LinkChoice.Replay
        else -> null
    }
    return LaunchArgs(link, null, fullscreen, look, ask)
}

/** The saved link the countdown reconnects to: none with `--ask`, a launch link, a launch error or where a tap comes first. */
internal fun autoConnectLink(saved: LinkChoice?, ask: Boolean, autoConnects: Boolean, hasLaunchLink: Boolean, hasLaunchError: Boolean): LinkChoice? =
    if (ask || !autoConnects || hasLaunchLink || hasLaunchError) null else saved
