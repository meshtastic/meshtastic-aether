package org.meshtastic.aether.ui

/** The ways the connect screen can start a session; one pane shows at a time. */
internal enum class LinkKind(val label: String) {
    Network("Network"),
    Bluetooth("Bluetooth"),
    Serial("USB"),
    WebSocket("WebSocket"),
    Demo("Demo"),
    ;

    /** Bluetooth and USB pick a radio from a list, so their panes have no Connect button. */
    val picksFromList: Boolean get() = this == Bluetooth || this == Serial
}

/**
 * The kinds this target can offer, real links first and the demo last: WebSocket and the demo everywhere, TCP,
 * Bluetooth and USB serial only where the platform has them.
 */
internal fun availableKinds(supportsTcp: Boolean, bluetooth: Boolean, serial: Boolean = false): List<LinkKind> =
    listOfNotNull(
        LinkKind.Network.takeIf { supportsTcp },
        LinkKind.Bluetooth.takeIf { bluetooth },
        LinkKind.Serial.takeIf { serial },
        LinkKind.WebSocket,
        LinkKind.Demo,
    )

/**
 * The kind the connect screen opens on: the pending link's while its countdown runs, else the one last chosen, else the
 * first real link the platform offers (Network, or WebSocket on the web).
 */
internal fun initialKind(kinds: List<LinkKind>, pending: LinkKind?, lastChosen: LinkKind?): LinkKind =
    pending?.takeIf { it in kinds } ?: lastChosen?.takeIf { it in kinds } ?: kinds.first()

/** The kind whose pane shows a saved [link], or null when no pane edits it (the replay, a scanned radio). */
internal fun LinkChoice.kind(): LinkKind? = when (this) {
    LinkChoice.Demo -> LinkKind.Demo
    is LinkChoice.Tcp -> LinkKind.Network
    is LinkChoice.WebSocket -> LinkKind.WebSocket
    is LinkChoice.Ble -> LinkKind.Bluetooth
    is LinkChoice.Serial -> LinkKind.Serial
    LinkChoice.Replay -> null
}
