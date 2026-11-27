package org.meshtastic.aether.tools

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.meshtastic.aether.audio.AudioSink
import org.meshtastic.aether.audio.BlockRenderer
import org.meshtastic.aether.mesh.SimulatedMeshSource
import org.meshtastic.aether.theme.LocalAmbientPalette
import org.meshtastic.aether.theme.PaletteChoice
import org.meshtastic.aether.ui.AmbientEngine
import org.meshtastic.aether.ui.EngineTiming
import org.meshtastic.aether.synth.wavBytes
import org.meshtastic.aether.visual.AmbientScene
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration

private const val VIDEO_SAMPLE_RATE = 48_000
private const val LOOKAHEAD_MS = 400
private const val MILLIS_PER_SECOND = 1000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val BYTES_PER_PIXEL = 4

/** H.264 in 4:2:0 needs even sides; an odd size loses its last row or column. */
private const val EVEN_CROP = "crop=trunc(iw/2)*2:trunc(ih/2)*2:0:0"

/**
 * An [AudioSink] that plays nothing: the caller pulls each block, and the frames pulled so far are the playback clock
 * the composer and the scene read. With it the real engine runs offline, as fast as the machine allows, with every
 * picture frame on exactly its audio frame.
 */
class OfflineSink(override val sampleRate: Int) : AudioSink {
    private var renderer: BlockRenderer? = null
    private var played = 0L

    val started: Boolean get() = renderer != null

    override fun start(renderer: BlockRenderer) {
        this.renderer = renderer
    }

    override fun stop() {
        renderer = null
    }

    override fun playbackFrame(): Long = played

    /** Renders [frames] into [out] and advances the clock by them; false, with nothing rendered, before [start]. */
    fun pull(out: FloatArray, frames: Int): Boolean {
        val r = renderer ?: return false
        r.render(out, frames)
        played += frames
        return true
    }
}

/** What to render: the simulated mesh, the clock, the picture and how much to skip while the mesh fills in. */
data class VideoSpec(
    val seconds: Double,
    val nodes: Int = 150,
    val speed: Double = 10.0,
    val seed: Long = 1L,
    val startEpochMs: Long = START_EPOCH_MS,
    val fps: Int = 30,
    val width: Int = 1600,
    val height: Int = 1075,
    val warmupSeconds: Double = 45.0,
    val look: PaletteChoice = PaletteChoice.Standard,
) {
    val audioFramesPerPicture: Int get() = VIDEO_SAMPLE_RATE / fps
    val warmupPictures: Int get() = (warmupSeconds * fps).toInt()
    val pictures: Int get() = (seconds * fps).toInt()
}

/** Where a render ended: audio frames pulled and the engine's virtual clock, which must agree. */
data class RenderClock(val audioFrames: Long, val virtualMillis: Long)

