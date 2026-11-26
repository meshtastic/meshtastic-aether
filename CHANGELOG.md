# Changelog

All notable changes to Meshtastic Aether are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A slow generative piece of music and visuals that plays on its own and is shaped by live Meshtastic traffic: packets are ornaments, nodes are voices, and mesh activity is the crowd. The app only listens and sends nothing to the mesh.
- Desktop (JVM, on JetBrains Runtime), Android (phones, tablets, Android TV and Google TV), iOS and web (Wasm) targets on a shared Kotlin Multiplatform codebase, built with the Kotlin Toolchain.
- Links to a node over TCP and Bluetooth (neither on web) and WebSocket, plus a scripted demo mesh and a replay of a bundled capture. Desktop, Android and iOS remember the last demo, TCP or WebSocket link and reconnect after a cancelable countdown.
- Mesh-driven parameters in four layers: per-packet facts (node role and hardware, hops, SNR, RSSI, MQTT, position, battery, payload size) shape each note and ripple; slow self-normalized signals from the whole mesh move the rotation, phrase speed, mode lean, the pull toward pentatonic and gravity; the cloud rotates with a mesh-driven speed, tilt and direction; and a gravity layer circles each orb around its place with Kepler-style timing, leans linked nodes together and kicks a node on each packet.
- The wobble follows the median channel use that nearly every node reports when no node reports wind, which real meshes almost never do.
- A clock skeleton under the mesh: weekday picks the key, time of day picks the mode and register, with an hour chime, dawn and dusk, a night dim and a long form.
- About 15 instruments, 8 scales and 4 tempo bands, synthesized in pure Kotlin with a Clouds-topology reverb.
- A headphone mode that places each dry voice to the side of the head with an interaural delay and head shadow, crossfaded over 0.3 s. Off by default; pads, reverb and limiter are unchanged.
- A Material 3 Expressive connect screen: a connected group of link types (Demo, Network, Bluetooth, WebSocket) with one pane at a time, inline field errors, a Bluetooth scan with a loading indicator, a countdown banner with a wavy progress bar, Connect now and Cancel, and layouts for compact, medium and expanded widths.
- A floating-toolbar HUD with mute, volume, a Look menu (Standard, Projector, OLED night), headphones, Drivers, fullscreen and a two-press Disconnect, plus a status card, tooltips with a keyboard and haptic ticks. The Drivers panel lists each parameter with its live value and the signal driving it.
- Keys: H HUD, F fullscreen, M mute, P headphones, Up and Down volume, D disconnect, Esc close; on a TV remote Select opens the HUD, Play/Pause mutes, Back closes the HUD and then the session, and the volume slider lets Left and Right move along the toolbar. A macOS menu bar mirrors the keys.
- Android TV and Google TV: a leanback launcher entry with a banner, a focused Connect button and a larger safe margin on the connect screen.
- Launch flags `--demo`, `--replay`, `--tcp host[:port]`, `--ask`, `--fullscreen` and `--look standard|projector|oled` on desktop, and the same as intent extras on Android (`--es tcp host:port`, `--ez demo true`, `--ez replay true`, `--es look oled`, `--ez ask true`).
- Fullscreen on desktop and on Android phones and tablets, and keep-awake everywhere.
- A status line that shows for 10 seconds after its text changes and for 10 seconds each minute, stepping down one of four heights so it never sits on the same pixels.
- A local log with a session summary every minute and a coverage line that counts which telemetry fields the mesh reports, including channel use.
- Desktop: `AETHER_RECORD_AUDIO=out.wav` records what the app plays for demo videos, across reconnects; an `out.wav.start` file holds the wall-clock start in epoch milliseconds.
- The `tools` module renders the simulated mesh to a WAV file and analyzes it, and the `blescan` module scans for nodes over Bluetooth.
- App icons for Android, iOS, desktop and web with branded launch backgrounds, an Android TV banner, and the in-app glyph and tagline.
