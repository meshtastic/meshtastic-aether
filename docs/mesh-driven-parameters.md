# Mesh-driven parameters

A very large parameter space where the mesh turns every dial, so the landscape never repeats: the idea behind iart's *Sonorous Reception*, with the mesh as the lobby.

## Intent

*Sonorous Reception* has 15 instruments, 4 tempos, 7 scales, the hour, the weekday, the weather and a fresh sound for every visitor, all driving a sonic landscape that is always moving. The mesh is a quieter stage: it delivers only 2-4 packets a minute, but every packet carries a dozen facts about a real node. The richness comes from three layers, each mesh-driven:

1. Per event: every packet's own attributes (who, what hardware and role, how far, how strong, where, how charged) shape its note and its ripple. This layer varies on every packet, so it stays alive on a sparse mesh.
2. Landscape: slow, self-normalized signals from the whole mesh move the bed, the texture and the scene.
3. Rotation: the speed, angle and direction of the cloud, driven from both.

## Goals

- A large palette: about 15 instruments, 8 scales and 4 tempo bands, chosen by the mesh.
- Every parameter outside the skeleton below has a named mapping from mesh data. Rotation has three: speed, angle and direction.
- The mapping is visible: the overlay lists each parameter, its live value and its driver (or "no data").
- Convincing on the real mesh: signals are measured before they are relied on, and relative signals are self-normalized so they move.

## Non-goals

- The clock skeleton stays: weekday picks the key, time of day picks the mode skeleton and register, and the hour chime, dawn and dusk, the night dim and the Form arc stay. It is *Sonorous Reception*'s own inheritance and a floor for a quiet mesh.
- No learning, no stored history beyond rolling ranges, and no network calls.

## Constraints

- Sparse mesh: rates are log-scaled and self-normalized against their own rolling 24 h range (as `WeatherScaler` does for weather), so a quiet hour reads as quiet against its own history.
- A signal with no data holds its neutral value and shows "no data". Confidence decays when a source goes silent.
- Neutral equals the constant the parameter replaced, row by row, so nothing changes until the mesh says so.
- Comfort for a projected rotating scene: tilt plus wobble never exceeds 20 degrees off level, angular acceleration is capped, one turn is never faster than 150 s, camera distance is fixed, direction changes decelerate through a stop within 30 s and need hysteresis, and nothing flashes.
- No time-varying phase is a closed-form `sin(2*PI*t/period)` once its period is mesh-driven: each is a phase accumulator.
- No Locrian: any mode bias clamps and changes only at chord boundaries, with hysteresis.
- Pure commonMain Kotlin, deterministic for a seed and a scripted clock, with no per-frame allocation on the read path.

## Layer 1: per event

Every `PacketHeard` carries these, and each drives note and visual parameters directly. Role, hardware, battery, voltage, uptime and altitude come from NodeInfo, DeviceMetrics and Position events and are held per node.

| Attribute | Sonic | Visual |
|---|---|---|
| node role | instrument family (below) | orb core shape weight |
| hardware model | instrument within the family | halo tint offset |
| node number | pitch role, register band, motif, pan seed, vibrato phase | color, depth, breath phase |
| packet kind | articulation: attack, length, brightness | ripple width, ring style |
| hops taken | reverb send, lowpass, echo count, echo decay | ripple life, flash decay, ring count |
| relay byte (resolved) | echo pitch path | relay pulse |
| SNR | velocity | ripple size |
| RSSI | proximity: dryness and filter cutoff | orb nearness (depth bias) |
| via MQTT | shimmer layer on the note | cool tint on the ripple |
| position, bearing, distance | pan, width, lowpass with distance | placement |
| altitude | register offset within the band (high ground rings higher) | z lift |
| battery level | vibrato depth and detune (a low battery wavers) | orb flicker rate |
| voltage, powered externally | brightness | orb brightness |
| uptime | note length (an old node rings longer) | halo size |
| payload size | velocity trim | ripple width |
| text length (never the text) | phrase length | ring count |
| toUs | call articulation | direct pulse |
| firstThisSession | swell | arrival bloom |
| channel index | stereo offset | hue offset |
| environment readings in the packet | glass-chime pitch from temperature, gain from humidity | orb tint |

## Palettes

