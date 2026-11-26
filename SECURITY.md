# Security policy

## Reporting a vulnerability

Do not open a public GitHub issue. File a private [GitHub Security Advisory](https://github.com/meshtastic/meshtastic-aether/security/advisories/new) on this repository.

Maintainers aim to acknowledge a report within five business days and to ship a fix or mitigation within 90 days, depending on severity. The advisory credits the reporter unless they prefer to stay anonymous.

## Supported versions

Meshtastic Aether is pre-1.0. Only the latest published release receives security fixes, and there is no long-term support branch. A support window will be published here with 1.0.

## Scope

The app only listens. It connects to a node over TCP, Bluetooth or WebSocket, stores a saved link, the volume and mute, and the palette choice on the device, writes a local log, and sends nothing to the mesh. The security surface is the data it accepts from a node and what it keeps locally.

In scope:

- Handling of hostile or malformed data from a node link: packets, node information and telemetry that crash the app, exhaust memory or stall the audio.
- The saved link and the local log: contents that should not be stored, or that leave the device.
- Any network traffic other than the link to the node the user chose.
- Build infrastructure and dependency sources.

Out of scope, so report to the upstream owner:

- The Meshtastic firmware ([`meshtastic/firmware`](https://github.com/meshtastic/firmware)).
- The [`meshtastic/meshtastic-sdk`](https://github.com/meshtastic/meshtastic-sdk) library, which carries the transports, framing and handshake.
- Third-party libraries and ported code listed in [`NOTICE`](NOTICE).

## Disclosure

After a fix ships, the advisory is published, with a CVE ID where one applies. Disclosure is coordinated with the reporter on a timeline.
