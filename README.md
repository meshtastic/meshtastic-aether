# meshtastic-ambient

A slow ambient piece of music and visuals that plays on its own and is nudged by live Meshtastic traffic: packets are soft ornaments, nodes are voices, mesh activity is the crowd. Compose Multiplatform on the [meshtastic-sdk](https://github.com/meshtastic/meshtastic-sdk), built with the [Kotlin Toolchain](https://kotlin-toolchain.org); it only listens and sends nothing to the mesh.

Design: `docs/specs/`. Plan: `docs/plans/`. Prior art: `docs/prior-art.md`.

## Run

```sh
./kotlin run -m desktopApp                      # desktop
./kotlin run -m desktopApp -- --fullscreen      # also: --demo, --replay, --tcp host[:port], --look standard|projector|oled
./kotlin run -m webApp                          # browser; tap to start audio
./kotlin run -m androidApp                      # device or emulator
./kotlin run -m iosApp                          # Mac only
./kotlin test -m shared -p jvm                  # shared tests (wasmJs: -p wasmJs)
```

## Connect

- Demo: a scripted five-minute mesh, no radio needed (`--demo`).
- TCP: host and port on the connect screen, or `--tcp host[:port]` on desktop (port defaults to 4403).
- Bluetooth: the connect screen scans and lists radios; pick one. Not on web.
- WebSocket: under Advanced, a `ws://` or `wss://` address.
- Saved link: desktop, Android and iOS remember the last demo, TCP or WebSocket link and reconnect after a 5 s countdown you can cancel. A Bluetooth pick and `--replay` are not saved and leave the saved link as it was.
- An unreachable host shows `Can't reach host:port - retrying` in the status line; the raw error goes to the log.

## Controls and keys

Click or tap the scene to show the overlay (link, node count, key, mode, section, volume, palette, Drivers); it hides after 15 s without input (60 s while Drivers is open), and scrolls when it is taller than the window. The pointer hides after 3 s idle.

Volume and mute are remembered; each session ramps the master from silence over 3 s. `--look` picks the palette for that launch (Standard, Projector or OLED night); with `--fullscreen` and no saved choice the look is Projector, otherwise Standard.

Status wording: Connecting, Listening (warming up), Connected, Demo mesh, Replaying a capture, Link lost.

| Key | Action |
| --- | --- |
| H | overlay |
| F | fullscreen (desktop) |
| M | mute |
| Up, Down | volume |
| D | disconnect |
| Esc | close overlay, leave fullscreen |

## Mesh-driven

The mesh turns parameters in three layers, and every parameter holds today's constant until the mesh says otherwise.

- Per event: each packet's own facts shape its note and ripple: the node's role and hardware pick the instrument, hops, SNR, RSSI, MQTT, position and altitude, battery, payload size and text length (never the text) shape how it sounds.
- Landscape: slow signals from the whole mesh (activity, surge, silence, reach, link quality, senders, chat, arrivals, weather), each scaled against its own rolling 24 h range, so a quiet mesh still reads against its own history. A signal with no data keeps its neutral value. So far they turn the rotation, phrase speed, mode lean and the pull toward pentatonic; the rest wait on what the coverage log shows.
- Rotation: speed, angle and direction of the cloud, integrated so nothing jumps.

The clock skeleton stays: weekday picks the key, time of day picks the mode and register, with the hour chime, dawn and dusk, the night dim and the long form.

Palettes: about 15 instruments (Bell, Marimba, Vibes, Kalimba, Glass chime, Harp, Pluck, Mallet, Gong, Bowed string, Choir, Pad, Drone, Sub, Shimmer, plus the pitchless Breath), picked by role family and hardware model; 8 scales (Ionian, Dorian, Mixolydian, Lydian, Aeolian, Phrygian, major and minor pentatonic, never Locrian); 4 tempo bands (0.5x, 0.75x, 1x, 1.5x phrase speed) from relative activity.

Rotation controls:

- Turn time: one turn per 1200 s on a quiet mesh down to 150 s on a busy one, plus a small decaying push per packet.
- Angle: axis tilt from the 3 h pressure trend (reach when no weather reports); wobble 1 to 6 degrees from wind, or from relative senders when there is no wind, neutral 3; together never more than 20 degrees.
- Direction: the first signal with data wins: pressure trend (rising is clockwise), temperature trend (warming is clockwise), battery trend (only nodes whose level moved), activity trend. A flip eases through a stop and needs 30 minutes since the last.

The Drivers button in the overlay lists each parameter with its live value, unit and the signal driving it, or a plain note such as `holding 8.0 deg - no wind reports` when its signal has no data; direction shows the way the cloud is turning, with the target when they differ. The demo sweeps these inputs in five minutes: traffic rises and falls, a router, tracker and sensor speak, one node runs low on battery, one sits on high ground, one packet arrives by MQTT, and a pressure fall reverses the rotation once per session (the first pass carries three hours of pressure history to make that possible).

## Log

Desktop writes `ambient.log` (and stdout) to `$XDG_STATE_HOME/meshtastic-ambient/` or `~/.local/state/meshtastic-ambient/` on Linux, `~/Library/Logs/MeshtasticAmbient/` on macOS; one older generation is kept as `ambient.log.1`. Android logs to logcat under `MeshtasticAmbient`, web to the browser console.

It records the session link, link state changes, Bluetooth scan results, failures, key and mode changes, a `coverage` line ten minutes after the link comes up and then hourly (distinct nodes reporting each telemetry field, the role and hardware mix, whether our node reports LocalStats), and one line a minute with packets heard by kind, notes scheduled, link, key, mode, section and volume.

## Tools

```sh
./kotlin run -m tools -- sparse 1 /tmp/sparse.wav   # silent | sparse | busy, hours, output [seed]
./kotlin run -m tools -- analyze /tmp/sparse.wav    # per-window peak, RMS, clipping, centroid
./kotlin run -m blescan -- 15                       # scan for radios for 15 s, print what is found
```

The render tool runs the simulated mesh through the composer and synth offline. Real captures stay local (`captures/` is ignored).

## Troubleshooting

- Headless over ssh: the desktop app needs a display; use the render tool to produce a WAV instead.
- Bluetooth permission: Android asks for Bluetooth scan and connect on the first scan; without it the scan does not start and the log says so. macOS and Linux need Bluetooth enabled and permitted for the terminal or app.
- Silent on web: browsers block audio until a gesture; tap the start screen.
- No sound on desktop: check M (mute) and the volume in the overlay.

## Verified on

| Target | Built | Tests | Run |
| --- | --- | --- | --- |
| jvm (Linux) | yes | 599 shared tests pass (521 common, 78 jvm only) | offline renders only on Linux |
| jvm (macOS, M4) | yes | - | 6 h live run against a real mesh (about 210 nodes): 59.9 fps, p95 18 ms, 340 MB, about 27% of one core, LocalStats delivered, no warnings |
| wasmJs | compiles (`./kotlin build`) | run with `-p wasmJs`; check for `ERROR` lines | never run in a browser |
| android | compiles | not run | never run on a device |
| iOS | shared and its tests compile for the simulator (`build/ios-result.txt`) | not run | never run |
| Bluetooth | - | - | not exercised on a live radio |

The live run was watched through the log and frame statistics; no person has yet judged the sound or the visuals from it. The real mesh sent no pressure or wind reports (6 nodes with temperature, 20 with battery), so those paths are proven only by tests and the demo.