Instruments, about 15, are built from the synth blocks (modal, Karplus-Strong, FM, ensemble, polyBLEP, noise): Bell, Marimba, Vibes, Kalimba, Glass chime, Harp, Pluck, Mallet, Gong, Bowed string, Choir pad, Pad, Drone, Sub, Shimmer, plus the pitchless Breath. A node's role picks the family and the hardware model hash picks the voice within the family:

- Router and router-late: Sub, Drone, Gong.
- Repeater: Vibes, Glass.
- Tracker and TAK: Harp, Pluck, Kalimba.
- Sensor: Glass, Kalimba, Bell.
- Client, client-mute, client-base and unknown: Marimba, Bell, Mallet, Bowed.

Packet kind stays as articulation.

Scales, 8: Ionian, Dorian, Mixolydian, Lydian, Aeolian, Phrygian, major pentatonic and minor pentatonic (no Locrian). The clock skeleton picks a base and the mesh moves it: valence (link quality and weather warmth) leans bright or dark by at most one step, a spread-out mesh opens toward pentatonic, and a dense busy mesh stays modal. Changes happen at chord boundaries with hysteresis.

Tempo bands, 4: phrase speed (motif step length, answer gap, loop stretch) follows relative activity at 0.5x, 0.75x, 1x and 1.5x. The grid stays at 0.25 s, which is inaudible at this density.

## Layer 2: landscape

Each signal is self-normalized to its own rolling 24 h range, with a stated smoothing, a no-data neutral and a decaying confidence.

| Signal | From | Notes |
|---|---|---|
| activity | packets per minute, log, relative to its own 24 h range | tau 5 min, with a fast layer below |
| surge | each packet adds a small decaying impulse | tau 20 s |
| silence | time since the last packet, log 10 s to 10 min | drives idle floor, reverb wet, loop stretch |
| reach | mean hops, relative | tau 15 min |
| linkQuality | mean SNR, relative | tau 15 min |
| senders | distinct senders in the last 45 min | relative |
| chat | texts and direct messages, decaying accumulator | tau 10 min |
| arrival | distinct new senders, windowed (not the connect snapshot) | tau 15 min |
| weather | per-node deltas then median, so a changing reporting set does not shift it | temperature, humidity, pressure, lux, wind; trends over 3 h need 3 h of data, else neutral |
| windGust | wind_gust and wind_lull from the proto | tau 2 min |
| congestion | the connected node's channel utilization only | tau 2 min |
| interference | noise floor and bad-receive delta from LocalStats, using uptime for reset detection | only when the connected node reports LocalStats |

Landscape mappings: the chord interval runs 480 to 120 s from activity; loop periods stretch from silence (long when quiet); reverb size comes from humidity, reach and silence (renormalized, not additive past 1); reverb wet from silence and humidity; brightness from temperature, link quality and lux; detune from pressure and interference; ensemble depth from wind; echo delay and the visual echo-ring spacing from congestion (one driver for both); echo decay from reach; ornament slot TTL from congestion; pad level from senders; idle floor from silence; flash decay and ripple life from reach; stereo width from the spread of positioned senders; haze from the encrypted share; and background hue from temperature and saturation from humidity within the brand palette.

Built: the signals turn the rotation, phrase speed, mode lean and the pull toward pentatonic. The other mappings in the paragraph above are not built; they wait on what the coverage log shows moves.

## Layer 3: rotation

Angular velocity is integrated state, never a closed-form phase.

- Speed: the base comes from relative activity, one turn per 1200 s (quiet) down to 150 s (busy), plus a per-packet push. Each packet adds a small decaying angular impulse, visible within seconds of a packet, so it works at 3 packets a minute.
- Angle (axis tilt): tilt comes from the absolute pressure trend (median of per-node deltas over 3 h), 4 to 14 degrees, with wobble amplitude of 1 to 6 degrees from wind, or from relative senders when there is no wind (neutral 3). Tilt plus wobble is capped at 20. With no weather data, relative reach drives the tilt.
- Direction: the first signal with data wins: pressure trend (rising is clockwise), temperature trend (warming is clockwise), battery trend (only nodes whose level moved), then activity trend over 30 minutes. A flip decelerates to a stop and turns within 30 s, with hysteresis and a 30 minute minimum between flips.
- Breathing, drift, anchor breath and orb breath each keep a phase accumulator, so a driven period never jumps their phase.

## Layer 4: gravity and orbits

A small physics layer moves each orb within 0.16 unit-disc units of the place the layout gave it, so the map stays readable. It adds no draw calls, and it costs a few thousand pair operations a frame.

