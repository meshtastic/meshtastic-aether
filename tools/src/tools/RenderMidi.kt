package org.meshtastic.aether.tools

import org.meshtastic.aether.midi.MidiConfig
import org.meshtastic.aether.midi.MidiLayout
import org.meshtastic.aether.midi.MidiMapper
import org.meshtastic.aether.midi.MidiStats
import org.meshtastic.aether.midi.StandardMidiFile
import org.meshtastic.aether.music.Composer
import org.meshtastic.aether.music.ComposerOutput

private const val MIDI_SAMPLE_RATE = 48_000
private const val MIDI_BLOCK = 512
private const val MIDI_LOOKAHEAD = 12_000L
private const val MILLIS_PER_SECOND = 1000L

data class MidiSpec(
    val seconds: Double,
    val nodes: Int = 150,
    val speed: Double = 30.0,
    val seed: Long = 1L,
    val startEpochMs: Long = START_EPOCH_MS,
    val config: MidiConfig = MidiConfig(),
)

class MidiRender(val bytes: ByteArray, val stats: MidiStats)

private val MIDI_FLAGS = setOf("nodes", "speed", "seed", "at", "range", "layout", "allowance")
private val LAYOUTS = mapOf("one" to MidiLayout.OnePart, "two" to MidiLayout.BedAndOrnaments)

/** `midi <seconds> <out.mid>` followed by `--name value` flags; see [USAGE]. */
internal fun parseMidiArgs(all: List<String>): Command.Midi {
    val (flags, positional) = splitFlags(all, MIDI_FLAGS)
    require(positional.size == 2) { USAGE }
    val seconds = positional[0].toDoubleOrNull()
    require(seconds != null && seconds > 0.0 && seconds.isFinite()) {
        "seconds must be a positive number: '${positional[0]}'"
    }
    val range = flags["range"]?.toIntOrNull() ?: MidiConfig.MPE_BEND_RANGE
    require(range == MidiConfig.MPE_BEND_RANGE || range == MidiConfig.PLAIN_BEND_RANGE) { "--range is 48 or 2" }
    val layout = requireNotNull(LAYOUTS[flags["layout"] ?: "one"]) { "--layout is one or two" }
    val defaults = MidiSpec(seconds)
    val seed = flags["seed"]?.let { requireNotNull(it.toLongOrNull()) { "--seed must be an integer" } }
    val config = MidiConfig(
        bendRange = range,
        layout = layout,
        releaseAllowanceSeconds = flags.number("allowance", defaults.config.releaseAllowanceSeconds),
    )
    val spec = defaults.copy(
        nodes = flags.number("nodes", defaults.nodes.toDouble()).toInt().coerceIn(0, MAX_VIDEO_NODES),
        speed = flags.number("speed", defaults.speed),
        seed = seed ?: defaults.seed,
        startEpochMs = startEpochMs(all, if ("at" in flags) all.indexOf("--at") else -1),
        config = config,
    )
    require(spec.speed > 0.0) { "--speed must be above zero" }
    return Command.Midi(spec, positional[1])
}

/**
 * The composer's output for [spec] as a Standard MIDI File, without the synth. The mapper drains a dispatch horizon
 * behind the composer, so every note has arrived before its bend is due, as a live output's latency allows.
 */
fun renderMidi(spec: MidiSpec, onOutput: (ComposerOutput) -> Unit = {}): MidiRender {
    val rate = MIDI_SAMPLE_RATE
    val frames = (spec.seconds * rate).toLong()
    val durationMs = (spec.seconds * MILLIS_PER_SECOND).toLong()
    val events = generateEvents(spec.nodes, spec.speed, spec.seed, durationMs, spec.startEpochMs)
    val composer = Composer(spec.seed, rate)
    val mapper = MidiMapper(rate, spec.config)
    val smf = StandardMidiFile(rate, spec.config.parts)
    val behind = (spec.config.dispatchAheadSeconds * rate).toLong()
    var next = 0
    var done = 0L
    while (done < frames) {
        val epoch = spec.startEpochMs + done * MILLIS_PER_SECOND / rate
        while (next < events.size && events[next].atMillis <= epoch) composer.onEvent(events[next++])
        val out = composer.advance(done, MIDI_LOOKAHEAD, momentAt(epoch))
        onOutput(out)
        mapper.schedule(out)
        mapper.drain(done - behind, smf::add)
        done += MIDI_BLOCK
    }
    mapper.close(done - behind, smf::add)
    return MidiRender(smf.bytes(), mapper.stats)
}

fun formatMidiStats(s: MidiStats): String =
    "notes=${s.notes} skipped=${s.skipped} carried=${s.carried} restruck=${s.restruck} held=${s.held} " +
        "retriggered=${s.retriggered} fallbacks=${s.fallbacks} maxFallbackCents=${"%.1f".format(s.maxFallbackCents)} " +
        "late=${s.late} shifted=${s.shifted}"
