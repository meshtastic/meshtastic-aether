# meshtastic-ambient

Ambient music and visuals shaped by live Meshtastic mesh traffic. A slow autonomous piece plays with no traffic at all; packets, nodes and mesh telemetry perturb it. Compose Multiplatform on the [meshtastic-sdk](https://github.com/meshtastic/meshtastic-sdk), built with the [Kotlin Toolchain](https://kotlin-toolchain.org).

Design: `docs/specs/`. Plan: `docs/plans/`. Prior art: `docs/prior-art.md`.

## Run

```sh
./kotlin run -m desktopApp            # desktop, simulated mesh from the connect screen
./kotlin run -m webApp                # browser; tap to start audio
./kotlin run -m androidApp            # device or emulator
./kotlin test -m shared -p jvm        # shared tests (wasmJs: -p wasmJs)
```

iOS builds only on a Mac: `./kotlin build -m iosApp`.

## Listen without a radio

```sh
./kotlin run -m tools -- sparse 1 /tmp/sparse.wav   # silent | sparse | busy, hours, output
./kotlin run -m tools -- analyze /tmp/sparse.wav               # per-window peak, RMS, clipping, centroid
```

Real captures stay local (`captures/` is ignored).