- Orbits: each orb circles its place on a ring. The period follows Kepler's third law with the distance from our node (angular speed `0.35 rad/s` at half the radius, falling as `r^-1.5`, capped at `1 rad/s`), and the ring turns the way the cloud is turning. The ring is `0.07` wide for a node that is unheard and shrinks by up to 70 percent as the node's smoothed RSSI goes from -120 to -40 dBm and gravity rises.
- Links: every fresh edge (a neighbor report or a traceroute hop) pulls its two nodes toward each other, in proportion to gravity and the edge's freshness, which fades over 60 s. Our node is fixed at the origin, so a node linked to it leans toward the centre.
- Packets: each packet kicks its node by up to `0.12` units a second in a direction that is a fixed function of the node and its kick count. The spring to the ring brings it back within seconds.
- Gravity, the one parameter: `0.5` plus the link-quality signal's distance from neutral, weighted by its confidence, so a mesh whose mean SNR is rising against its own history clumps and rings tighten, and a falling one loosens. It holds `0.5` with no link data and shows "no link data" in the Drivers panel. It eases over 10 s like the rotation parameters.

## Architecture

```
MeshEvent ─▶ MeshMood (composer tick) ─▶ ParamValues (immutable, in TickResult)
                                            ├─▶ Texture (generated from it)
                                            ├─▶ Composer / Synth
                                            └─▶ Scene and Projection (ease toward it per frame)
Overlay: lists each parameter, value, driver or "no data" (metadata over plain functions)
```

- `MeshMood` is the one source of truth for rate, SNR and utilization. It runs inside the composer tick off the main thread, `TickResult` carries an immutable `ParamValues` to the UI thread, and the scene eases toward it per frame.
- Mappings are plain, tested functions. The table in the overlay is metadata describing them, because a generic table cannot express the rotation state machine or the driver fallbacks.
- Events: `NodeSeen` carries role and hardware, `Health(node, batteryPct, voltage, uptime)` carries device metrics, `Stats` carries LocalStats with uptime, and `Environment` carries wind direction, gust and lull. RSSI is on `PacketHeard`.

## Coverage log

The app logs a coverage line ten minutes after the link comes up and then hourly: how many distinct nodes reported each telemetry field (temperature, humidity, pressure, lux, wind, battery, voltage), the role and hardware distribution, and whether the connected node delivers LocalStats over the phone API. Layer 2 mappings are built only on signals the coverage shows move; the rest stay listed as not built.

A 6 hour live run on a real mesh (212 nodes) reported temperature from 6 nodes, humidity 3, pressure 0, lux 3, wind 0, battery 20 and voltage 18, with LocalStats delivered. Roles were CLIENT 149, CLIENT_BASE 24, CLIENT_MUTE 15, ROUTER 13 and SENSOR 1.

## Demo mode

The scripted demo sweeps the signals and the per-event attributes so every mapping is visible in five minutes with no node: traffic rises and falls, a router, a tracker and a sensor each speak, one node runs low on battery, one sits on high ground, one packet arrives by MQTT, and a pressure fall reverses the rotation once per session (the first pass carries three hours of pressure history to make that possible).

## Testing

- Behavioral tests of the plain functions with hand-computed expectations, not the table against itself: a quiet hour against a busy 24 h range reads low; activity at its range minimum and maximum gives the stated speeds; the per-packet push decays; the speed floor of 150 s holds; a flip passes through zero within 30 s and not inside hysteresis; tilt plus wobble never exceeds 20 degrees; neutral mood reproduces the replaced constants; phase accumulators never jump when a period changes.
- Palette: the same role and hardware always give the same voice, every role maps into its family, no mode bias reaches Locrian, and changes happen only at chord boundaries.
- Signals from hand-built event streams, with no NaN for empty, flat or single-sample input, and LocalStats deltas that survive an uptime reset.
- Coverage line format and counts from a fake stream.

## Status

Built:

- The per-event layer and palettes, `MeshMood` with `ParamValues`, the rotation (speed, tilt and wobble, direction), the coverage log, the overlay Drivers panel and the demo sweep.

Not built:

- The landscape mappings beyond those listed as built in Layer 2.

## Tuned in code

Curve shapes inside the stated ranges, the confidence ramp, the exact overlay layout, how instruments are voiced from the blocks, and the order of table rows are set in the source.
