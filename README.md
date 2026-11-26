# Meshtastic Aether

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org)

*The mesh, on the ether.* A slow piece of music and visuals that plays on its own and is nudged by live Meshtastic® traffic: packets are soft ornaments, nodes are voices, and mesh activity is the crowd. It targets desktop, Android, iOS and the web, and it only listens: nothing is sent to the mesh.

Meshtastic® is a registered trademark of Meshtastic LLC. Meshtastic Aether is a Meshtastic project.

## What this is

A Compose Multiplatform app on the [meshtastic-sdk](https://github.com/meshtastic/meshtastic-sdk), built with the [Kotlin Toolchain](https://kotlin-toolchain.org). It connects to a node over TCP, Bluetooth or WebSocket, or plays a scripted demo mesh, and turns what it hears into a generative score and a slowly rotating cloud of nodes. The piece sounds complete with no traffic at all; the mesh perturbs it.

The score and the instruments are synthesized in pure Kotlin on every target, with a per-platform audio output. The app has no dashboard, charts or node lists on the scene.

## Run

```sh
./kotlin run -m desktopApp                      # desktop
./kotlin run -m desktopApp -- --fullscreen      # also: --demo, --replay, --tcp host[:port], --look standard|projector|oled
./kotlin run -m webApp                          # browser; tap to start audio
./kotlin run -m androidApp                      # device or emulator
./kotlin run -m iosApp                          # Mac only
./kotlin test -m shared -p jvm                  # shared tests (wasmJs: -p wasmJs)
```

The `./kotlin` wrapper downloads the Kotlin Toolchain on first use. [`CONTRIBUTING.md`](CONTRIBUTING.md) lists the build and test commands.

## Connect

- Demo: a scripted five-minute mesh, no node needed (`--demo`).
- TCP: host and port on the connect screen, or `--tcp host[:port]` on desktop (the port defaults to 4403).
- Bluetooth: the connect screen scans and lists nodes; pick one. Not on web.
- WebSocket: under Advanced, a `ws://` or `wss://` address.
- Saved link: desktop, Android and iOS remember the last demo, TCP or WebSocket link and reconnect after a 5 s countdown that can be canceled. A Bluetooth pick and `--replay` are not saved and leave the saved link as it was.
- An unreachable host shows `Can't reach host:port - retrying` in the status line, and the raw error goes to the log.

## Controls

Click or tap the scene to show the overlay (link, node count, key, mode, section, volume, palette, Drivers). It hides after 15 s without input (60 s while Drivers is open) and scrolls when it is taller than the window. The pointer hides after 3 s idle.

Volume and mute are remembered, and each session ramps the master from silence over 3 s. `--look` picks the palette for that launch (Standard, Projector or OLED night). With `--fullscreen` and no saved choice the look is Projector, otherwise Standard.

Status wording: Connecting, Listening (warming up), Connected, Demo mesh, Replaying a capture, Link lost.

| Key | Action |
| --- | --- |
| H | overlay |
| F | fullscreen (desktop) |
| M | mute |
| Up, Down | volume |
| D | disconnect |
| Esc | close overlay, leave fullscreen |

## Mesh-driven parameters

The mesh turns parameters in three layers, and every parameter holds its neutral value until the mesh says otherwise.

- Per event: each packet's own facts shape its note and ripple. The node's role and hardware pick the instrument, and hops, SNR, RSSI, MQTT, position and altitude, battery, payload size and text length (never the text) shape how it sounds.
- Landscape: slow signals from the whole mesh (activity, surge, silence, reach, link quality, senders, chat, arrivals, weather), each scaled against its own rolling 24 h range, so a quiet mesh still reads against its own history. A signal with no data keeps its neutral value. They turn the rotation, phrase speed, mode lean and the pull toward pentatonic.
- Rotation: the speed, angle and direction of the cloud, integrated so nothing jumps.

The clock skeleton stays under the mesh: weekday picks the key, and time of day picks the mode and register, with the hour chime, dawn and dusk, the night dim and the long form.

The palette is about 15 instruments, 8 scales (never Locrian) and 4 tempo bands. The Drivers button in the overlay lists each parameter with its live value, unit and the signal driving it, or a plain note such as `holding 8.0 deg - no wind reports` when its signal has no data. The demo sweeps these inputs in five minutes, including a pressure fall that reverses the rotation once per session.

[`docs/mesh-driven-parameters.md`](docs/mesh-driven-parameters.md) has the instruments, scales, signals, mappings and rotation rules.

## Branding

The icon is an own glyph in the brand palette: a luminous green orb with a tilted orbit ring and bright nodes. It is not the Meshtastic M mark, which is never altered or recolored. The sources are `branding/icon.svg`, `branding/icon-small.svg` and `branding/icon-foreground.svg`, and `scripts/icons.sh` regenerates the iOS, desktop and web raster assets from them. The Android icon is hand-ported VectorDrawable XML that follows the foreground source.

The app is dark-only by design: it is a calm projector and night piece. The design standards ask for light and dark themes and for dynamic color on Android 12+, and both are waived here. The name is written as `Meshtastic Aether`.

## Logs

Desktop writes `aether.log` (and stdout) to `$XDG_STATE_HOME/meshtastic-aether/` or `~/.local/state/meshtastic-aether/` on Linux and `~/Library/Logs/MeshtasticAether/` on macOS, and keeps one older generation as `aether.log.1`. Android logs to logcat under `MeshtasticAether`, and web logs to the browser console.

The log records the session link, link state changes, Bluetooth scan results, failures, and key and mode changes. A `coverage` line follows ten minutes after the link comes up and then hourly: the distinct nodes reporting each telemetry field, the role and hardware mix, and whether the connected node reports LocalStats. One line a minute records packets heard by kind, notes scheduled, link, key, mode, section and volume.

## Tools

```sh
./kotlin run -m tools -- sparse 1 /tmp/sparse.wav   # silent | sparse | busy, hours, output [seed]
./kotlin run -m tools -- analyze /tmp/sparse.wav    # per-window peak, RMS, clipping, centroid
./kotlin run -m blescan -- 15                       # scan for nodes for 15 s, print what is found
```

The render tool runs the simulated mesh through the composer and synth offline. Real captures stay local (`captures/` is ignored).

## Troubleshooting

- Headless over ssh: the desktop app needs a display, so use the render tool to produce a WAV file instead.
- Bluetooth permission: Android asks for Bluetooth scan and connect permission on the first scan, and without it the scan does not start and the log says so. macOS and Linux need Bluetooth enabled and permitted for the terminal or app.
- Silent on web: browsers block audio until a gesture, so tap the start screen.
- No sound on desktop: check M (mute) and the volume in the overlay.

## Verified on

| Target | Built | Tests | Run |
| --- | --- | --- | --- |
| jvm (Linux) | yes | 602 shared tests pass (524 common, 78 jvm only) | offline renders only on Linux |
| jvm (macOS, M4) | yes | - | 6 h live run against a real mesh (about 210 nodes): 59.9 fps, p95 18 ms, 340 MB, about 27% of one core, LocalStats delivered, no warnings |
| wasmJs | compiles (`./kotlin build`) | run with `-p wasmJs`; check for `ERROR` lines | never run in a browser |
| android | compiles | not run | never run on a device |
| iOS | shared and its tests compile for the simulator | not run | never run |
| Bluetooth | - | - | not exercised on a live node |

The live run was watched through the log and frame statistics; no person has judged the sound or the visuals from it. The real mesh sent no pressure or wind reports (6 nodes with temperature, 20 with battery), so those paths are proven only by tests and the demo.

## Documentation

- [`docs/design.md`](docs/design.md): goals, constraints, how the app works and what is built.
- [`docs/mesh-driven-parameters.md`](docs/mesh-driven-parameters.md): how the mesh turns the parameters.
- [`docs/prior-art.md`](docs/prior-art.md): the survey of prior work and licenses behind the design.
- [`AGENTS.md`](AGENTS.md): architecture and rules for coding agents.
- [`CONTRIBUTING.md`](CONTRIBUTING.md): build, test and submit a change.

## Credits

The app is inspired by *Sonorous Reception* (190 South LaSalle, Chicago, 2022), a lobby sound sculpture by iart with sound design by Idee und Klang, whose generative score follows the hour, the weekday and the weather. [`NOTICE`](NOTICE) lists the ported code and its licenses.

## Contributing

Sign every commit with the Developer Certificate of Origin (DCO):

```shell
git commit -s -m "Your message"
```

[`CONTRIBUTING.md`](CONTRIBUTING.md) has the full flow.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE).

## Related Meshtastic projects

- [meshtastic.org](https://meshtastic.org): the project site and documentation.
- [`meshtastic/meshtastic-sdk`](https://github.com/meshtastic/meshtastic-sdk): the Kotlin Multiplatform SDK this app links to a node with.
- [`meshtastic/Meshtastic-Android`](https://github.com/meshtastic/Meshtastic-Android) and [`meshtastic/Meshtastic-Apple`](https://github.com/meshtastic/Meshtastic-Apple): the Android and Apple clients.
- [Design standards](https://github.com/meshtastic/design/tree/master/standards): the standards the clients implement.
