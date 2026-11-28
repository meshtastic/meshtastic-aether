package org.meshtastic.aether

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.meshtastic.aether.audio.AudioSink
import org.meshtastic.aether.audio.BlockRenderer
import org.meshtastic.aether.audio.FrameTime
import org.meshtastic.aether.mesh.LinkState
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.MeshSource
import org.meshtastic.aether.ui.AmbientEngine
import org.meshtastic.aether.ui.EngineTiming
import org.meshtastic.aether.ui.FADE_IN_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHostTest {
    private class FakeSink : AudioSink {
        override val sampleRate = 48_000
        var stopped = false

        override fun start(renderer: BlockRenderer) = Unit

        override fun stop() {
            stopped = true
        }

        override fun playbackFrame(): Long = 0L

        override fun playbackTimestamp() = FrameTime(0L, 0L)
    }

    private class FlowSource(override val events: Flow<MeshEvent>) : MeshSource

    private class Played(val session: Session, val engine: AmbientEngine, val sink: FakeSink) {
        var closed = false
    }

    private val base = 1_700_000_000_000L

    private fun TestScope.host() = SessionHost(backgroundScope) { base + currentTime }

    private fun TestScope.played(): Played {
        val sink = FakeSink()
        val source = FlowSource(flow { emit(MeshEvent.Link(LinkState.Simulated, base)); awaitCancellation() })
        val engine = AmbientEngine(
            source, sink, seed = 11L,
            timing = EngineTiming(400, now = { base + currentTime }, composerContext = StandardTestDispatcher(testScheduler)),
        )
        lateinit var played: Played
        played = Played(Session(source, close = { played.closed = true }), engine, sink)
        return played
    }

    // Regression: a session ended from the system controls with no window open left its link open and its sink playing.
    @Test
    fun `a stop closes the session's link and stops its sound with no window`() = runTest {
        val host = host()
        val a = played()
        host.start(a.session, a.engine)
        advanceTimeBy(1_000)
        host.stop()
        runCurrent()
        assertTrue(a.closed)
        assertTrue(a.sink.stopped)
        assertFalse(host.isRunning)
    }

    // Regression: starting a session while one ran kept both links open.
    @Test
    fun `starting a session ends the one already running`() = runTest {
        val host = host()
        val a = played()
        val b = played()
        host.start(a.session, a.engine)
        advanceTimeBy(1_000)
        host.start(b.session, b.engine)
        runCurrent()
        assertTrue(a.closed)
        assertTrue(a.sink.stopped)
        assertFalse(b.closed)
        assertSame(b.session, host.running?.session)
    }

    // Regression: the fade-in lived in the window, so a window closed during it left the session part-way up for good.
    @Test
    fun `the fade-in reaches the viewer's volume with no window`() = runTest {
        val host = host()
        val a = played()
        host.start(a.session, a.engine)
        host.setVolume(0.8f)
        advanceTimeBy(FADE_IN_MILLIS / 2)
        assertTrue(a.engine.volume > 0f && a.engine.volume < 0.8f)
        advanceTimeBy(FADE_IN_MILLIS)
        assertEquals(0.8f, a.engine.volume)
    }

    // Regression: a pause from the system controls must silence the session and a resume bring back the viewer's volume.
    @Test
    fun `a pause silences the session and a resume restores the viewer's volume`() = runTest {
        val host = host()
        val a = played()
        host.start(a.session, a.engine)
        host.setVolume(0.6f)
        advanceTimeBy(FADE_IN_MILLIS + 100)
        host.pause(true)
        assertEquals(0f, a.engine.volume)
        host.setVolume(0.9f)
        assertEquals(0f, a.engine.volume)
        host.pause(false)
        assertEquals(0.9f, a.engine.volume)
    }

    // Regression: a pause left from one session made the next one start silent.
    @Test
    fun `a pause ends with its session`() = runTest {
        val host = host()
        val a = played()
        host.start(a.session, a.engine)
        host.setVolume(0.5f)
        host.pause(true)
        host.stop()
        assertFalse(host.paused)
        assertNull(host.running)
        val b = played()
        host.start(b.session, b.engine)
        advanceTimeBy(FADE_IN_MILLIS + 100)
        assertEquals(0.5f, b.engine.volume)
    }
}
