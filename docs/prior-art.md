# Aether: prior art report

Scope: what the app can take (ideas, algorithms, code, constants) for a GPL-3.0-or-later KMP/CMP app that turns live Meshtastic traffic into generative ambient music and a full-screen visual. Researched 2026-11-25. Licenses below were read from the repo's LICENSE file, README or the source file header, through the GitHub API, unless marked otherwise.

License key for GPL-3.0-or-later: MIT, BSD, ISC, Apache-2.0, public domain and STK-4.3 can be copied in (keep notices). GPL-3.0 (-or-later or -only) can be copied in, but GPL-3.0-only code makes the combined binary effectively GPL-3.0-only. LGPL-3.0 is fine. MPL-2.0 is file-level copyleft and GPL-compatible unless a file is marked "Incompatible With Secondary Licenses". AGPL-3.0 and CC BY-NC-SA (Shadertoy default) are ideas only.

---

## 0. Findings that should change the design

1. Latency doesn't matter to this app, so it needs no low-latency engine. Nothing here is played live by a person except optional visitor triggers. Render well ahead (100-500 ms of buffer) with a lookahead scheduler on the sample clock. That makes Kotlin/Native and Wasm GC pauses harmless and simplifies every sink. Chris Wilson's "A tale of two clocks" pattern: a timer every ~25 ms schedules events into a ~100 ms lookahead window on the audio clock, and never triggers sound straight from the timer (https://web.dev/articles/audio-scheduling). The app's version: packet → event queue stamped with a target sample frame (quantized to the beat grid) → the render loop consumes events by frame number.
2. iOS: don't synthesize inside an `AVAudioSourceNode` render block from Kotlin/Native. Two independent KMP codebases document the pattern. `pandulapeter/campfire` (MPL-2.0) feeds an `AVAudioPlayerNode` from its own thread with `scheduleBuffer`, keeping N buffers in flight and paced by a semaphore. Its stated reason is that a render block "would run Kotlin/Native on the real-time audio thread, where a collection is a gap in the sound" (https://github.com/pandulapeter/campfire, `libs/metronome/implementation/src/iosMain/.../IosAudioOutput.kt`). Klarinet (Apache-2.0) does use `AVAudioSourceNode` from Kotlin (https://github.com/vectencia/Klarinet, `AudioStream.apple.kt`), so it's possible, but riskier. With big buffers the player-node approach is the right fit.
3. Web: a SharedArrayBuffer FIFO into an AudioWorklet needs cross-origin isolation. Phil Burk's kc-audio-bridge synthesizes in Kotlin/Wasm on the main thread and writes a stereo FIFO in a `SharedArrayBuffer` that a JS `AudioWorkletProcessor` drains. It warns when `window.crossOriginIsolated` is false. Hosting therefore needs `Cross-Origin-Opener-Policy: same-origin` and `Cross-Origin-Embedder-Policy: require-corp`, and `file://` can't provide them (Mozilla bug 1630877). Synthesis on the main thread also competes with Compose rendering, which is one more reason for a deep FIFO. Running the Kotlin/Wasm module inside the AudioWorklet scope is unproven: one Kotlin Slack report hit a module import error and got no answer. Autoplay: the AudioContext only starts after a user gesture, so an unattended kiosk needs a "tap to start" or Chrome's `--autoplay-policy=no-user-gesture-required` flag.
4. Android SkSL needs API 33. `android.graphics.RuntimeShader` (AGSL) exists only on API 33+. On desktop, iOS and wasmJs one `skiaMain` source set can use `org.jetbrains.skia.RuntimeEffect.makeForShader` + `RuntimeShaderBuilder`. KMPLiquidGlass wires exactly this: `skiaMain` is shared by `desktopMain`, `iosX64/Arm64/SimulatorArm64Main` and `wasmJsMain` (https://github.com/Kashif-E/KMPLiquidGlass, Apache-2.0, `backdrop/build.gradle.kts`, `backdrop/src/skiaMain/.../SdfShader.kt`). So the app needs a plain Compose Canvas fallback for Android below 33, and shaders as an enhancement. That build proves the wasm source set compiles against it; It was not seen running on wasm.
5. Map aggregate data to meta-parameters, not just to notes. Idee und Klang, the sound designers of the iart piece, state it outright: at Media World (Verkehrshaus Luzern) they mapped crowd-level data (visitor count, interaction frequency) to "musical tension, rhythmic density, and overall intensity". Individual interactions get "local sonic feedback" that must sit on a "fixed musical grid" and then hand off into the ambient bed (https://ideeundklang.com/audio/en/ideas/research/generatives_audiodesign). For this app: packet rate, node count, mean SNR and channel utilization should drive density, brightness, tension and register as smoothed meta-parameters, while single packets are ornaments on the grid. That protects the piece from a busy mesh turning into noise and a quiet one into silence.
6. The day cycle should change the rules, not just the tempo. Eno and Chilvers' Reflection app changes its rules with the time of day. Harmony is brighter in the morning and drifts back to the original key by evening, and late at night changed conditions thin the notes out and slow everything down (Synthtopia/Stuff coverage of Reflection, https://www.stuff.tv/review/app-of-the-week-brian-eno-reflection-review). iart's piece uses hour → tempo (4 tempos), weekday → scale (7 keys/scales) and weather → texture, with 15 instruments (https://iart.ch/en/work/190-la-salle-street). Combine the two: order modes by brightness (Lydian > Ionian > Mixolydian > Dorian > Aeolian > Phrygian) and let time of day move along that axis plus density, while weekday picks the tonic/mode family.
7. Use a polyphony cap with a time window, plus pitch fuzz. Listen to Wikipedia, the closest product to this app, caps at `note_overlap = 15` notes, each holding a slot for `note_timeout = 300` ms. Excess events are drawn but stay silent. It also adds a random ±2-step fuzz to the computed pitch index so equal inputs don't hammer one note (`static/index.html`, `static/js/app.js` `play_sound`). Adopt both: the cap as the density limiter, and the fuzz as variation that stays within the scale.

---

## 1. Data-driven sonification closest to this app

### Listen to Wikipedia (Hatnote), BSD-3-Clause
https://github.com/hatnote/listen-to-wikipedia. LICENSE is BSD-3-Clause, (c) 2013 Stephen LaPorte and Mahmoud Hashemi. The GitHub API says NOASSERTION only because it doesn't parse the file.

What makes it pleasant for hours, from the source:
- Two timbres plus one event class. Additions play celesta, removals play clavichord (`sounds/celesta`, `sounds/clav`). A first-time editor plays a random orchestral swell. The analog here is a first-seen NodeInfo → swell.
- Size → pitch on a log curve, inverted (bigger = lower), quantized to a sample index:
  `pitch = 100 - min(100, ln(size + 1.0715) / ln(1.0715))`, `index = floor(pitch/100 * N) + rand(-2..1)`, clamped. Size is `max(sqrt(|bytes|) * 5, 3)`.
- Polyphony cap: 15 concurrent notes, 300 ms each (see design point 7).
- Visual: circle radius scales with sqrt(size). Position is seeded from the page title (`Math.seedrandom(data.page_title)`), so edits to the same page land in the same place. Circles fade over `max_life = 60000` ms, an outer ring expands from r+20 to r+40, and a background tint scales with edits per minute. Take: seed each node orb's position from its node ID (or place it by real position when known), so the map has stable geography.
- Inspired by BitListen (below).
- Hatnote's blog asks for attribution when remixing.

### BitListen / Listen to Bitcoin, MIT
https://github.com/MaxLaumeister/bitlisten. Each transaction is a bubble plus a celesta-like bell, and bigger transactions give lower pitch and bigger bubbles. A block is an orchestral swell. It's the same grammar as Listen to Wikipedia: frequent small events are bells, rare big events are swells. The press says nothing about a pentatonic scale and one was not confirmed in code.

### Peep, the Network Auralizer (Gilfix and Couch, LISA 2000), GPL-2.0
https://www.usenix.org/legacy/events/lisa2000/gilfix/gilfix_html/index.html, https://sourceforge.net/projects/peep/
- Natural sounds (birds, water) instead of beeps. Each event type is one sound.
- Discrete events get a single sound. Ongoing states change the type, volume or stereo position of a continuous background sound.
- Heartbeat sounds mean "the network is working", and silence is itself the alarm.
- Take: the always-on drone is Peep's background state, so let mesh health (packets per minute, channel utilization, node count) modulate its timbre, level and pan slowly. Consider a faint periodic "heartbeat" tied to the connected node's telemetry or position broadcasts, so a dead link is audible as a change rather than a guess.

### Network-traffic sonification research
- Vickers, Laing and Fairfax, "Sonification Aesthetics and Listening for Network Situational Awareness" (https://arxiv.org/abs/1409.5282): the goal is to "maximise acceptance while minimising annoyance and listener fatigue". They argue soundscape (ecological) designs beat other approaches for long listening.
- Falk and Dykstra (2019): music that favors aesthetics over bandwidth, tested on 708M authentication events over 9 months. CMU CyLab (2021): non-musicians could hear traffic changes in a musical mapping (https://cylab.cmu.edu/news/2021/07/30-sonification.html).
- *The Sonification Handbook* (Hermann, Hunt, Neuhoff, 2011) is free online. Read the parameter-mapping chapter by Grond and Berger (https://sonification.de/handbook/).
- Stephen Roddy, *Signal to Noise Loops* (Dublin IoT sensor networks; https://www.stephenroddy.com/projects/s2nl/s2nl_project): a pre-sonification smoothing filter controls how much detail of the data reaches the listener, from small changes to trends. Take: an explicit per-parameter smoothing time constant (seconds to minutes) for every environment-telemetry mapping.
- No prior Meshtastic, LoRa or LoRaWAN traffic sonification turned up in standard and extended searches, so there is no direct prior art to take.

### iart and Idee und Klang
- *Sonorous Reception* (iart page, 2022): hour → tempo, weekday → scale, weather → texture; 15 instruments, 4 tempos, 7 keys/scales; 91 speakers, of which the 8 nearest the visitor play; 81 light sensors and 3 motion sensors (https://iart.ch/en/work/190-la-salle-street). Idee und Klang's page calls the same work *Ascend* (2021) and says 95 speakers (https://ideeundklang.com/audio/en/work/media-design/La-Salle). Neither page publishes the actual mapping values or volume policy.
- Idee und Klang principles (research page above): two-tier sound (local feedback at the interaction point plus responsive ambient sound across the room); layered events (one trigger produces several layers with different purposes); local-to-global handoff (a local impulse decays into a "cloud" that drifts into the ambient bed); a fixed musical grid; aggregate data → meta-parameters. Museum of the Future (2017): button impulses decay into sound clouds that resolve into orchestral events on the synced grid.
- Take: a packet = impulse (pluck or bell, panned by bearing) whose reverb/delay tail is fed into the pad bed's input. Each event literally "joins" the drone, so dense traffic thickens the bed instead of cluttering the foreground.

---

## 2. Generative ambient practice

### Eno: incommensurable loops (Music for Airports 2/1)
Tero Parviainen's "JavaScript Systems Music" (https://teropa.info/blog/2016/07/28/javascript-systems-music; code https://github.com/teropa/musicforairports.js, ISC):
- Seven sampled notes, each looping with its own period and start delay (seconds): F4 19.7/4.0, Ab4 17.8/8.1, C5 21.3/5.6, Db5 22.1/12.6, Eb5 18.4/9.2, F5 20.0/14.1, Ab5 17.7/3.1. Eno's original tape loops: 23 1/2, 25 7/8, 29 15/16 s. The durations matter more than the delays, because periods that don't divide evenly realign almost never.
- Reich *It's Gonna Rain* phasing: two copies of one loop, the second at `playbackRate 1.002`, panned hard left and right.
- Take: this is the "memory" mode. When traffic is quiet, recently heard node pitches each get a loop period drawn from ~17-30 s plus a random offset, and fade out after K repeats. Also use incommensurable periods for every slow LFO in the pad bed (filter, pan, detune, chord-tone gains), e.g. 23, 29, 37, 41, 53 s.

### Generative.fm (Alex Bainter), MIT
Repos (under `generativefm`): https://github.com/generativefm/generators (pieces, MIT, (c) 2019 Alexander Bainter), https://github.com/generativefm/generative.fm (MIT), samples repo MIT (samples are from third-party libraries; irrelevant because the app synthesizes). Tone.js-based (Tone.js is MIT). Concrete techniques from source:
- piece-eno-machine: the notes of a Db major 9th (inverted) over octaves 3-5. Each note repeats every `rand(20, 60)` s, starting after `rand(0, 40)` s, on piano. That's 30 lines for an endless piece.
- piece-meditation: singing bowls. A low pool (tonic + 5th, octaves 1-2) and a high pool (+M2, P4, octaves 3-4) each fire on a timer of `min (45 s / 75 s) + rand*5 s`, with a 70% chance of a second note 1-16 s later. A 20 s FeedbackDelay at feedback 0.8, wet 0.5, gives an enormous slow echo. The tonic is randomly C/C#/D/D# per session.
- piece-observable-streams: an RxJS operator pipeline over a note stream, which is the best template for a packet → note pipeline. Operators: `octaved(p=0.2,+1)`, `octaved(p=0.2,-1)`, `octaved(p=0.4,-2)`; `chord(p)` (with probability p, replace a note with a random diatonic chord that contains it, using a note → chords map built from the scale); `delayed` (20% of notes rescheduled 0.7-10.7 s later, which is where the echo/memory feel comes from); `humanize` (0-100 ms jitter); `shortTermThrottleByNote` (suppress the same pitch repeating too soon). Take: model the mapper as a chain of small `(Event) -> List<Event>` operators, each with probability and rate-limit, in commonMain.
- Session-level seeded randomness (`window.generativeMusic.rng()`): seed per session or per day, so a day has a consistent character.

### Eno and Chilvers: Bloom, Reflection, 77 Million Paintings
- Bloom (App Store listing): 12 moods, "evolve when idle", adjustable delay, sleep timer. The engine is sample-based, and the visual is expanding and fading circles that Eno asked to keep simple (USC preservation study, https://scalar.usc.edu/works/bloom-hd-app-preservation/code-study). "Evolve when idle" is the "memory notes when quiet" mode. Bloom's taps repeat as a fading loop; that's general knowledge, not verified in a primary source.
- Reflection: rules change through the day (see design point 6). Eno spent "days and weeks" tuning materials and rules. Entirely hands-off.
- 77 Million Paintings: 296 works (from 380 slides) overlaid up to four at a time with slow crossfades (https://en.wikipedia.org/wiki/77_Million_Paintings). Take for visuals: 3-4 slowly crossfading background layers (gradient, noise field, weather texture, ripple residue) whose periods are incommensurable, like the audio loops.

### Endel
Self-described: pre-designed sound layers combined in real time by inputs such as time of day, natural light, weather and heart rate, built around circadian and ~110-minute ultradian cycles (https://endel.io/technology, Endel help center). The patent is claimed but not located. Take: a long-form arc of about 90-120 min (one "movement": slow tension build and release, key change at the trough) layered on the 24 h day cycle. That gives structure across hours, which loops alone don't.

### Koan / Wotja (SSEYO, Intermorphic)
About 150-200 parameters of rules (scales, harmony rules, rhythm rules, "mutation"), and Eno's *Generative Music 1* (1996) was made with it (https://intermorphic.com/archive/sseyo/koan/). No code available. The lesson is that a rule set with weighted scale-degree choices and per-voice ranges beats free randomness.

### Terry Riley, *In C*
53 patterns played in order. Each player repeats at will, stays within 2-3 patterns of the others, and the piece ends when all reach #53 (score/instructions https://ccrma.stanford.edu/~cc/vox/InC.pdf; Parviainen's realization https://github.com/teropa/in-c, ISC). Take: a shared "harmonic pointer" that advances slowly (e.g. a chord progression of 8-16 states over the ~110 min arc). Every voice (packet-type instruments, pad, memory loops) draws from the current state, and voices may lag one state behind for overlap. That's how independent voices stay coherent.

### Euclidean rhythms
Toussaint, "The Euclidean Algorithm Generates Traditional Musical Rhythms" (BRIDGES 2005, https://cgm.cs.mcgill.ca/~godfried/publications/banff.pdf). These patterns are the maximally even ones (Demaine et al.). Take: quantize packet events onto Euclidean grids E(k, n) where k rises with mesh density and the grid rotates slowly. Busy meshes then sound like a gamelan-like ostinato instead of random clicks. The Bresenham one-liner is enough: `hit(i) = ((i * k) % n) < k`.

### Tidal / Strudel (ideas only)
Tidal is GPL-3.0 (https://github.com/tidalcycles/Tidal). Strudel moved to Codeberg and is AGPL-3.0 (https://codeberg.org/uzu/strudel), so take ideas, not code. Useful pattern transforms to reimplement: `degradeBy(p)` (random drop), `sometimesBy(p, f)`, `every(n, f)`, `off(t, f)` (delayed transformed copy, an echo harmony), euclid `(k,n,rot)`, `slow`.

### Markov and probabilistic harmony
No single source to take from. The generative.fm `chord(p)` operator (a note maps to the diatonic chords containing it) plus a small hand-written chord-transition table weighted toward common-tone movement (I-vi-IV-ii in the current mode, 1-3 changes per 10 minutes) covers it. Keep the harmonic rhythm glacial: the iart, Eno and Reflection examples all change harmony on the scale of minutes or hours, not bars.

### What keeps these pleasant for hours (synthesis of the above)
1. Few timbres, all soft-attack or bell-like with long natural decays; no hard transients at high level.
2. One scale or mode at a time, changing only over minutes or hours; events never leave the pitch set.
3. Density limited by a cap with a window, plus aggregate data driving density instead of raw event count.
4. Incommensurable periods for anything that repeats.
5. A continuous bed so silence never happens and events have a harmonic context.
6. Big, slow space (long reverb or 20 s delays): events blur into the bed.
7. Loudness stable (limiter, slow gain riding); low end under control.
8. The day cycle changes the rules (brightness, density, tempo), not just the parameters.

---

## 3. Synthesis building blocks to port (licenses checked)

Ranked by lushness per line of code.

| Block | Source | License | Size / notes |
|---|---|---|---|
| Clouds reverb (Griesinger topology from Dattorro: 4 input allpasses, then a loop of 2x (2 AP + delay), LFO-modulated AP1 and long delay for "slow shimmer/chorus") | https://github.com/pichenettes/eurorack `clouds/dsp/fx/reverb.h` + `fx_engine.h` | MIT (STM32 projects; README: "Code (STM32F projects): MIT license", file header MIT) | ~70 lines of DSP plus a small delay-line engine (`fx_engine.h`, 302 lines incl. 12-bit storage that can be dropped). Delay lengths at 32 kHz: 113, 162, 241, 399, 1653, 2038, 3411, 1913, 1663, 4782; diffusion 0.625, lp 0.7, LFOs 0.5 Hz and 0.3 Hz. Scale lengths by the sink's sample rate / 32000. Best first reverb. Rings and Elements have similar `reverb.h`. |
| Greyhole ("spacey washes"; diffuser in feedback with a long modulated delay, inspired by Eventide) and JPverb (lush modulated reverb with 3-band decay) | Faust `reverbs.lib` → `jp_gh_rev`, authors Julian Parker / Till Bovermann | MIT (`declare greyhole license "MIT"`, `declare jpverb license "MIT"`) | Port from the Faust source; parameters documented in the lib (dt, damp, size, early_diff, feedback, mod_depth, mod_freq). Greyhole at feedback ~0.5-0.8 is a ready-made ambient "texture" knob, so map weather to it. |
| Freeverb | Jezar, https://github.com/sinshu/freeverb (readme: "SOURCE CODE in the public domain") | Public domain | 8 parallel combs + 4 allpasses per channel, right channel detuned (+23 samples). Simple, but metallic and less lush than the above; fine as a "room" option. Faust `mono_freeverb`/`stereo_freeverb` also exist. |
| Dattorro plate | Paper: https://ccrma.stanford.edu/~dattorro/EffectDesignPart1.pdf (JAES 45(9), 1997) gives every delay length and coefficient (at 29,761 Hz). Faust `dattorro_rev` (STK-4.3, permissive); `el-visio/dattorro-verb` (MIT) | paper = algorithm; impls permissive | The classic lush plate with modulated tank allpasses. Clouds' reverb is a compact cousin. |
| FDN reverb, Signalsmith style | Article: https://signalsmith-audio.co.uk/writing/2021/lets-write-a-reverb/; example code https://github.com/Signalsmith-Audio/reverb-example-code has no license (reimplement from the article); Signalsmith DSP lib https://github.com/Signalsmith-Audio/dsp is MIT | article ideas; lib MIT | Diffuser: 4 steps (20/40/80/160 ms) of delay + shuffle/polarity flip + Hadamard over 8 channels, then an 8-line FDN (100-200 ms) with a Householder matrix, ~85% feedback, gentle modulation, high-shelf in loop. Very little code, very smooth, scales to "infinite" pads. |
| Ensemble chorus (string-machine ensemble: two LFOs at 0.75 Hz and 6.57 Hz, three taps) | `plaits/dsp/fx/ensemble.h`; Rings `rings/dsp/fx/ensemble.h`, `chorus.h` | MIT | ~100 lines. Instantly turns a saw pad into a lush string bed. Put it on the drone. |
| Shimmer (pitch shifter in reverb feedback) | `clouds/dsp/fx/pitch_shifter.h` (115 lines) | MIT | Two-tap crossfading delay pitch shifter; +12 st in the Clouds/Dattorro feedback path = shimmer. Use sparingly (fatigue). |
| Modal resonator (bank of SVF bandpasses = struck bars, bowls, bells) | `rings/dsp/resonator.cc`, `plaits/dsp/physical_modelling/modal_voice.cc`, `resonator.cc` | MIT | Feed a short noise burst or impulse in and get a bowl or bell. Ideal for "telemetry / position packet" bells; SNR maps to brightness or damping. |
| Karplus-Strong string with dispersion | `plaits/dsp/physical_modelling/string.cc`, `string_voice.cc`; `rings/dsp/string.cc` | MIT | Plucks for text-message packets. Plain KS is ~15 lines (delay + averaging lowpass); Plaits adds allpass dispersion and damping for a nicer tone. |
| 2-op / 6-op FM | `plaits/dsp/engine/fm_engine.cc`; Faust `dx7` folder | MIT (Plaits); check Faust dx7 headers before copying | FM bells: carrier:modulator ratio ~1:1.4 (inharmonic) or 1:3.5, with the index decaying faster than the amplitude (Chowning 1973). ~20 lines. |
| Granular | `clouds/dsp/granular_processor.cc`, `grain.h` | MIT | Overkill for v1; a later "memory" texture could granulate the last N minutes of output. |
| Filters, oscillators, utilities | stmlib (https://github.com/pichenettes/stmlib): `dsp/filter.h` (TPT state-variable filter with tan approximation, one-pole), `polyblep.h`, `cosine_oscillator.h`, `parameter_interpolator.h`, `limiter.h` | MIT (file headers; repo API says NOASSERTION) | Exactly the small primitives the app writes; the polyBLEP and SVF are the ones worth copying verbatim. |
| Airwindows | https://github.com/airwindows/airwindows | MIT | Hundreds of small effects (tape, console saturation, reverbs like "Galactic", "Verbity"). Good for a final "glue" stage; each plugin is one self-contained process function. |

### JVM libraries (port ideas or ugens; most can't be commonMain dependencies)
- JSyn (Phil Burk), Apache-2.0, https://github.com/philburk/jsyn: a mature Java unit-generator set (envelopes, SVF, oscillators, PinkNoise). Java only, so port classes to Kotlin.
- TarsosDSP, GPL-3.0, https://github.com/JorenSix/TarsosDSP: analysis-focused (pitch detection, FFT); low value here.
- Minim, LGPL-3.0, https://github.com/ddf/Minim. Beads: README says "GPL v3", no LICENSE file, https://github.com/orsjb/beads. Neither beats Mutable/Faust for sound.
- SuperCollider, GPL-3.0, https://github.com/supercollider/supercollider. Greyhole/JPverb originated in sc3-plugins (DEIND); take them from Faust's MIT versions. SC `Pbind`/`Pseq`/`Pwrand` pattern semantics are good design references.
- Pure Data, BSD-3, https://github.com/pure-data/pure-data: the `rev1~/rev2~/rev3~` reverbs and `vline~` are permissive references.

### Faust as a generator or oracle
- Faust compiler is LGPL-2.1+ (COPYING.txt). It has backends for c, cpp, java, csharp, rust, wasm, cmajor, julia, dlang and more (https://github.com/grame-cncm/faust/tree/master-dev/compiler/generator). So `faust -lang java` can emit a JVM class, which is usable on desktop or Android but not in commonMain (Kotlin/Native, Wasm).
- Generated-code license: the Faust libraries carry an exception allowing distribution of compiled code under your own terms. Per-function licenses are declared in the libs (`declare <fn> license`), so check each one. In `reverbs.lib`: `jpverb`, `greyhole`, `valhallaSupermassive` (David Braun) are MIT; `dattorro_rev_default` is STK-4.3; `vital_rev` and `kb_rom_rev1` are GPL-3.0-only. They're compatible, but they'd pin the combined binary to GPL-3.0-only, so avoid them to keep "-or-later" clean.
- Practical recommendation: emit Faust C or Java for a reverb, hand-translate it to Kotlin (the generated code is flat and loop-based, so it translates mechanically), and use Faust's own output as a test oracle: render the impulse response from both and diff it. That gives a real expected value for synth tests instead of characterizing the app's own output.

### Tone.js (MIT), https://github.com/Tonejs/Tone.js
API design to imitate: `Transport` + `scheduleRepeat`/`scheduleOnce` on musical time, `PolySynth`, `FMSynth`, `FeedbackDelay`, `Freeverb`/`JCReverb`, `Chorus`, `AutoFilter`. Generative.fm pieces are a large worked catalog against this API, so mirroring it makes porting their logic trivial.

---

## 4. Ambient visual prior art

- Hatnote circles (BSD-3, above): seeded stable positions, sqrt-area radius, 60 s fade, expanding ring per event, background tint by event rate. Directly the app's packet ripples and node orbs.
- Bloom: expanding, fading circles, kept simple at Eno's request; the visual and the note are the same event.
- 77 Million Paintings: up to 4 layers crossfading slowly (design section 2).
- Electric Sheep / fractal flames: `scottdraves/flam3` is GPL-3.0 (https://github.com/scottdraves/flam3), so the algorithm and code are portable. The Electric Sheep client is GPL-2 (readme), so ideas only for that part. Flames are GPU-heavy and better as an optional SkSL mode.
- Flow fields (Tyler Hobbs, https://tylerxhobbs.com/words/flow-fields): a grid of angles from noise, particles traced through it. Cheap in Compose Canvas with ~1-3k particles drawing short fading segments, and a natural home for "packet trails" traveling between orbs. Drive the noise time or scale with weather (wind → flow speed).
- Reaction-diffusion (Karl Sims tutorial, https://www.karlsims.com/rd.html): Gray-Scott with feed and kill parameters (e.g. f=0.0367, k=0.0649); very sensitive near f≈0.062, k≈0.063. Needs ping-pong render targets. Skia runtime shaders can't feed back into themselves without an offscreen surface each frame, so on Skiko it means rendering to an `Image` per step. Feasible on desktop, heavy on wasm and mobile. Treat it as optional.
- Ryoji Ikeda / Refik Anadol: Ikeda's data-as-grid, high-contrast aesthetic suits a "data mode"; Anadol's fluid point clouds are expensive. Neither has code to take; they're art direction only.
- Shader sources: licensing trap. Shadertoy's default license for shaders is CC BY-NC-SA 3.0 (per community reports citing shadertoy.com/terms), which is not GPL-compatible. Don't paste Shadertoy code. CC0 sources exist, e.g. The Library of Shaders (https://the-library-of-shaders.github.io). Write the noise in the repository (simplex/value noise in SkSL is ~30 lines).
- SkSL in CMP: see design point 4. SkSL and AGSL are near-identical (`half4 main(float2 coord)`, `uniform float iTime`), so one shader string can serve both with a thin `expect`/`actual` brush factory.
- Burn-in / image retention: LCD panels (and LCD projectors) can show temporary retention from static content. DLP and laser projectors are largely immune; OLED TVs are the real risk if someone uses one instead of a projector (BenQ FAQ, Rtings, https://www.rtings.com/tv/learn/what-is-image-retention). Mitigations: no static UI; move any clock or label slowly (orbit a few px per minute); drift the whole scene and camera very slowly; keep average brightness low with dark backgrounds; periodic full-field changes (dawn and dusk gradient). Night mode doubles as an energy and retention saver.

---

## 5. Kotlin / KMP specifics

### Sinks
- kc-audio-bridge (Phil Burk, the author of JSyn and co-author of Oboe), Apache-2.0, https://github.com/philburk/kc-audio-bridge. A Compose Multiplatform library with a blocking `write(FloatArray, offsetFrames, numFrames)` API (plus a suspend extension), stereo float, and a device query flow.
  - Android: `AudioTrack` `MODE_STREAM`, `PERFORMANCE_MODE_LOW_LATENCY`, `WRITE_BLOCKING`, float.
  - Desktop JVM: `javax.sound.sampled.SourceDataLine`, with mixer and device selection.
  - Wasm: AudioWorklet + SharedArrayBuffer FIFO, `Atomics` read/write indices (`kcab-output-stream.js`, `kcab-webaudio.js`); needs cross-origin isolation.
  - iOS: a stub (`StubAudioOutputBridge` that discards samples). The iOS actual has to be written in the repository.
  - Recommendation: depend on it, or vendor its Android/JVM/Wasm actuals (Apache-2.0 is compatible), and add an iOS actual using `AVAudioEngine` + `AVAudioPlayerNode.scheduleBuffer` from a dedicated thread (campfire pattern). For ambient, use a larger `framesPerBuffer` and, on Android, consider `PERFORMANCE_MODE_POWER_SAVING` (deep buffer) instead of low-latency, which is gentler on battery for an always-on device. That last point comes from Android's AudioTrack docs, not tested here.
- Klarinet, Apache-2.0, https://github.com/vectencia/Klarinet: Oboe on Android, AVAudioEngine (`AVAudioSourceNode`) on Apple, Rust DSP core. A native-dependency-heavy alternative; useful as a reference for the Kotlin/Native `AudioBufferList` pointer handling (`outputData?.pointed?.mBuffers?.pointed?.mData?.reinterpret<FloatVar>()`).
- audio-stream-player-kmp, MIT, https://github.com/adrianczuczka/audio-stream-player-kmp: raw PCM streaming via AudioTrack and AVAudioEngine + `AVAudioPlayerNode`, Android/iOS/macOS. Another iOS reference.
- PatchCore (https://github.com/SillyDevices/PatchCore): a KMP modular-synth library; no license detected by GitHub, so check it before any reuse.

### Pitfalls
- Kotlin/Native GC on a real-time thread produces gaps (campfire comment; Kotlin Slack reports of GC bursts in a K/N VST3 plugin). Mitigations: preallocate everything, render on a normal-priority thread into a ring buffer, keep big buffers.
- In Kotlin/Wasm, synthesis on the main thread shares time with Compose rendering and GC. Keep the per-block render allocation-free and the FIFO deep (≥250 ms). Moving synthesis into a Worker or the AudioWorklet itself is unproven for Kotlin/Wasm today.
- Web autoplay and COOP/COEP: design point 3.
- Android RuntimeShader API 33: design point 4.
- Seeded RNG: use one common `kotlin.random.Random(seed)` per subsystem so a session can be reproduced from a seed (useful for debugging "that sounded wrong at 3am").

---

## Sources (primary unless noted)
- Listen to Wikipedia: https://github.com/hatnote/listen-to-wikipedia (LICENSE, static/js/app.js, static/index.html)
- BitListen: https://github.com/MaxLaumeister/bitlisten
- Peep: https://www.usenix.org/legacy/events/lisa2000/gilfix/gilfix_html/index.html ; https://sourceforge.net/projects/peep/
- Vickers et al.: https://arxiv.org/abs/1409.5282 ; CyLab: https://cylab.cmu.edu/news/2021/07/30-sonification.html ; Sonification Handbook: https://sonification.de/handbook/
- Roddy: https://www.stephenroddy.com/projects/s2nl/s2nl_project
- iart: https://iart.ch/en/work/190-la-salle-street ; Idee und Klang: https://ideeundklang.com/audio/en/work/media-design/La-Salle , https://ideeundklang.com/audio/en/ideas/research/generatives_audiodesign
- Parviainen: https://teropa.info/blog/2016/07/28/javascript-systems-music ; https://github.com/teropa/musicforairports.js ; https://github.com/teropa/in-c
- Generative.fm: https://github.com/generativefm/generators ; https://github.com/generativefm/generative.fm ; https://medium.com/@metalex9/making-generative-music-in-the-browser-bfb552a26b0b
- Bloom study: https://scalar.usc.edu/works/bloom-hd-app-preservation/code-study ; Reflection: https://www.stuff.tv/review/app-of-the-week-brian-eno-reflection-review ; 77MP: https://en.wikipedia.org/wiki/77_Million_Paintings
- Endel: https://endel.io/technology ; Koan: https://intermorphic.com/archive/sseyo/koan/
- In C: https://ccrma.stanford.edu/~cc/vox/InC.pdf ; Toussaint: https://cgm.cs.mcgill.ca/~godfried/publications/banff.pdf
- Tidal: https://github.com/tidalcycles/Tidal ; Strudel: https://codeberg.org/uzu/strudel ; Tone.js: https://github.com/Tonejs/Tone.js
- Mutable Instruments: https://github.com/pichenettes/eurorack ; https://github.com/pichenettes/stmlib
- Faust: https://github.com/grame-cncm/faust ; https://github.com/grame-cncm/faustlibraries (reverbs.lib)
- Freeverb: https://github.com/sinshu/freeverb ; Dattorro: https://ccrma.stanford.edu/~dattorro/EffectDesignPart1.pdf ; https://github.com/el-visio/dattorro-verb
- Signalsmith: https://signalsmith-audio.co.uk/writing/2021/lets-write-a-reverb/ ; https://github.com/Signalsmith-Audio/dsp
- Airwindows: https://github.com/airwindows/airwindows ; JSyn: https://github.com/philburk/jsyn ; TarsosDSP: https://github.com/JorenSix/TarsosDSP ; Minim: https://github.com/ddf/Minim ; Beads: https://github.com/orsjb/beads ; SuperCollider: https://github.com/supercollider/supercollider ; Pd: https://github.com/pure-data/pure-data
- flam3: https://github.com/scottdraves/flam3 ; Electric Sheep: https://github.com/scottdraves/electricsheep
- Hobbs: https://tylerxhobbs.com/words/flow-fields ; Sims: https://www.karlsims.com/rd.html
- Shaders in CMP: https://github.com/Kashif-E/KMPLiquidGlass ; https://www.pushing-pixels.org/2021/09/22/skia-shaders-in-compose-desktop.html
- Retention: https://www.rtings.com/tv/learn/what-is-image-retention ; Shadertoy license (secondhand): https://magicmusicvisuals.com/forums/viewtopic.php?p=3529 ; CC0 shaders: https://the-library-of-shaders.github.io
- Audio sinks: https://github.com/philburk/kc-audio-bridge ; https://github.com/vectencia/Klarinet ; https://github.com/adrianczuczka/audio-stream-player-kmp ; https://github.com/pandulapeter/campfire
- Scheduling: https://web.dev/articles/audio-scheduling ; cross-origin isolation: https://bugzilla.mozilla.org/show_bug.cgi?id=1630877 ; AudioWorklet patterns: https://developer.chrome.com/blog/audio-worklet-design-pattern

## Not verified
- BitListen's scale (pentatonic or not) and Bloom's exact loop and fade mechanics: no primary source found.
- Kotlin/Wasm running inside AudioWorkletGlobalScope: unproven.
- Skiko `RuntimeEffect` running on wasmJs: KMPLiquidGlass compiles it there, but it was not run.
- Shadertoy default license: from community posts citing shadertoy.com/terms; the terms page itself wasn't fetched.
- Endel patent: claimed, not located.
