# Instructions for Coding Agents

This project uses the [Kotlin Toolchain](https://kotlin-toolchain.org/) (a dedicated build tool for Kotlin) through the `./kotlin` wrapper. Run `./kotlin --help` to see the available commands, or `./kotlin <command> --help` to see command-specific options.

## Global constraints

- No dependency beyond the meshtastic-sdk, kotlinx-coroutines and JetBrains Compose, plus `androidx.activity` in the Android entry point; anything else is written here on platform APIs, so every audio sink is `expect`/`actual` on platform APIs with no third-party audio library.
- Ported code is MIT, BSD, Apache-2.0 or public domain, with its notice in `NOTICE`. Never port Shadertoy code (non-commercial license), Faust `vital_rev` or `kb_rom_rev1` (GPL-3.0-only).
- `shared/src` (commonMain) is pure Kotlin and compiles for jvm, android, iosArm64, iosSimulatorArm64 and wasmJs.
- Time is the audio sample counter (`Long` frames); phases and clocks are `Double` or `Long`, never `Float`.
- The render loop allocates nothing and injects a tiny noise floor into feedback paths against denormals.
- Reverb is the Clouds topology (modulated Dattorro, 10-30 s tails), never Freeverb.
- No Locrian; weekday picks the key, time of day picks mode brightness, density, register and decay.
- A node's role picks the instrument family and its hardware model picks the voice within it (about 15 instruments); packet kind only changes articulation. Encrypted traffic is a pitchless Breath. `hopsTaken == null` means unknown distance, not direct.
- Mesh-driven parameters follow `docs/mesh-driven-parameters.md`: neutral values equal the constants they replaced, rotation is integrated state with comfort caps, and the clock skeleton (weekday key, time-of-day mode, hour chime, night dim, Form arc) stays.
- The app only listens: nothing is sent to the mesh, and the only network traffic is the link to the node the person chose and the update check: Android and desktop builds from CI fetch the followed track's `update.properties` (the build's own channel until Settings picks the other) from the repository's GitHub releases, and download the APK or jar it names only when the person asks to install.
- A real capture holds real names and positions, so captures stay local (`captures/` is ignored) and committed fixtures are synthetic.
- Check a wasm run by its `ERROR` lines as well as `Passed` and `Failed`: the browser runner stops a run at 30 s, so keep long renders in `shared/test@jvm`.
- Commits are Conventional, signed off (`git commit -s`), carry no attribution trailers, and the license is GPL-3.0-or-later.

## Branding rules

- Colors come from the brand palette in the design standards (`design/standards`, section 7), and `shared/test/theme/PaletteSourceTest.kt` enforces it.
- The icon is its own glyph with the Meshtastic M mark on its orb. The mark is the official artwork (`meshtastic/design` `logo/svg`, kept as `M_MARK_PATH` in `AetherGlyph.kt` and as paths in `branding/*.svg` and the Android vectors): scale and place it only, in the dark-on-green colorway; never redraw, distort or recolor it.
- `branding/*.svg` are the sources, and `scripts/icons.sh` regenerates every raster asset; the Android icon is hand-ported XML.
- The theme is dark-only, a documented waiver of the light theme and dynamic color.
- The app is called `Aether` in every string, title, manifest and doc, never `Meshtastic Aether`. `Meshtastic` names the network, the project and the mark, with the trademark line in the README. Identifiers keep the long form: `org.meshtastic.aether`, the `meshtastic-aether` repository and artifact names, and the `MeshtasticAether` log folder and Android log tag.

## Behavior that holds

- With zero packets the composer still emits notes and controls: the autonomous piece sounds complete alone.
- 200 packets in one second keep ornaments under the slot cap, and nothing queues past its TTL.
- A reconnect emits no `PacketHeard` during warm-up, whether the packets come from the `nodes` snapshot or from retained handshake packets.
- A node with no name, no position, null SNR and `hopsTaken == null` sounds at middle distance, center pan and mid velocity, without a crash.
- A flat or single-sample weather range never produces a NaN, and a day rollover changes the key once, on a chord boundary, with no density step above 0.1.

