# meshtastic-ambient

A slow ambient piece of music and visuals that plays on its own and is nudged by live Meshtastic traffic: packets are soft ornaments, nodes are voices, mesh activity is the crowd. Compose Multiplatform on the [meshtastic-sdk](https://github.com/meshtastic/meshtastic-sdk), built with the [Kotlin Toolchain](https://kotlin-toolchain.org); it only listens and sends nothing to the mesh.

Design: `docs/specs/`. Plan: `docs/plans/`. Prior art: `docs/prior-art.md`.

## Run

```sh
./kotlin run -m desktopApp                      # desktop
./kotlin run -m desktopApp -- --fullscreen      # also: --demo, --replay, --tcp host[:port]
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
- Saved link: desktop, Android and iOS remember the last demo, TCP or WebSocket link and reconnect after a 5 s countdown you can cancel. A Bluetooth pick and `--replay` are not saved.

## Controls and keys

Click or tap the scene to show the overlay (link, node count, key, mode, section, volume, palette, Drivers); it hides after 15 s. The pointer hides after 3 s idle.

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

- Speed: one turn per 1200 s on a quiet mesh down to 150 s on a busy one, plus a small decaying push per packet.
- Angle: axis tilt from the 3 h pressure trend (reach when no weather reports), wobble from wind; together never more than 20 degrees.
- Direction: rising pressure turns it clockwise, falling counter-clockwise (the activity trend when there is no pressure data); a flip eases through a stop and needs 30 minutes since the last.

The Drivers button in the overlay lists each parameter with its live value, unit and the signal driving it, or `no data`; it scrolls and hides with the overlay. The demo sweeps these inputs in five minutes: traffic rises and falls, a router, tracker and sensor speak, one node runs low on battery, one sits on high ground, one packet arrives by MQTT, and a pressure fall reverses the rotation once per session (the first pass carries three hours of pressure history to make that possible).

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
| jvm (Linux) | yes | 334 shared tests pass | desktop app not launched in this check; sound never heard |
| wasmJs | compiles (`./kotlin build`) | not run in this check | never run in a browser |
| android | compiles | not run | never run on a device |
| iOS | never built | never run | never run |
| Bluetooth, live radio | - | - | not exercised on a live radio from this workspace |

No person has judged the sound or the visuals; only offline renders and their level statistics have been checked.
