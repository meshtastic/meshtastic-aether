# Security policy

## Reporting a vulnerability

Do not open a public GitHub issue. File a private [GitHub Security Advisory](https://github.com/meshtastic/meshtastic-aether/security/advisories/new) on this repository.

Maintainers aim to acknowledge a report within five business days and to ship a fix or mitigation within 90 days, depending on severity. The advisory credits the reporter unless they prefer to stay anonymous.

## Supported versions

Aether is pre-1.0. Only the latest published release receives security fixes, and there is no long-term support branch. A support window will be published here with 1.0.

## Scope

The app only listens. It connects to a node over TCP, Bluetooth, USB serial or WebSocket, stores a saved link, the volume and mute, the headphone mode and the palette choice on the device, writes a local log, and sends nothing to the mesh. Android and desktop builds from CI also fetch `update.properties` from this repository's GitHub releases, and download the build it names only when asked. The security surface is the data it accepts from a node, what it keeps locally and the update.

An update is trusted as far as HTTPS and this repository's releases are: the manifest carries each file's SHA-256, and the file must match it. On Android the package must also carry the installed app's signing key and a higher version code. The desktop jar is not signed, so its digest guards against a damaged download, not against a replaced release. `AETHER_UPDATE_MANIFEST` points the check at another manifest for testing, and whoever sets it chooses what is installed.

In scope:

- Handling of hostile or malformed data from a node link: packets, node information and telemetry that crash the app, exhaust memory or stall the audio.
- The saved link and the local log: contents that should not be stored, or that leave the device.
- Any network traffic other than the link to the node the user chose and the update check.
- The update: manifest parsing, download verification and the install.
- Build infrastructure and dependency sources.

Out of scope, so report to the upstream owner:

- The Meshtastic firmware ([`meshtastic/firmware`](https://github.com/meshtastic/firmware)).
- The [`meshtastic/meshtastic-sdk`](https://github.com/meshtastic/meshtastic-sdk) library, which carries the transports, framing and handshake.
- Third-party libraries and ported code listed in [`NOTICE`](NOTICE).

## Disclosure

After a fix ships, the advisory is published, with a CVE ID where one applies. Disclosure is coordinated with the reporter on a timeline.