## Architecture

`MeshSource` -> `Composer` -> `Synth` -> `AudioSink`. `visual` and `ui` read the engine; nothing flows back.

- `shared/src/mesh`: `MeshEvent` and the sources (SDK, simulated, demo, replay) plus the packet, payload and telemetry mappers. Nothing downstream sees a protobuf.
- `shared/src/mood`: `MeshMood` folds events into slow, self-normalized signals and the rotation target; `ParamValues` is the immutable result that the composer, scene and overlay read, with a value, unit and driver per parameter and the rotation direction and its strength. Neutral equals the constants it replaced.
- `shared/src/music`: the pure, deterministic `Composer` (theory, harmony, form, day, weather, ornaments); mesh activity, SNR and channel utilization come from `MeshMood`, not a separate crowd model. It schedules by sample frame.
- `shared/src/synth`: pure Kotlin DSP; `Synth` renders `NoteEvent`s and `Texture` changes to stereo float blocks.
- `shared/src/audio`: `AudioSink` plus the block pump; `shared/src@<platform>/audio` holds the per-platform output.
- `shared/src/ui`: `AmbientEngine` (owns the clock, composer, synth and sink; each tick carries `composer.params()` to the scene and the overlay's Drivers panel; feeds `Coverage` and logs its line), connect screen, controls, the HUD overlay, settings.
- `shared/src/visual`: scene model and drawing, driven by the engine's events and the sink's playback frame.
- `shared/src/log`: `Log` with a file sink on desktop, logcat on Android.
- `shared/src/update`: `Channel` (`BuildInfo` is generated by `plugins/build-info`), the `update.properties` manifest and the `Updater` contract; `src@updatable` (jvm and android) fetches and verifies, `src@android` installs through `PackageInstaller` (never for an app store install), `src@jvm` swaps the executable jar and restarts (never for an app installed from a deb, dmg or msi, which `jpackage.app-path` marks). iOS, the web and local builds have no updater. `AETHER_UPDATE_MANIFEST` points the check at another manifest for testing.
- Apps: `desktopApp`, `androidApp`, `iosApp`, `webApp`. Tools: `tools` (offline render and analyze), `blescan` (Bluetooth scan check).
- `plugins/build-info`: a local Toolchain plugin that generates `BuildInfo` into `shared` from `AETHER_BUILD_LABEL`, `AETHER_BUILD_CODE` and `AETHER_BUILD_CHANNEL`, set together in CI; unset, a build is `dev`, code 0, channel `DEV`.

The contracts `MeshEvent.kt`, `NoteEvent.kt` and `AudioSink.kt` change only on purpose, with every consumer updated.

Launch flags: `--demo`, `--replay`, `--tcp host[:port]`, `--serial PORT`, `--ask`, `--fullscreen`, `--look standard|projector|oled` (`shared/src/ui/LaunchArgs.kt`); `--ask` shows the connect screen and ignores the saved link and any `--demo`, `--replay`, `--tcp` or `--serial`. Volume, mute, the headphone mode and the look persist in `Settings`; the master fades in over 3 s each session. Android takes the same flags as intent extras (`launchArgsFrom` in `androidApp/src/MainActivity.kt`).

## UI rules

- Material3 is `org.jetbrains.compose.material3:material3:1.12.0-alpha03` in `libs.versions.toml`, because the Expressive APIs are public only in the 1.12 line, which pairs with Compose 1.12.2; 1.9.0 had them internal. Bump it with Compose, never alone.
- Expressive opt-ins are file-level (`@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)`), never in a build flag.
- The theme is `MaterialExpressiveTheme` with `MotionScheme.standard()`: no overshoot, and the toolbar keeps its standard colors so a saturated slab never burns in.
- UI colors come from `MaterialTheme` roles, never hardcoded values; only the scene reads `LocalAmbientPalette`.
- A user-visible change adds a fragment to `changelog.d/` (`scripts/changelog.sh check` validates it); never edit `CHANGELOG.md` or the manifest versions by hand, the release cut does both (`RELEASING.md`). Never commit a stamped Android `versionCode`: CI writes it (`scripts/stamp-build.sh`).
- Test names in `shared/test` take no commas or parentheses: Kotlin/Native rejects them, and only the iOS test compile shows it. Check with `grep -rn 'fun \`[^\`]*[,()][^\`]*\`' shared/test/`.
- Compose UI tests live in `shared/test@jvm` (`androidx.compose.ui.test.v2.runComposeUiTest`), and no check may need them on wasm. A running session animates forever, so a test cannot wait for it to go idle: test the pieces (`HostCommands`, the chooser, the countdown) on their own.
- Host menus and Back call `AppCommands` and the shared key path (`perform`), never their own copies of an action, so the two-press disconnect and the haptics apply everywhere.
- Desktop installers: the Toolchain only builds the executable jar, so `scripts/package-desktop.sh` wraps it with `jpackage` into a deb, dmg or msi on the matching CI runner, jlinking the JBR the Toolchain downloaded. Icons are in `desktopApp/packaging` (`scripts/icons.sh` regenerates the icons). The deb's t64 dependency names are rewritten so it installs on Debian 12 and Raspberry Pi OS; the dmg gets the Bluetooth and local-network usage strings (macOS ends an app that uses BLE without them) and is signed ad hoc, not notarized.
- The desktop app runs on JetBrains Runtime (`settings.jvm.jdk.distributions: [jetbrains]` in `desktopApp/module.yaml`); libraries, tests and Android use the Toolchain default.
- The HUD is hidden while idle: it draws nothing until opened and closes itself after 15 s (60 s with Drivers open).

## Commands

```sh
./kotlin build                          # every target that compiles on this OS
./kotlin test -m shared -p jvm          # shared tests; add -p wasmJs when shared code changed
./kotlin test -m tools -p jvm
./kotlin test -m build-info             # the BuildInfo generator
./kotlin run -m desktopApp
./kotlin run -m tools -- sparse 1 /tmp/sparse.wav
./kotlin run -m blescan -- 15
```

iOS builds only on a Mac: `./kotlin build -m iosApp`.

## One build at a time

Run one `./kotlin` build or test at a time per checkout, with `KOTLIN_CLI_NO_WELCOME_BANNER=1` for clean output. Agents that share a checkout serialize on a lock every one of them uses (`flock <lock file> ./kotlin <args>`), edit only the paths they were given, and leave staging and commits to whoever coordinates them.

## Test rules

- Test names are backtick sentences without commas or parentheses (Kotlin/Native).
- A test names the regression it catches. Expected values come from music theory, the design documents, the proto or the design standards, never from running the code under test.
- Mutate each change's plausible bugs on a scratch copy, confirm each new test fails, restore from a `.bak`.
- Test fixtures are Kotlin constants: tests cannot read files on the iOS simulator.

## UI Development

Use the Compose Hot Reload MCP server to develop and validate UI changes. It shortens the feedback loop compared with an emulator or simulator, and it provides tools for interacting with the app, capturing screenshots and inspecting the composable semantic tree. Run `./kotlin compose-hot-reload-mcp-server --help` to learn how to configure the MCP server. Prefer headless mode when available. The app must run in `--compose-hot-reload` mode for the MCP server to attach. Use `./kotlin run --help` to learn more.

Before changing UI code, start the app in the background (`./kotlin run -m <compose-hot-reloadable-module> --compose-hot-reload`), then call the MCP `status` tool. Repeat `status` until it reports `connected: true` (the first build can take minutes). `status` is the only readiness signal: do not tail or grep the run log and do not write sleep loops. If startup fails, check `lastError` in `status` and the MCP `get_logs` tool.

Once connected, edit the UI and apply changes with MCP `reload` (or `await_reload` if `buildContinuous: true`).
