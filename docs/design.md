# Aether design

An ambient music and visuals generator shaped by live Meshtastic mesh traffic, as a Compose Multiplatform app on the meshtastic-sdk.

## Intent

The app is inspired by iart's *Sonorous Reception* (190 S LaSalle, Chicago, 2022): a lobby sound sculpture where light and motion sensors locate visitors and 91 speakers play a generative score whose tempo follows the hour, scale follows the weekday, and texture follows the weather (15 instruments, 4 tempos, 7 keys and scales). Its sound designers, Idee und Klang, describe the principle: each local event lands on a fixed musical grid and hands off into an ambient bed, while crowd-level activity drives tension, density and intensity.

Here the mesh is the lobby. The app plays a slow autonomous piece that sounds good with no traffic at all, and the mesh perturbs it: packets are ornaments, nodes are voices, mesh activity is the crowd, and environment telemetry is the weather. It runs unattended for hours on a projector or as an installation without becoming a sonified dashboard.

## Goals

- One shared Kotlin codebase on all targets: JVM desktop, Android (including Android TV and Google TV), iOS and web (wasmJs). tvOS is not possible: Compose Multiplatform and meshtastic-sdk publish no tvOS variants.
- Built on the meshtastic-sdk `RadioClient`: `packets`, `fromRadio`, `nodes` and `telemetry.observe(node)`.
- Generative audio synthesized in shared Kotlin, with per-platform output only.
- A full-screen ambient visual driven by the same events and clock as the audio.
- Simulated, demo and replayed meshes on every target, for development, demos and listening tests without a node.
- Note events go through a seam so a MIDI or OSC output can be added without touching the composer.

## Non-goals

- MIDI or OSC output. The seam exists; an implementation does not.
- Sending anything to the mesh. The app is listen-only.
- Multi-speaker spatial installs. Output is stereo, and pan stands in for the nearest speakers.
- Stored history, accounts or settings sync. The app keeps only the saved link, the volume and mute, the headphone mode and the palette choice, on the device.
- App store publishing.
- A web kiosk mode.

## Anti-goals

- Dashboard sound: one instrument per packet type, a note for every packet regardless of context, pitches that ignore the harmony.
- Cacophony: a burst never smears. Per-packet notes are capped, expire, and give way to texture when the mesh is busy.
- A wall of drone: the bed breathes, thins and sometimes nearly stops. Silence is part of the piece.
- Dashboard look: no charts, node lists or chrome on the scene.
- Anything leaving the device: the only network traffic is the link to the node and, on Android and desktop builds from CI, the update check against this repository's GitHub releases.
- Burn-in: nothing static on screen for long.

## Constraints

