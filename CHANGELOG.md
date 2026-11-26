# Changelog

All notable changes to Meshtastic Aether are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- On a TV the first Back press no longer gets spent on a HUD button's focus: one press closes the HUD.
- The dither on the background no longer costs a full-screen offscreen pass on weak GPUs (about 3 fps on a Chromecast).
- Orbs glide to their new places when a node joins or leaves, where they used to jump.

### Added

- Gravity and orbits: each orb circles its place with Kepler-style timing, linked nodes lean together, and each packet kicks its node. A new mesh-driven gravity parameter, from mean link quality, tightens or loosens it all, and it has a row in the Drivers panel.
- Android TV and Google TV: a leanback launcher entry with a banner, Select to open the HUD, Play/Pause to mute, a focused Connect button, a larger safe margin, and `adb` launch extras (`--es tcp host:port`, `--ez demo true`, `--ez replay true`, `--es look oled`, `--ez ask true`).
- Desktop: set `AETHER_RECORD_AUDIO=out.wav` to record exactly what the app plays, for demo videos. A `out.wav.start` file holds the wall-clock start in epoch milliseconds.
- A macOS menu bar with Disconnect, Enter Full Screen, Mute and Minimize that call the same actions as the keys.
- Android Back closes the HUD, then returns to the connect screen.
- The desktop app runs on JetBrains Runtime.
- A slow generative piece of music and visuals that plays on its own and is shaped by live Meshtastic traffic: packets are ornaments, nodes are voices, and mesh activity is the crowd. The app only listens and sends nothing to the mesh.
- Desktop (JVM), Android, iOS and web (Wasm) targets on a shared Kotlin Multiplatform codebase, built with the Kotlin Toolchain.
- Links to a node over TCP, Bluetooth (not on web) and WebSocket, plus a scripted demo mesh and a replay of a bundled capture. Desktop, Android and iOS remember the last demo, TCP or WebSocket link and reconnect after a cancelable countdown.
- Mesh-driven parameters in three layers: per-packet facts (node role and hardware, hops, SNR, RSSI, MQTT, position, battery, payload size) shape each note and ripple; slow self-normalized signals from the whole mesh move the rotation, phrase speed, mode lean and the pull toward pentatonic; the cloud rotates with a mesh-driven speed, tilt and direction.
- A clock skeleton under the mesh: weekday picks the key, time of day picks the mode and register, with an hour chime, dawn and dusk, a night dim and a long form.
- About 15 instruments, 8 scales and 4 tempo bands, synthesized in pure Kotlin with a Clouds-topology reverb.
- An overlay with link state, node count, key, mode, section, volume, palette and a Drivers panel that lists each parameter with its live value and the signal driving it.
- Standard, Projector and OLED night looks, fullscreen, and keep-awake.
- Launch flags `--demo`, `--replay`, `--tcp host[:port]`, `--fullscreen` and `--look standard|projector|oled` on desktop.
- A local log with a session summary every minute and a coverage line that counts which telemetry fields the mesh reports.
- The `tools` module renders the simulated mesh to a WAV file and analyzes it, and the `blescan` module scans for nodes over Bluetooth.
- A Material 3 Expressive connect screen: a connected group of link types (Demo, Network, Bluetooth, WebSocket) with one pane at a time, inline field errors and a Bluetooth scan with a loading indicator.
- A countdown banner on the connect screen with a wavy progress bar, Connect now and Cancel; any edit to a field or change of link type cancels it.
- Connect screen layouts for the compact, medium and expanded window widths, with two panes on wide or short windows.
- A floating-toolbar HUD with mute, volume, look, Drivers, fullscreen and Disconnect, tooltips with a keyboard, and haptic ticks for mute and a confirmed Disconnect.
- Disconnect asks for a second press within 3 seconds, from the toolbar button or the D key.
- Launch flag `--ask` shows the connect screen and ignores the saved link and any `--demo`, `--replay` or `--tcp`.
- A status line that shows for 10 seconds after its text changes and for 10 seconds each minute, stepping down one of four heights so it never sits on the same pixels.
- App icons for Android, iOS, desktop and web with branded launch backgrounds, and the in-app glyph and tagline.