/**
 * Runs the app's own [AmbientEngine] on a virtual clock over the simulated mesh and draws [AmbientScene] offscreen
 * at exactly [VideoSpec.fps]. Every audio block goes to [onAudio], from the first frame of the warm-up; each picture
 * after the warm-up goes to [onPicture] as BGRA bytes, top row first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun renderFrames(
    spec: VideoSpec,
    onAudio: (FloatArray, Int) -> Unit,
    onPicture: (ByteArray, Int) -> Unit,
): RenderClock {
    require(VIDEO_SAMPLE_RATE % spec.fps == 0) { "fps must divide $VIDEO_SAMPLE_RATE" }
    var clock = RenderClock(0L, 0L)
    runTest(timeout = Duration.INFINITE) {
        val sink = OfflineSink(VIDEO_SAMPLE_RATE)
        val now = { spec.startEpochMs + currentTime }
        val source = SimulatedMeshSource(spec.seed, spec.nodes, spec.speed, now)
        val timing = EngineTiming(LOOKAHEAD_MS, now = now, composerContext = StandardTestDispatcher(testScheduler))
        val engine = AmbientEngine(source, sink, spec.seed, timing)
        engine.volume = 1f
        backgroundScope.launch { engine.run() }
        while (!sink.started) {
            advanceTimeBy(1)
            runCurrent()
        }
        val scene = ImageComposeScene(spec.width, spec.height, Density(1f)) {
            CompositionLocalProvider(LocalAmbientPalette provides spec.look.palette) {
                AmbientScene(engine, Modifier.fillMaxSize())
            }
        }
        try {
            drive(spec, sink, scene, onAudio, onPicture)
        } finally {
            scene.close()
        }
        clock = RenderClock(sink.playbackFrame(), currentTime)
    }
    return clock
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.drive(
    spec: VideoSpec,
    sink: OfflineSink,
    scene: ImageComposeScene,
    onAudio: (FloatArray, Int) -> Unit,
    onPicture: (ByteArray, Int) -> Unit,
) {
    val block = FloatArray(spec.audioFramesPerPicture * 2)
    val bitmap = Bitmap()
    for (i in 0 until spec.warmupPictures + spec.pictures) {
        sink.pull(block, spec.audioFramesPerPicture)
        onAudio(block, spec.audioFramesPerPicture)
        advanceTimeBy(sink.playbackFrame() * MILLIS_PER_SECOND / VIDEO_SAMPLE_RATE - currentTime)
        runCurrent()
        val nanos = i * NANOS_PER_SECOND / spec.fps
        if (i < spec.warmupPictures) {
            scene.render(nanos).close()
            continue
        }
        scene.render(nanos).use { image ->
            bitmap.allocN32Pixels(image.width, image.height)
            image.readPixels(bitmap)
            val bgra = bitmap.readPixels() ?: ByteArray(image.width * image.height * BYTES_PER_PIXEL)
            onPicture(bgra, i - spec.warmupPictures)
        }
    }
    bitmap.close()
}

/** Renders [spec] to [out] as H.264 and AAC through ffmpeg, which must be on the PATH. */
fun renderVideo(spec: VideoSpec, out: String): Int {
    val wav = File("$out.wav")
    val silent = File("$out.video.mp4")
    val encoder = ProcessBuilder(
        "ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${spec.width}x${spec.height}",
        "-r", spec.fps.toString(), "-i", "-", "-vf", EVEN_CROP, "-c:v", "libx264", "-preset", "slow", "-crf", "22",
        "-pix_fmt", "yuv420p", silent.path,
    ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
    var pictures = 0
    WavStream(wav, VIDEO_SAMPLE_RATE).use { audio ->
        encoder.outputStream.buffered().use { video ->
            renderFrames(spec, onAudio = audio::write) { bgra, _ ->
                video.write(bgra)
                pictures++
            }
        }
    }
    check(encoder.waitFor() == 0) { "ffmpeg failed encoding the pictures" }
    val mux = ProcessBuilder(
        "ffmpeg", "-y", "-v", "error", "-i", silent.path, "-ss", spec.warmupSeconds.toString(), "-i", wav.path,
        "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
        "-movflags", "+faststart", out,
    ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
    check(mux.waitFor() == 0) { "ffmpeg failed muxing the audio" }
    silent.delete()
    wav.delete()
    return pictures
}

/** The canonical 44-byte header [wavBytes] writes, and where its two size fields sit. */
private const val WAV_HEADER = 44
private const val RIFF_SIZE_AT = 4L
private const val DATA_SIZE_AT = 40L
private const val RIFF_SIZE_HEADER_PART = 36
private const val PCM16_BYTES_PER_FRAME = 4

/** A 16-bit stereo WAV written block by block; [close] fills in the sizes. */
internal class WavStream(file: File, private val sampleRate: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw").also {
        it.setLength(0)
        it.write(wavBytes(FloatArray(0), sampleRate))
    }
    private var frames = 0L

    fun write(block: FloatArray, count: Int) {
        val bytes = wavBytes(if (count * 2 == block.size) block else block.copyOf(count * 2), sampleRate)
        raf.write(bytes, WAV_HEADER, count * PCM16_BYTES_PER_FRAME)
        frames += count
    }

    override fun close() {
        val data = frames * PCM16_BYTES_PER_FRAME
        raf.seek(RIFF_SIZE_AT)
        raf.write(littleEndian32(RIFF_SIZE_HEADER_PART + data))
        raf.seek(DATA_SIZE_AT)
        raf.write(littleEndian32(data))
        raf.close()
    }

    private fun littleEndian32(v: Long): ByteArray =
        ByteArray(Int.SIZE_BYTES) { (v ushr (Byte.SIZE_BITS * it)).toByte() }
}

private val LOOKS = mapOf(
    "standard" to PaletteChoice.Standard,
    "projector" to PaletteChoice.Projector,
    "oled" to PaletteChoice.OledNight,
)
private val VIDEO_FLAGS = setOf("nodes", "speed", "seed", "at", "warmup", "fps", "size", "look")

/** The simulator's node limit. */
private const val MAX_VIDEO_NODES = 200

/** The `--name value` flags of [all] (after the command word), and the words that are not flags. */
private fun splitFlags(all: List<String>): Pair<Map<String, String>, List<String>> {
    val flags = HashMap<String, String>()
    val positional = ArrayList<String>()
    var i = 1
    while (i < all.size) {
        val a = all[i]
        if (a.startsWith("--")) {
            require(i + 1 < all.size) { "$a needs a value; $USAGE" }
            flags[a.removePrefix("--")] = all[i + 1]
            i += 2
        } else {
            positional += a
            i++
        }
    }
    val unknown = flags.keys - VIDEO_FLAGS
    require(unknown.isEmpty()) { "unknown flag --${unknown.first()}; $USAGE" }
    return flags to positional
}

private fun Map<String, String>.number(name: String, default: Double): Double {
    val raw = this[name] ?: return default
    val v = raw.toDoubleOrNull()
    require(v != null && v.isFinite() && v >= 0.0) { "--$name must be a non-negative number: '$raw'" }
    return v
}

private fun Map<String, String>.size(): List<Int> {
    val size = (this["size"] ?: "1600x1075").split("x").map { it.toIntOrNull() ?: 0 }
    require(size.size == 2 && size.all { it > 0 }) { "--size takes WxH, like 1600x1075" }
    return size
}

/** `video <seconds> <out.mp4>` followed by `--name value` flags; see [USAGE]. */
internal fun parseVideoArgs(all: List<String>): Command.Video {
    val (flags, positional) = splitFlags(all)
    require(positional.size == 2) { USAGE }
    val seconds = positional[0].toDoubleOrNull()
    require(seconds != null && seconds > 0.0 && seconds.isFinite()) {
        "seconds must be a positive number: '${positional[0]}'"
    }
    val defaults = VideoSpec(seconds)
    val size = flags.size()
    val look = requireNotNull(LOOKS[flags["look"] ?: "standard"]) { "--look is standard, projector or oled" }
    val seed = flags["seed"]?.let { requireNotNull(it.toLongOrNull()) { "--seed must be an integer" } }
    val spec = defaults.copy(
        nodes = flags.number("nodes", defaults.nodes.toDouble()).toInt().coerceIn(0, MAX_VIDEO_NODES),
        speed = flags.number("speed", defaults.speed),
        seed = seed ?: defaults.seed,
        startEpochMs = startEpochMs(all, if ("at" in flags) all.indexOf("--at") else -1),
        fps = flags.number("fps", defaults.fps.toDouble()).toInt(),
        width = size[0],
        height = size[1],
        warmupSeconds = flags.number("warmup", defaults.warmupSeconds),
        look = look,
    )
    require(spec.fps > 0 && VIDEO_SAMPLE_RATE % spec.fps == 0) { "--fps must divide $VIDEO_SAMPLE_RATE" }
    return Command.Video(spec, positional[1])
}