- Toolchain: Kotlin 2.4.21 (the SDK's pin, so its klibs link), Compose Multiplatform 1.12.2, and the SDK at `0.2.1-SNAPSHOT` from the Sonatype snapshot repository until 0.3.0, because 0.2.0 has no JS or Wasm klibs, no `transport-websocket` and no `fromRadio`. `core`, `testing`, `transport-websocket` and `transport-ble` publish `js` and `wasmJs` klibs. SDK modules may need `-Xskip-prerelease-check`, as the SDK's `samples/parity-app` does.
- Build: the JetBrains Kotlin Toolchain (alpha), not Gradle. The `./kotlin` and `kotlin.bat` wrappers pin the toolchain version (0.14.1). `project.yaml` lists the modules, `common.module-template.yaml` holds the Kotlin version, the Compose version and the snapshot repository for every module, and versions live in a root `libs.versions.toml`.
  - Compose Multiplatform builds for jvm, android and wasmJs and links the SDK on each. The shared module and its tests also compile and run on the iOS simulator when built on a Mac.
  - A Linux `build` skips Apple targets silently, so iOS builds on a Mac only. Tests cannot read files on the iOS simulator, so fixtures are Kotlin constants.
  - Commands: `./kotlin build`, `./kotlin test -m <module> -p <platform>`, `./kotlin check`, `./kotlin run -m <module>`.
- Browser links: the SDK's WebSocket transport needs a node that serves the phone API over WebSocket (firmware PRs #12412-12414). Bluetooth goes through Web Bluetooth, which only Chrome and Edge have, only on a secure page, and which offers a device chooser opened from a gesture instead of a scan.
- Browser audio needs a user gesture.
- Dependencies: only the SDK, kotlinx-coroutines and JetBrains Compose. Anything else is written in the repository on platform APIs.
- The SDK's Wasm engine is tested on Node.js only, never in a browser.
- License GPL-3.0-or-later. Ported code is GPL-3.0-or-later compatible (MIT, BSD, Apache-2.0 or public domain), and its notices live in `NOTICE`. Shadertoy code (non-commercial license) and Faust's `vital_rev` and `kb_rom_rev1` (GPL-3.0-only) are excluded.

## Decisions

1. Synth in shared Kotlin, with a thin per-platform PCM sink. It is self-contained on every target and testable offline; the sound is limited to what the repository writes.
2. MIDI or OSC out to an external synth was rejected. It gives the best sound but is effectively desktop-only and needs external setup per install.
3. Synth in shared Kotlin with a note-event seam, so MIDI or OSC can be added later. This is the chosen design.
4. Every audio sink is an `expect`/`actual` on platform APIs with no third-party audio library. kc-audio-bridge (Apache-2.0) is reference reading only.
5. The Kotlin Toolchain is the build tool, with Gradle as the fallback.
6. The piece is autonomous with long-form harmonic movement, perturbed by the mesh. A note per packet over a static drone is one drone for hours at a few packets a minute.

## How it works

```
MeshSource ──MeshEvent──▶ Composer ──NoteEvent/ControlEvent (sample time)──▶ Synth ──PCM──▶ AudioSink
     │                        │                                                              │
     └──────MeshEvent─────────┴──SceneState──▶ Visual ◀────────── playback position ──────────┘
```

### Clock

One clock: the audio sample counter (a `Long`). The composer schedules every event at a sample frame inside a lookahead window (250-500 ms on desktop, Android and iOS; 1 s or more on web), filled by a timer, as in Chris Wilson's "A tale of two clocks". The synth renders ahead of playback, so GC pauses on Kotlin/Native and Wasm don't reach the speaker. The visual fires a packet's ripple when the sink's playback position reaches that note's frame, so sight and sound agree. Phases and times use `Double` or sample counts, never `Float`, so nothing drifts over days.

### MeshSource

`MeshSource` emits app-level `MeshEvent`s, so the composer never sees protobufs:

- `PacketHeard(from, kind, snr, rssi, hopsTaken?, viaMqtt, ...)` where `hopsTaken` is null when `hop_start == 0` (older firmware), meaning unknown, not direct. It also carries the destination, the relay byte, whether it was addressed to the connected node, the payload size, the text length (never the text) and the channel.
- `NodeSeen(node, name, position?, firstThisSession, role, hardware)` and `NodeGone(node)`.
- `Environment(node, temperature?, humidity?, pressure?, wind?, lux?, wind direction, gust, lull)`.
- `Health(node, battery, voltage, uptime)` from each node's device metrics, and `Vitals(channelUtilization?, airUtilTx?)` and `Stats` (LocalStats) from the connected node.
- `Self`, `Neighbors` and `Route` for the connected node, neighbor reports and traceroutes.
- `Link(connecting | warmingUp | connected | simulated | replaying | lost)`.

What a client hears: firmware forwards to the phone only packets that are broadcast or addressed to the connected node (`RoutingModule.cpp`), and the SDK's `packets` drops any packet without a decoded payload (`RadioClient.kt` KDoc). Unicasts between other nodes (most acks, routing, traceroutes) never arrive.

Implementations:

- `SdkMeshSource(RadioClient)`:
  - Decoded packets come from `packets`. Encrypted broadcasts come from a second mapper on `fromRadio` that takes only `FromRadio.packet` frames with no `decoded` payload, so the two never overlap. `fromRadio` is lossy past 256 buffered envelopes, which is fine for music.
  - Nodes come from `nodes`, and environment telemetry from `telemetry.observe(node)` per node as nodes appear.
  - Warm-up: `nodes` re-sends a full `Snapshot` after every reconnect and retained handshake packets flush at `Ready`, so for a short window after each `Connected` the source updates state silently and emits no `PacketHeard`.
  - Storage is `InMemoryStorageProvider`, and auto-reconnect is on.
- `ReplayMeshSource(capture)` replays a capture of timestamped `FromRadio` frames at 1x or faster; the app ships a bundled synthetic capture. Real captures hold real names and positions, so they stay local and are never committed, and committed fixtures are synthetic or scrubbed.
- `SimulatedMeshSource(seed)` runs 5-40 fake nodes, each broadcasting on its own period per portnum, roughly like firmware defaults (position, telemetry and nodeinfo on intervals of minutes to hours, with jitter), plus occasional text, a share of encrypted broadcasts and rare routing. Each node is heard at its own steady RSSI, SNR and route, with a little variation per packet, and environment sensors sit on about 4 percent of nodes, as in real captures. Traffic is periodic, not Poisson: real traffic is a set of slow loops of unrelated periods, which is Eno's tape-loop method for free, and the music is tuned against that.
- `DemoMeshSource` is a scripted five-minute mesh for the connect screen: nodes arrive one by one, traffic rises and falls, a router, a tracker and a sensor speak, and a pressure fall reverses the rotation. The mesh-driven parameters document describes the sweep.

Links per target, picked on the connect screen:

| Target | Links |
| --- | --- |
| Desktop | TCP, Bluetooth, USB serial, WebSocket |
| Android | Bluetooth, TCP, USB serial, WebSocket |
| iOS | Bluetooth, TCP, WebSocket |
| Web | WebSocket, Bluetooth (Chrome and Edge) |
| All | Demo, replay |

Desktop, Android (including TV) and iOS are the unattended install targets: the last demo, TCP, USB or WebSocket link is remembered and reconnects after a cancelable countdown, and audio starts on launch. Web is a demo and development target: it opens on a "tap to start" screen.

### Composer

The composer is pure and deterministic given (events, clock, seed). This is where the character lives.

The autonomous piece sounds complete with zero packets:

- Harmonic field: a slow progression in the day's key, one chord every 2-8 minutes, voice-led (common tones held, others move by step). Chords are built from the current mode.
- Long form: sections of 20-60 minutes on a roughly 110-minute energy arc. Layers enter and leave: sub drone, pad, slow loop voices, a high shimmer. Some passages thin to near silence.
- Loop voices: each chord tone repeats on its own period of 17-60 s, with periods unrelated to each other (Music for Airports, Generative.fm's Eno Machine), so the texture never exactly repeats.
- Just intonation for the bed and loop voices relative to the chord root.

Time and weather change the rules, following *Sonorous Reception* and Eno's Reflection:

- Weekday picks the key (7 roots).
- Time of day picks mode brightness, density, register, decay length and loop periods: Lydian or Ionian in the morning, Mixolydian or Dorian through the afternoon, Aeolian at night and Phrygian in the darkest hours, back toward home by evening; sparser, lower and longer-decaying late at night. No Locrian.
- Weather sets texture, scaled to the mesh's own rolling range (per-quantity min and max over about a week, absolute defaults until there is data), so a cold season isn't one dull setting for months: temperature sets brightness (filter, FM index), humidity sets reverb size and wetness, falling pressure sets slow detuned movement, and wind or lux sets noise and shimmer.

The mesh perturbs the piece:

- Crowd sets tension. `MeshMood` smooths the packet rate, active node count, mean SNR and channel utilization against their own rolling range, and they set density, brightness and tension of the autonomous piece. A busy mesh thickens the bed, and packet reverb tails feed the pad.
- Packets are ornaments. A node's role picks the instrument family and its hardware model picks the voice within it; the packet kind is articulation (attack, length, brightness), not the instrument. [`mesh-driven-parameters.md`](mesh-driven-parameters.md) has the per-event mappings.
  - Pitch: the node number hashes to a chord-tone role (root, third, fifth, seventh, ninth) and a fixed register band, so a node keeps its place in the harmony as chords move and its notes form phrases, not leaps.
  - Timing: soft quantization to a slow grid, keeping each node's broadcast rhythm.
  - Velocity comes from SNR (range-limited), and pan from the sender's bearing relative to the connected node (center, slightly wide, if unknown).
  - Distance comes from hops taken: direct is dry and close, each hop adds reverb send, lowpass and an echo repeat, unknown is middle distance, and via MQTT is one step further.
  - Encrypted broadcasts are a pitchless filtered breath, so other channels read as weather, not melody.
  - Variation operators from Generative.fm: small timing jitter, occasional octave shift, occasional delayed echo of a note, and a per-pitch repeat limit.
- Density control: a fixed number of ornament slots (as in Listen to Wikipedia's 15 slots), each held for a short time. A note that can't get a slot within a short TTL is dropped, never queued indefinitely. Above an activity threshold, per-packet notes thin to a sample and the crowd parameters carry the rest.
- Human events bend the piece. A text message triggers a phrase and, after a minimum gap, a chord change. A node seen for the first time this session gets a soft swell.
- Quiet mesh: recently heard nodes return as soft memory notes on loop periods, and the autonomous piece carries the rest.

Output is `NoteEvent(voice, pitch, velocity, pan, sends, atFrame)` and `ControlEvent`s (chord, mode, texture, layer levels). `NoteSink` is the seam, and the synth is its one implementation.

### Synth

The synth is pure Kotlin and renders stereo `FloatArray` blocks at the sink's sample rate (not assumed 48 kHz), with no allocation in the render loop and a tiny noise floor injected into feedback paths against denormal CPU spikes. It has about 24 voices with stealing and a soft limiter on the master.

Building blocks, ported (licenses checked against the repositories):

- Main reverb: the Clouds reverb (Dattorro and Griesinger topology with modulated delays, about 70 lines, MIT, `pichenettes/eurorack` `clouds/dsp/fx/reverb.h`), tuned for 10-30 s tails. It is not Freeverb, which is metallic on sparse plucks.
- Chorus: Plaits `ensemble` (MIT). Shimmer: Clouds `pitch_shifter` (MIT).
- Bells and bowls: a modal resonator after Rings and Plaits (MIT) and FM pairs. Plucks: Karplus-Strong (Plaits string, MIT). Pads: polyBLEP oscillators through stmlib's state-variable filter (MIT).

Headphone placement (`BinauralPanner`): a spherical-head model, not measured HRIRs. Each dry voice is placed at the side its note's pan gives. The far ear hears it up to 0.66 ms later (Woodworth's delay with a 0.0875 m head) and with its highs shaded by Brown and Duda's head-shadow shelf; a voice straight ahead is identical to the constant-power pan. Pads keep their pan and chorus (their 4 ms delays would comb with a delay), the reverb stays stereo and the limiter stays last. A switch crossfades over 0.3 s, so it never clicks. It is off by default. On a Chromecast release build the audio thread measured 45.6 percent of a core with it on and 50.6 percent without, within run-to-run noise.

The `tools` module renders the synth offline to WAV for listening tests, from any start time (`--at`), and analyzes a WAV per five-minute window: peak, RMS, clipping, spectral centroid, onsets, BS.1770 integrated loudness, the share of energy at or below 120 Hz, a score for a level envelope that repeats every 20 s, and the longest rest 40 dB under the window's median.

### AudioSink

`AudioSink` interface: `sampleRate`, `start(renderer: BlockRenderer)`, `stop()`, `playbackFrame()`.

- Desktop: `javax.sound.sampled.SourceDataLine` on a dedicated thread.
- Android: `AudioTrack` (float PCM, streaming) on a dedicated thread.
- iOS: `AVAudioEngine` and `AVAudioPlayerNode.scheduleBuffer`, fed from a thread the app owns, never an `AVAudioSourceNode` render block, so Kotlin/Native GC never runs on the realtime thread.
- Web: Web Audio `AudioBufferSourceNode` chunks scheduled about 1 s ahead of `currentTime` from the main thread. There is no AudioWorklet or SharedArrayBuffer, so no COOP or COEP headers are needed.

### Visual

The scene is full screen, dark and on the shared clock.

- Background: a slow gradient whose hue follows time of day and whose movement follows weather texture, dithered so projectors don't band.
- Nodes: soft glowing orbs placed by position relative to the connected node, with robust auto-fit (percentile bounds; far outliers such as MQTT nodes pinned to the edge), or at a position seeded from the node number when there is no position. Glow follows activity, and orbs fade when a node goes quiet. A relayout glides each orb to its new place, and the gravity layer moves each orb a little around it (`OrbitalField`).
- Packets: a ripple from the sender's orb when its note sounds, colored by articulation and sized by SNR; hop echoes as fainter rings; encrypted traffic as a faint haze.
- Renderer: plain Compose Canvas on every target. Skia runtime shaders (Skiko `RuntimeEffect` on desktop, iOS and web; `RuntimeShader` on Android 33 and later) are an optional enhancement for the background.
- Burn-in: the field drifts slowly and dims at night. OLED screens are the real risk; most projectors are not.
- A hidden HUD (tap, click or `H`) shows a status card with link state, node count, key and section, and a floating toolbar with mute, volume, a Look menu, headphones, the Drivers panel, fullscreen and a two-press disconnect. It follows Material 3 Expressive with the standard motion scheme, so nothing overshoots. Fullscreen is available on desktop and on Android phones and tablets (system bars), and is hidden on a TV; keep-awake runs on every target.

## Project shape

A standalone repository with these modules:

- `shared`: a KMP library with Compose for all targets: sources, composer, synth, sinks, visual and UI.
- `desktopApp`, `androidApp`, `iosApp`, `webApp`: thin entry points.
- `tools`: offline render and analyze commands.
- `blescan`: a Bluetooth scan check.

## Testing

- Listening gate: one-hour WAV renders at three densities (silent, sparse, busy), judged by ear after composer changes.
- Composer in common tests, with expected values from music theory and this document: ornament pitches are chord tones of the current chord in the node's band; a node keeps its role across chord changes; chord changes voice-lead (held common tones); no Locrian; slots and TTL hold under a burst of 200 packets; warm-up emits no notes for a snapshot flush; `hop_start == 0` maps to unknown distance; weather scales to the rolling range; a text message triggers a chord change.
- Synth: the reverb port's tail length, decay, denormal floor and tank tap are checked; no NaN; nothing past the limiter; silence after release; no allocation in the render loop on JVM.
- Mesh mapping: hand-built `MeshPacket`s and `FromRadio` frames (from the proto) go through both mappers, including an encrypted frame reaching `PacketHeard` as `Encrypted` and a decoded `FromRadio.packet` being ignored by the raw mapper.
- Each target makes sound and shows the scene once; iOS runs on a Mac.

## Prior art used

- iart and Idee und Klang, *Sonorous Reception*: hour, weekday and weather mapping; events on a grid handing off to a bed; crowd sets tension.
- Brian Eno, Music for Airports and Reflection: unrelated loop periods; the day changing the rules.
- Generative.fm (MIT, `generativefm/generators`): note-stream variation operators; per-tone loop periods.
- Listen to Wikipedia (BSD-3, `hatnote/listen-to-wikipedia`): ornament slots; a swell for newcomers; positions seeded from identity.
- Mutable Instruments (MIT, `pichenettes/eurorack`, `stmlib`) and Faust `reverbs.lib` (MIT parts): reverb, chorus, shimmer, modal, string and filters.
- kc-audio-bridge (Apache-2.0): reference reading for the platform sinks.
- Peep, the network auralizer: continuous background following network state.
- The full survey with sources is in [`prior-art.md`](prior-art.md).

## Tuned in code

Exact presets and timbres, the chord vocabulary and progression rules, the weekday key table, section lengths, slot count and TTL, warm-up length, reverb tuning, colors, layout math, simulator periods and the connect screen layout are set in the source and tuned by ear.

## Status

Built:

- Mesh, composer, synth and audio sink, with the visual and UI reading the engine, on jvm, android, iOS (source and simulator build) and wasmJs.
- SDK, simulated, replay and demo sources; a connect screen with TCP, Bluetooth, USB serial, WebSocket and a saved link with auto-connect.
- Clouds-topology reverb, polyBLEP, FM, modal, Karplus-Strong and pitch-shifter voices in pure Kotlin.
- A file log on desktop, an overlay with key controls, and the `tools` render and analyze commands.
- Mesh-driven parameters, pass 1: the per-event layer and palettes, `MeshMood` with `ParamValues`, rotation (speed, tilt and wobble, direction), gravity and orbits, the coverage log, the overlay Drivers panel and the demo sweep. A 6 hour run on a Mac against a real mesh held 59.9 fps and 340 MB with no warnings; no person has judged the sound or the visuals from it.

Not built:

- Mesh-driven parameters, pass 2: the landscape mappings beyond those built (reverb, brightness, detune, ensemble, echo, ornament TTL, pad level, haze, background hue and the rest of the Layer 2 table), which wait on coverage data.
- A desktop capture mode; replay plays a bundled synthetic capture.
- WebSerial.
- MIDI and OSC output.
- A run of iOS and web on a device or in a browser, and of Android on a phone or tablet (Android TV has run on a Chromecast with Google TV).
