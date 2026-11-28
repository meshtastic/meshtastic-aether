package org.meshtastic.aether.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.aether.audio.AudioSink
import org.meshtastic.aether.audio.BlockRenderer
import org.meshtastic.aether.audio.FrameTime
import org.meshtastic.aether.midi.FakeMidiOutput
import org.meshtastic.aether.midi.MidiLiveOutput
import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mood.ParamValues
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.MeshSource
import org.meshtastic.aether.mesh.PacketKind
import org.meshtastic.sdk.MeshtasticException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Holds work until [runAll]: a composer on it starts only when the test says. */
private class HeldDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
    private val held = ArrayList<Runnable>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        held += block
    }

    fun runAll() {
        while (held.isNotEmpty()) held.removeAt(0).run()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientEngineTest {
    private class FakeSink : AudioSink {
        override val sampleRate = 48_000
        var renderer: BlockRenderer? = null
        var frame = 0L
        var stopped = false

        override fun start(renderer: BlockRenderer) {
            this.renderer = renderer
        }

        override fun stop() {
            stopped = true
        }

        override fun playbackFrame(): Long = frame

        override fun playbackTimestamp() = FrameTime(frame, frame * 1_000_000_000L / sampleRate)
    }

    private class FlowSource(private val flow: Flow<MeshEvent>) : MeshSource {
        override val events: Flow<MeshEvent> = flow
    }

    private val base = 1_700_000_000_000L

    private fun TestScope.engine(sink: FakeSink, source: Flow<MeshEvent>) = AmbientEngine(
        FlowSource(source), sink, seed = 11L,
        timing = EngineTiming(
            lookaheadMillis = 400,
            now = { base + currentTime },
            composerContext = StandardTestDispatcher(testScheduler),
        ),
    )

    // Stopping the engine mid-piece must not leave a held pad or a ringing bell on an external synth.
    @Test
    fun `stopping the engine closes its midi output with nothing left sounding`() = runTest {
        val sink = FakeSink()
        val port = FakeMidiOutput()
        val e = AmbientEngine(
            FlowSource(flow { emit(MeshEvent.Link(LinkState.Simulated, base)); awaitCancellation() }), sink, seed = 11L,
            timing = EngineTiming(400, now = { base + currentTime }, composerContext = StandardTestDispatcher(testScheduler)),
            EngineOptions(midi = { MidiLiveOutput(port, it) }),
        )
        val job = launch { e.run() }
        val block = FloatArray(2 * 4_800)
        val driver = launch {
            while (true) {
                delay(100)
                sink.renderer?.render(block, 4_800)
                sink.frame += 4_800
                port.now = sink.frame * 1_000_000_000L / sink.sampleRate
                port.deliver()
            }
        }
        advanceTimeBy(45_000)
        assertTrue(port.sounding.isNotEmpty(), "nothing was sounding when the engine stopped")
        driver.cancel()
        job.cancelAndJoin()
        assertTrue(port.closed)
        assertTrue(port.sounding.isEmpty(), "still sounding: ${port.sounding}")
    }

    // An engine stopped before its composer first runs (a session left at once) must still release the port it opened.
    @Test
    fun `an engine stopped before its composer starts still closes its midi output`() = runTest {
        val port = FakeMidiOutput()
        val composer = HeldDispatcher()
        val e = AmbientEngine(
            FlowSource(flow { awaitCancellation() }), FakeSink(), 11L,
            EngineTiming(400, now = { base + currentTime }, composerContext = composer),
            EngineOptions(midi = { MidiLiveOutput(port, it) }),
        )
        val job = launch { e.run() }
        runCurrent()
        job.cancel()
        composer.runAll()
        job.join()
        assertTrue(port.closed)
    }

    // Regression: the engine used to leave the scene always "linked", so a lost link never desaturated the field.
    @Test
    fun `the scene is unlinked while connecting and lost and linked while connected`() = runTest {
        val link = MutableSharedFlow<MeshEvent>()
        val e = engine(FakeSink(), link)
        assertFalse(e.scene.linked, "starts unlinked: connecting")
        val job = launch { e.run() }
        runCurrent()
        link.emit(MeshEvent.Link(LinkState.Connected, base))
        runCurrent()
        assertTrue(e.scene.linked)
        assertEquals(LinkState.Connected, e.status.link)
        link.emit(MeshEvent.Link(LinkState.Lost, base + 1))
        runCurrent()
        assertFalse(e.scene.linked)
        assertEquals(LinkState.Lost, e.status.link)
        link.emit(MeshEvent.Link(LinkState.Simulated, base + 2))
        runCurrent()
        assertTrue(e.scene.linked, "the demo link is a link")
        job.cancel()
    }

    // Regression: notes the composer scheduled never reached the scene, so only packets drew.
    @Test
    fun `notes the composer schedules for a node pulse that node's orb`() = runTest {
        val nodes = listOf(0x0A000101L, 0x0A000202L, 0x0A000303L)
        val source = flow {
            emit(MeshEvent.Link(LinkState.Connected, base))
            emit(MeshEvent.Self(0x0A0000FFL, "Home", GeoPoint(45.0, -93.0), base))
            nodes.forEachIndexed { i, n ->
                emit(MeshEvent.NodeSeen(n, "N$i", GeoPoint(45.0 + 0.002 * i, -93.0 + 0.003 * i), true, base))
            }
            var k = 0
            while (true) {
                delay(1_000)
                emit(MeshEvent.PacketHeard(nodes[k % 3], PacketKind.Text, 3f, -90, 1, false, base + currentTime))
                k++
            }
        }
        val sink = FakeSink()
        val e = engine(sink, source)
        val job = launch { e.run() }
        val block = FloatArray(2 * 4_800)
        val driver = launch {
            while (true) {
                delay(100)
                sink.renderer?.render(block, 4_800)
                sink.frame += 4_800
                e.scene.update(sink.frame)
            }
        }
        advanceTimeBy(90_000)
        driver.cancel()
        job.cancelAndJoin()
        val pulsed = e.scene.orbs.filter { it.notePulseFrame != Long.MIN_VALUE }
        assertTrue(pulsed.isNotEmpty(), "some orb pulsed from a note")
        assertTrue(pulsed.all { it.node in nodes })
        assertEquals(3, e.status.nodes)
        assertTrue(e.status.section.isNotEmpty() && e.status.key.isNotEmpty(), "status carries the composer's section and key")
        assertTrue(sink.stopped)
    }

    // Regression: demo mode pinned the composer's form but the status label read a second unpinned Form.
    @Test
    fun `a pinned form reports a full section`() = runTest {
        val e = AmbientEngine(
            FlowSource(flow { awaitCancellation() }), FakeSink(), 11L,
            EngineTiming(400, now = { base + currentTime }, composerContext = StandardTestDispatcher(testScheduler)),
            EngineOptions(pinFormFull = true),
        )
        val job = launch { e.run() }
        runCurrent()
        advanceTimeBy(500)
        job.cancel()
        assertEquals("1 - full", e.status.section)
    }

    // Retry theory: 2 s then 4 s between attempts.
    @Test
    fun `a failing open shows the error and retries after two then four seconds`() = runTest {
        var attempts = 0
        val e = engine(FakeSink(), flow { awaitCancellation() })
        val job = launch {
            e.run {
                attempts++
                if (attempts < 3) throw MeshtasticException.Transport("boom")
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        assertEquals("Can't open the link - retrying", e.status.error)
        advanceTimeBy(1_999)
        assertEquals(1, attempts)
        advanceTimeBy(2)
        assertEquals(2, attempts)
        advanceTimeBy(3_900)
        assertEquals(2, attempts)
        advanceTimeBy(200)
        assertEquals(3, attempts)
        assertNull(e.status.error, "a successful retry clears the error")
        job.cancel()
    }

    // The person sees the target and a retry, never the exception text, and the open callback's own text is used.
    @Test
    fun `a failing open shows the sessions own text and not the exception message`() = runTest {
        val e = AmbientEngine(
            FlowSource(flow { awaitCancellation() }), FakeSink(), 11L,
            EngineTiming(400, now = { base + currentTime }, composerContext = StandardTestDispatcher(testScheduler)),
            EngineOptions(failureText = { unreachableText("node.local:4403") }),
        )
        val refused = java.net.ConnectException("Connection refused: no route")
        val job = launch { e.run { throw MeshtasticException.Transport("TCP connect failed: ${refused.message}", refused) } }
        runCurrent()
        assertEquals("Can't reach node.local:4403 - retrying", e.status.error)
        job.cancel()
    }

    // Regression: the master gain started at 1.0, so the first blocks played at full before the app set a volume.
    @Test
    fun `an engine is silent until the app sets a volume`() = runTest {
        assertEquals(0f, engine(FakeSink(), flow { awaitCancellation() }).volume)
    }

    @Test
    fun `a lost link is reopened with backoff until it connects again`() = runTest {
        val link = MutableSharedFlow<MeshEvent>()
        var opens = 0
        val e = engine(FakeSink(), link)
        val job = launch { e.run { opens++ } }
        runCurrent()
        link.emit(MeshEvent.Link(LinkState.Connected, base))
        runCurrent()
        link.emit(MeshEvent.Link(LinkState.Lost, base + 1))
        runCurrent()
        assertEquals(1, opens)
        advanceTimeBy(2_100)
        assertEquals(2, opens)
        advanceTimeBy(4_100)
        assertEquals(3, opens)
        link.emit(MeshEvent.Link(LinkState.Connected, base + 2))
        runCurrent()
        advanceTimeBy(120_000)
        assertEquals(3, opens, "no reopen once connected")
        job.cancel()
    }

    // Regression: the engine computed the composer's params but never gave them to the scene or the overlay.
    @Test
    fun `the engine hands the mood's parameters to the scene and the overlay`() = runTest {
        val source = flow {
            emit(MeshEvent.Link(LinkState.Connected, base))
            emit(MeshEvent.Self(0x0A0000FFL, "Home", GeoPoint(45.0, -93.0), base))
            while (true) {
                delay(2_000)
                emit(MeshEvent.PacketHeard(0x0A000101L, PacketKind.Position, 3f, -90, 1, false, base + currentTime))
            }
        }
        val e = engine(FakeSink(), source)
        val job = launch { e.run() }
        runCurrent()
        advanceTimeBy(300)
        assertEquals(ParamValues.NEUTRAL, e.scene.params, "no packets yet: neutral")
        advanceTimeBy(30_000)
        job.cancel()
        assertEquals("activity", e.status.params.drivers.rotationSpeed, "overlay row names its driver")
        assertEquals(e.status.params, e.scene.params, "scene and overlay see the same values")
    }
}
