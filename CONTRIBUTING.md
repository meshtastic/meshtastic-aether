# Contributing to Meshtastic Aether

This guide covers setting up a build, running the checks and submitting a change.

## Code of conduct

The project follows the [Meshtastic Code of Conduct](https://meshtastic.org/docs/legal/conduct/).

## Developer Certificate of Origin (DCO)

Meshtastic uses the [Developer Certificate of Origin](https://developercertificate.org/), not a CLA. Signing off certifies that you wrote the patch, or may submit it, under the project's license (GPL-3.0-or-later).

Sign every commit with `-s` (`--signoff`):

```shell
git commit -s -m "Your message"
```

That appends a trailer:

```text
Signed-off-by: Your Name <your.email@example.com>
```

To sign off by default:

```shell
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"
git config --global format.signOff true
```

To sign off existing commits, where `N` is the number of commits to amend:

```shell
git rebase --signoff HEAD~N
git push --force-with-lease
```

## Environment

| Tool | Version |
|---|---|
| Kotlin Toolchain | pinned by the `./kotlin` wrapper (`kotlin.bat` on Windows), which downloads the toolchain and its JRE on first use; no separate install |
| Kotlin, Compose Multiplatform | pinned in `common.module-template.yaml` |
| Dependency versions | `libs.versions.toml` |
| Android SDK | only to build or run `androidApp`, with `ANDROID_HOME` set |
| Xcode | on macOS, only for `iosApp` and the iOS targets |
| Browser | only to run `webApp`; a gesture starts the audio |

The Kotlin Toolchain is in alpha, so commands can change between versions. `./kotlin --help` lists them.

Clone:

```shell
git clone git@github.com:jamesarich/meshtastic-aether.git
cd meshtastic-aether
```

## Build and test

| Task | Command |
|---|---|
| Build every target that compiles on this OS | `./kotlin build` |
| Full check (runs the tests) | `./kotlin check` |
| Shared tests on the JVM | `./kotlin test -m shared -p jvm` |
| Shared tests on Wasm, when shared code changed (check the output for `ERROR` lines) | `./kotlin test -m shared -p wasmJs` |
| Tools tests | `./kotlin test -m tools -p jvm` |
| Build the iOS app (macOS) | `./kotlin build -m iosApp` |
| Run the desktop app | `./kotlin run -m desktopApp` |
| Run the web app | `./kotlin run -m webApp` |
| Render the simulated mesh to a WAV file | `./kotlin run -m tools -- sparse 1 /tmp/sparse.wav` |
| List the modules | `./kotlin show modules` |

[`README.md`](README.md) lists the launch flags and the other run targets.

## Changelog

[`CHANGELOG.md`](CHANGELOG.md) is hand-written in [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) form, and no entry is generated from commits. Add a bullet under `## [Unreleased]` in the right section (`Added`, `Changed`, `Deprecated`, `Removed`, `Fixed` or `Security`) for anything a listener or operator would notice: a new or changed behavior, a flag, a link type, a fix to something they could hit. Refactors, test-only changes and CI changes get no entry.

## Architecture rules

[`AGENTS.md`](AGENTS.md) holds the rules and the reasons: the dependency limit, the pure-Kotlin common source set, time as the audio sample counter, an allocation-free render loop, the contract files that change only on purpose, and the test rules. If a rule is in your way, open an issue to discuss the design; don't work around it.

## Submitting a change

1. Fork and branch off `main`, with a short descriptive branch name (`fix/reconnect-countdown`).
2. For non-trivial work, open an issue or comment on an existing one before investing days in it.
3. Make the change:
   - Add tests where there is behavior to verify. A test names the regression it catches, and its expected values come from music theory, the design documents, the proto or the design standards, never from running the code under test.
   - Update `docs/` and `README.md` if the change touches behavior, flags or design.
   - Run `./kotlin check`, plus `./kotlin test -m shared -p wasmJs` when shared code changed, and fix anything red.
4. Sign off every commit (`git commit -s`).
5. Open the PR and fill in the template.
6. Iterate on review. PRs are squash-merged, so intermediate commits don't need to be tidy, but each PR lands as one coherent commit.

### Commit messages

Use Conventional Commits:

```text
feat(ui): add a cancelable reconnect countdown
fix(mesh): treat hop_start 0 as unknown distance
docs(readme): list the launch flags
```

The squash-merge subject is what ships, so keep it readable.

## Reusing code from other projects

Meshtastic Aether is GPL-3.0-or-later, so ported code must be MIT, BSD, Apache-2.0, public domain or another GPL-compatible license. Keep the original copyright and license notice, and add the source and the files it became to [`NOTICE`](NOTICE). Never port Shadertoy code, Faust `vital_rev` or `kb_rom_rev1`. Code from Meshtastic projects under GPL-3.0 can be reused with the origin noted in the commit message.

## Reporting bugs and security issues

Open an issue from the bug template. It asks for the app version, the platform (desktop, Android, iOS or web), the link type (TCP, Bluetooth, WebSocket or demo) and the node's firmware version. Attach the relevant lines of the log; [`README.md`](README.md#logs) says where it is.

Don't open a public issue for a security problem. File a private GitHub Security Advisory as [`SECURITY.md`](SECURITY.md) describes.

## Questions

General Meshtastic questions belong in the [community channels](https://meshtastic.org/docs/community/).
