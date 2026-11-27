# Aether

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org)

*The mesh, on the ether.* A slow piece of music and visuals that plays on its own and is nudged by live Meshtastic® traffic: packets are soft ornaments, nodes are voices, and mesh activity is the crowd. It targets desktop, Android (phones, tablets, Android TV and Google TV), iOS and the web, and it only listens: nothing is sent to the mesh.

Meshtastic® is a registered trademark of Meshtastic LLC. Aether is a Meshtastic project.

## What this is

A Compose Multiplatform app on the [meshtastic-sdk](https://github.com/meshtastic/meshtastic-sdk), built with the [Kotlin Toolchain](https://kotlin-toolchain.org). It connects to a node over TCP, Bluetooth, USB serial or WebSocket, or plays a scripted demo mesh, and turns what it hears into a generative score and a slowly rotating cloud of nodes. The piece sounds complete with no traffic at all; the mesh perturbs it.

The score and the instruments are synthesized in pure Kotlin on every target, with a per-platform audio output. The app has no dashboard, charts or node lists on the scene.

## Screenshots

https://github.com/user-attachments/assets/e00ab211-8c55-48bb-9fc0-3cbc8f34ab88

The 90 second demo above has sound. Below, the scene while it listens to a generated DEF CON mesh (150 simulated nodes, from the `defcon` preset of `meshtastic-mcp replay`): each orb is a node, each ring a packet, each faint line a neighbor link, and the cloud rotates slowly in 3D.

![Orbs and rings under the simulated mesh](docs/screenshots/scene-1.jpg)

![A node pulsing with its neighbor links](docs/screenshots/scene-2.jpg)

![A packet ring and links to a busy node](docs/screenshots/scene-3.jpg)

The HUD over the same mesh, with the Drivers panel listing what the mesh is doing to each parameter:

![The HUD with the status card, toolbar and Drivers panel](docs/screenshots/hud.jpg)

## Run

```sh
./kotlin run -m desktopApp                      # desktop
./kotlin run -m desktopApp -- --fullscreen      # also: --demo, --replay, --tcp host[:port], --serial PORT, --ask, --look standard|projector|oled
./kotlin run -m webApp                          # browser; tap to start audio
./kotlin run -m androidApp                      # device or emulator
./kotlin run -m iosApp                          # Mac only
./kotlin test -m shared -p jvm                  # shared tests (wasmJs: -p wasmJs)
```

[Releases](https://github.com/meshtastic/meshtastic-aether/releases) carry the Android APK and AAB, the desktop installers (a `.deb` for Linux x64, a `.dmg` for Apple silicon macOS, an `.msi` for Windows x64, each with its own runtime; the macOS app is unsigned, so open it once from the right-click menu) and, for Linux and macOS, the desktop jar (run it with Java 25: `java -jar aether-X.Y.Z-desktop-linux-x64.jar`; build any other desktop from source) and a zipped web build to serve from any static host. The `snapshot` prerelease carries the same files built from the latest `main` that passed CI. The `./kotlin` wrapper downloads the Kotlin Toolchain on first use. [`CONTRIBUTING.md`](CONTRIBUTING.md) lists the build and test commands.

### Android TV and Google TV

The Android app installs on Android TV and Google TV with a launcher banner. Use a release build on a TV device: `./kotlin run -m androidApp -v release` (a debug build is debuggable and runs far slower on a weak CPU). Typing a host with a remote is slow, so start it over `adb` with the desktop flags as intent extras:

```sh
adb shell am start -n org.meshtastic.aether/.MainActivity --es tcp 192.168.1.20:4403
# also: --es serial <port>, --ez demo true, --ez replay true, --es look oled, --ez ask true
```

After the first connect the saved link reconnects on its own. tvOS is not possible: Compose Multiplatform and meshtastic-sdk publish no tvOS variants. On an Apple TV, mirror the iOS app with AirPlay.

To record the sound for a video, start the desktop app with `AETHER_RECORD_AUDIO=out.wav`. The file is 16-bit stereo and stays playable if the app is killed.

## Connect

The connect screen opens on a connected group of toggle buttons that picks one link type at a time: Network (TCP), Bluetooth, USB, WebSocket and Demo. A target lists only the types it supports: Network is desktop, Android and iOS, USB is desktop and Android, and Bluetooth is everywhere but a browser without Web Bluetooth (it needs Chrome or Edge). The group opens on the saved link's type while its countdown runs, else on the type you last chose, else on the first type listed: Network, or on web Bluetooth where the browser has it and WebSocket where it does not. On a TV or with a keyboard, focus starts on the selected type.

- Demo: a scripted five-minute mesh, no node needed. The button reads Start the demo, and `--demo` does the same at launch.
- Network: a host field and a port field (digits only; a blank port means 4403), then Connect over TCP. `--tcp host[:port]` connects at launch on desktop, and the `tcp` intent extra does the same on Android.
- Bluetooth: Scan for Bluetooth radios lists each node by name, the last five characters of its address and a signal word (Strong, Good, Fair, Weak or Signal unknown). Tapping a radio connects, so this pane has no connect button. A browser cannot scan, so on web the pane has one button, Choose a Bluetooth radio, that opens the browser's own device chooser, and picking a radio there connects.
- USB: Find USB radios lists the serial ports present, likely Meshtastic boards first, each by its board name (on Linux from its `/dev/serial/by-id` name, since many boards report the same USB product) and port. Tapping one connects, and the link is saved: on Linux it is saved by its stable `/dev/serial/by-id` path. Android asks for USB permission for each device the first time. `--serial PORT` opens a port at launch.
- WebSocket: a `ws://` or `wss://` address, prefilled with the firmware's WebSocket phone API: `wss://meshtastic.local/api/v1/ws` on web, `ws://meshtastic.local/api/v1/ws` in the native apps, which would refuse a stock node's self-signed certificate (ESP32 firmware serves it on ports 443 and 80, meshtasticd on its web server port). A page served over HTTPS, such as a hosted web app, may only open `wss://`, so the browser must trust the node's certificate: open `https://<node>/` once to accept its self-signed one.
- A connect button stays disabled while its fields are invalid, and the field says why. The Go key on the keyboard submits from the TCP port and the WebSocket address.
- An unreachable host shows `Can't reach host:port - retrying` in the status line, and the raw error goes to the log. A link that fails to start shows its reason on the connect screen.

Desktop, Android and iOS remember the last demo, TCP, USB or WebSocket link. On the next launch a banner reads `Connecting to host:port in N s` (or `Starting the demo in N s`) over a wavy progress bar that fills across 5 s, with Connect now and Cancel. Connect now starts whatever the screen shows, and it is absent while the fields are invalid or Bluetooth or USB is selected. Cancel stops the countdown, and so does any edit to a field or any change of link type. A Bluetooth pick and `--replay` are not saved and leave the saved link as it was.

`--ask` shows the connect screen with no countdown: it ignores the saved link and any `--demo`, `--replay`, `--tcp` or `--serial` on the same command line. A bad `--tcp`, `--serial` or `--look` value shows its error and starts no link.

The layout follows the window width. Under 600 dp it is one column with the connect button pinned to the bottom. From 600 to 839 dp the column is capped at 560 dp and the host and port fields sit side by side. At 840 dp and wider, or in a window shorter than 480 dp, it splits into two panes: the glyph, name, tagline and countdown banner on one side, and a card with the link types and fields on the other.

## Controls

Click or tap the scene, or press H, to show the HUD. It is hidden while the piece plays and draws nothing until opened. The pointer hides after 3 s without movement, and for the first 8 s a faint line reads `Click or press H for controls` (`Tap for controls` without a keyboard, `Press Select for controls` on a TV).

The HUD has a status card at the top left (link, node count, key and mode, section, any error, and the shortcut line where a keyboard is the usual input) and a floating toolbar at the bottom center. The toolbar holds, in order:

- Mute: a toggle that shows a speaker, or a crossed speaker while muted. It takes focus when the HUD opens.
- Volume: a slider. Moving it unmutes.
- Look: one button that shows the current look's icon and opens a menu of Standard, Projector and OLED night, each with its icon and a check on the selected one. The HUD stays up while the menu is open.
- Headphones: a toggle that places the dry voices to the side of the head instead of panning them for speakers. It is remembered and eases in over a fraction of a second. Leave it off for speakers, where the delay and shading sound wrong.
- Drivers: a toggle that lists each mesh-driven parameter with its live value and driver. The panel sits at the right on windows 840 dp and wider, and above the toolbar (up to half the window height) on narrower ones.
- Fullscreen: on desktop, on web, and on Android phones and tablets, where it shows or hides the system bars. Hidden on a TV.
- Update: shown only when a newer build is out. It opens a panel with Install on Android, Install and restart on the desktop jar, or a link to the download page on an installed desktop app.
- Disconnect: the power glyph, in the error color.

Under 600 dp the toolbar splits into two rows, volume and look above the actions. With a keyboard each button shows a tooltip with its name.

The HUD hides after 15 s without input, or 60 s while Drivers is open, and it waits while a control is focused or pressed or a screen reader is on.

Disconnect takes two presses within 3 s, from the toolbar button or the D key. The first press tints the button with the error container, shows `Press Disconnect again within 3 seconds` above the toolbar and, for the key, opens the HUD. The second press ends the session and returns to the connect screen with no countdown. After 3 s the first press lapses.

Haptics fire where the device has them: a confirmed Disconnect plays a confirm tick, and a mute change plays a toggle-on or toggle-off tick, whether it came from the button, the M key or the slider.

Volume, mute and the headphone mode are remembered, and each session ramps the master from silence over 3 s. `--look` picks the palette for that launch (Standard, Projector or OLED night). With `--fullscreen` and no saved choice the look is Projector, otherwise Standard.

A small status line at the top center names a link that is not settled: Connecting, Listening (warming up), `Link lost - retrying`, or the open error. It shows for 10 s after its text changes, then for 10 s at the start of each minute, at one of four heights in turn so it never sits on the same pixels. The HUD names every state: Connecting, Listening, Connected, Demo mesh, Replaying a capture, Link lost.

| Key | Action |
| --- | --- |
| H | HUD |
| F | fullscreen |
| M | mute |
| P | headphones |
| Up, Down | volume |
| D | disconnect (press twice within 3 s) |
| Esc | close the HUD, else leave fullscreen |
| Select (D-pad center) | HUD |
| Play/Pause | mute |

On a TV remote, Back closes the HUD in one press and then returns to the connect screen, and on the volume slider Left and Right move along the toolbar while Up and Down set the volume.

On macOS the menu bar mirrors the keys: File has Disconnect, View has Enter Full Screen (Ctrl+Cmd+F) and Mute or Unmute, and Window has Minimize. Disconnect and Mute are enabled while a session runs, and Disconnect asks for the same second press as the D key. Windows and Linux draw no menu bar over the picture. On Android, Back closes the HUD, then returns from the scene to the connect screen, then leaves the app.

## Design

The UI follows Material 3 Expressive with the standard motion scheme. The piece is calm, so motion never overshoots: the expressive scheme's springs bounce, and the standard scheme settles without it. The toolbar uses the standard floating-toolbar colors, because the vibrant ones fill a wide slab with saturated green that would burn into an OLED screen or a projector left on for days. Components still use the Expressive shapes: connected toggle buttons, a floating toolbar, wavy progress and a loading indicator. Colors come from Material roles on the standards' dark scheme. The theme is dark-only, which waives the standards' light theme and dynamic color (see Branding).

## Mesh-driven parameters

The mesh turns parameters in four layers, and every parameter holds its neutral value until the mesh says otherwise.

- Per event: each packet's own facts shape its note and ripple. The node's role and hardware pick the instrument, and hops, SNR, RSSI, MQTT, position and altitude, battery, payload size and text length (never the text) shape how it sounds.
- Landscape: slow signals from the whole mesh (activity, surge, silence, reach, link quality, senders, chat, arrivals, weather), each scaled against its own rolling 24 h range, so a quiet mesh still reads against its own history. A signal with no data keeps its neutral value. They turn the rotation, phrase speed, mode lean, the pull toward pentatonic and gravity.
- Rotation: the speed, angle and direction of the cloud, integrated so nothing jumps.
- Gravity: each orb circles its place on a ring timed like an orbit, fresh links lean their nodes together, and each packet kicks its node. A gravity parameter from link quality tightens or loosens it all. It moves the picture only.

The clock skeleton stays under the mesh: weekday picks the key, and time of day picks the mode and register, with the hour chime, dawn and dusk, the night dim and the long form.

The palette is about 15 instruments, 8 scales (never Locrian) and 4 tempo bands. The Drivers button in the HUD lists each parameter with its live value, unit and the signal driving it, or a plain note such as `holding 3.0 deg - no wind, channel or sender data` when its signal has no data. The demo sweeps these inputs in five minutes, including a pressure fall that reverses the rotation once per session.

[`docs/mesh-driven-parameters.md`](docs/mesh-driven-parameters.md) has the instruments, scales, signals, mappings and rotation rules.

## Branding

The icon is its own glyph in the brand palette: a luminous green orb with a tilted orbit ring and bright nodes, with the Meshtastic M mark on the orb. The mark is the official artwork from `meshtastic/design` (`logo/svg`), only scaled and placed, in its official dark-on-green colorway; it is never redrawn, distorted or recolored. On the Android themed icon, which is one tint, it is cut out of the orb. The sources are `branding/icon.svg`, `branding/icon-small.svg` and `branding/icon-foreground.svg`, and `scripts/icons.sh` regenerates the iOS, desktop and web raster assets and the Android TV banner from them. The Android icon is hand-ported VectorDrawable XML that follows the foreground source.

The app is dark-only by design: it is a calm projector and night piece. The design standards ask for light and dark themes and for dynamic color on Android 12+, and both are waived here. The app is called `Aether`, never `Meshtastic Aether`: `Meshtastic` names the network, the project and the mark, and the identifiers (`org.meshtastic.aether`, the `meshtastic-aether` repository and file names, the `MeshtasticAether` log folder and tag) keep the long form.

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
- No Bluetooth on web: the type is listed only where the browser has Web Bluetooth (Chrome and Edge, not Firefox or Safari; Chrome on Linux has it off by default), and the page must be served over HTTPS or from `localhost`.
- No sound on desktop: check M (mute) and the volume in the HUD.

## Updates and privacy

Aether only listens: it sends nothing to the mesh. It keeps the saved link, the last link type, the volume, mute, headphone mode and look on the device, and writes a local log. Android and desktop builds from [Releases](https://github.com/meshtastic/meshtastic-aether/releases) check this repository's releases for a newer build of their own channel shortly after launch and then daily, and download one only when asked. Store installs, iOS, the web build and local builds never check.

## Verified on

| Target | Built | Tests | Run |
| --- | --- | --- | --- |
| jvm (Linux) | yes | shared, tools and Compose UI tests pass | offline renders only on Linux |
| jvm (macOS, M4) | yes | - | 6 h live run against a real mesh (about 210 nodes): 59.9 fps, p95 18 ms, 340 MB, about 27% of one core, LocalStats delivered, no warnings. On JetBrains Runtime the visible window holds 60 fps |
| wasmJs | yes | shared tests pass in the browser runner | Chrome on Linux: the connect screen, and the Bluetooth chooser path against a stubbed `navigator.bluetooth`; never against a radio |
| Android TV | release build | - | Chromecast with Google TV (Android 14) over TCP: HUD, remote keys and Back checked; about 27 fps under a 150-node simulated mesh (GPU bound), audio thread under half a core, no underruns |
| Android phone or tablet | compiles | - | never run |
| iOS | shared compiles for the simulator | see `docs/design.md` | never run |
| Bluetooth | - | - | not exercised on a live node |
| USB serial | - | port labels tested | not exercised on a live node |
| Self-update | - | manifest, digest and signer checks tested | desktop jar on Linux against a local manifest: checked, installed and restarted on the new build; the Android install was never run on a device |

The live run was watched through the log and frame statistics; nobody sat through it to judge the sound or the visuals. The real mesh sent no pressure or wind reports (6 nodes with temperature, 20 with battery), so those paths are proven only by tests and the demo.

## Documentation

- [`docs/design.md`](docs/design.md): goals, constraints, how the app works and what is built.
- [`docs/mesh-driven-parameters.md`](docs/mesh-driven-parameters.md): how the mesh turns the parameters.
- [`docs/prior-art.md`](docs/prior-art.md): the survey of prior work and licenses behind the design.
- [`AGENTS.md`](AGENTS.md): architecture and rules for coding agents.
- [`RELEASING.md`](RELEASING.md): versions, snapshots, the release workflow and the update manifest.
- [`SECURITY.md`](SECURITY.md): how to report a vulnerability, and what is in scope.
- [`CHANGELOG.md`](CHANGELOG.md): what changed in each release.
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
