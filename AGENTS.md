# Instructions for Coding Agents

This project uses the [Kotlin Toolchain](https://kotlin-toolchain.org/) (a dedicated build tool for Kotlin) through the `./kotlin` wrapper. Run `./kotlin --help` to see the available commands, or `./kotlin <command> --help` to see command-specific options.

## Global constraints

- No dependency beyond the meshtastic-sdk, kotlinx-coroutines and JetBrains Compose; anything else is written here on platform APIs, so every audio sink is `expect`/`actual` on platform APIs with no third-party audio library.
- Ported code is MIT, BSD, Apache-2.0 or public domain, with its notice in `NOTICE`. Never port Shadertoy code (non-commercial license), Faust `vital_rev` or `kb_rom_rev1` (GPL-3.0-only).
- `shared/src` (commonMain) is pure Kotlin and compiles for jvm, android, iosArm64, iosSimulatorArm64 and wasmJs.
- Time is the audio sample counter (`Long` frames); phases and clocks are `Double` or `Long`, never `Float`.
- The render loop allocates nothing and injects a tiny noise floor into feedback paths against denormals.
- Reverb is the Clouds topology (modulated Dattorro, 10-30 s tails), never Freeverb.
- No Locrian; weekday picks the key, time of day picks mode brightness, density, register and decay.
- A node's role picks the instrument family and its hardware model picks the voice within it (about 15 instruments); packet kind only changes articulation. Encrypted traffic is a pitchless Breath. `hopsTaken == null` means unknown distance, not direct.
- Mesh-driven parameters follow `docs/mesh-driven-parameters.md`: neutral values equal the constants they replaced, rotation is integrated state with comfort caps, and the clock skeleton (weekday key, time-of-day mode, hour chime, night dim, Form arc) stays.
- The app only listens: nothing is sent to the mesh, and the only network traffic is the link to the node the person chose.
- A real capture holds real names and positions, so captures stay local (`captures/` is ignored) and committed fixtures are synthetic.
- Check a wasm run by its `ERROR` lines as well as `Passed` and `Failed`: the browser runner stops a run at 30 s, so keep long renders in `shared/test@jvm`.
- Commits are Conventional, signed off (`git commit -s`), carry no attribution trailers, and the license is GPL-3.0-or-later.

## Branding rules

- Colors come from the brand palette in the design standards (`design/standards`, section 7), and `shared/test/theme/PaletteSourceTest.kt` enforces it.
- The icon is an own glyph; the Meshtastic M mark is never altered or recolored.
- `branding/*.svg` are the sources, and `scripts/icons.sh` regenerates every raster asset; the Android icon is hand-ported XML.
- The theme is dark-only, a documented waiver of the light theme and dynamic color.
- The name is written `Meshtastic Aether`.

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
- `shared/src/ui`: `AmbientEngine` (owns the clock, composer, synth and sink; each tick carries `composer.params()` to the scene and the overlay's Drivers panel; feeds `Coverage` and logs its line), connect screen, controls, overlay, settings.
- `shared/src/visual`: scene model and drawing, driven by the engine's events and the sink's playback frame.
- `shared/src/log`: `Log` with a file sink on desktop, logcat on Android.
- Apps: `desktopApp`, `androidApp`, `iosApp`, `webApp`. Tools: `tools` (offline render and analyze), `blescan` (Bluetooth scan check).

The contracts `MeshEvent.kt`, `NoteEvent.kt` and `AudioSink.kt` change only on purpose, with every consumer updated.

Launch flags: `--demo`, `--replay`, `--tcp host[:port]`, `--fullscreen`, `--look standard|projector|oled` (`shared/src/ui/LaunchArgs.kt`). Volume and mute persist in `Settings`; the master fades in over 3 s each session.

## Commands

```sh
./kotlin build                          # every target that compiles on this OS
./kotlin test -m shared -p jvm          # shared tests; add -p wasmJs when shared tests changed
./kotlin test -m tools -p jvm
./kotlin run -m desktopApp
./kotlin run -m tools -- sparse 1 /tmp/sparse.wav
./kotlin run -m blescan -- 15
```

iOS builds only on a Mac: `./kotlin build -m iosApp`.

## Agent build rule

In the agent workspace run every build and test as `flock /tmp/meshtastic-aether-kotlin.lock ./kotlin <args>` from the repo root, one at a time, with `KOTLIN_CLI_NO_WELCOME_BANNER=1`. Never run `git commit`, `git add`, `git checkout`, `git reset` or `git stash`; edit only the paths you own. The lock file is any path every agent shares; `/tmp/meshtastic-aether-kotlin.lock` is the one used here.

## Test rules

- Test names are backtick sentences without commas or parentheses (Kotlin/Native).
- A test names the regression it catches. Expected values come from music theory, the design documents, the proto or the design standards, never from running the code under test.
- Mutate each change's plausible bugs on a scratch copy, confirm each new test fails, restore from a `.bak`.
- Test fixtures are Kotlin constants: tests cannot read files on the iOS simulator.

## UI Development

Use the Compose Hot Reload MCP server to develop and validate UI changes. It shortens the feedback loop compared with an emulator or simulator, and it provides tools for interacting with the app, capturing screenshots and inspecting the composable semantic tree. Run `./kotlin compose-hot-reload-mcp-server --help` to learn how to configure the MCP server. Prefer headless mode when available. The app must run in `--compose-hot-reload` mode for the MCP server to attach. Use `./kotlin run --help` to learn more.

Before changing UI code, start the app in the background (`./kotlin run -m <compose-hot-reloadable-module> --compose-hot-reload`), then call the MCP `status` tool. Repeat `status` until it reports `connected: true` (the first build can take minutes). `status` is the only readiness signal: do not tail or grep the run log and do not write sleep loops. If startup fails, check `lastError` in `status` and the MCP `get_logs` tool.

Once connected, edit the UI and apply changes with MCP `reload` (or `await_reload` if `buildContinuous: true`).
