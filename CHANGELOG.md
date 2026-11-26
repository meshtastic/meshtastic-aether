# Changelog

All notable changes to Meshtastic Aether are documented here. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
- App icons for Android, iOS, desktop and web with branded launch backgrounds, and the in-app glyph and tagline.
