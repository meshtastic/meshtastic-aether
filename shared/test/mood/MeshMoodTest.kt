package org.meshtastic.ambient.mood

import kotlin.math.abs
import kotlin.math.ln
import org.meshtastic.ambient.mesh.LinkState
import org.meshtastic.ambient.mesh.MeshEvent
import org.meshtastic.ambient.mesh.PacketKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeshMoodTest {
    @Test
    fun `a mood that heard nothing holds every neutral at zero confidence`() {
        val s = MeshMood().advance(5 * HOUR)
        assertTrue(s.allFinite())
        assertEquals(0.0, s.activityConfidence)
        assertEquals(0.0, s.reachConfidence)
        assertEquals(0.0, s.linkQualityConfidence)
        assertEquals(0.0, s.sendersConfidence)
        assertEquals(0.0, s.pressureTrendConfidence)
        assertEquals(ParamValues.NEUTRAL, s.params)
        assertEquals(RotationTarget.NEUTRAL.copy(directionStrength = 0.0), s.rotation.copy(directionStrength = 0.0))
        assertEquals(600.0, s.rotation.targetPeriodSeconds)
    }

    @Test
    fun `activity at the top of its own range asks for the busy speed`() {
        val sim = Sim()
        sim.run(12 * 60, 1.0)
        val s = sim.run(30, 20.0)
        assertEquals(1.0, s.activity, 1e-9)
        assertEquals(1.0, s.activityConfidence, 1e-9)
        assertEquals(150.0, s.rotation.targetPeriodSeconds, 1e-6)
    }

    @Test
    fun `activity at the bottom of its own range asks for the quiet speed`() {
        val sim = Sim()
        sim.run(12 * 60, 1.0)
        sim.run(30, 20.0)
        val s = sim.run(3 * 60, 0.0)
        assertEquals(0.0, s.activity, 1e-9)
        assertEquals(1200.0, s.rotation.targetPeriodSeconds, 1e-6)
    }

    @Test
    fun `a quiet hour after a busy day reads low`() {
        val sim = Sim()
        sim.run(23 * 60, 12.0)
        val s = sim.run(60, 0.3)
        assertTrue(s.activity < 0.2, "activity ${s.activity}")
    }

    @Test
    fun `a modest hour after a quiet day reads high instead of staying at the absolute mapping`() {
        val sim = Sim()
        sim.run(23 * 60, 0.3)
        val s = sim.run(60, 3.0)
        assertTrue(s.activity > 0.9, "activity ${s.activity}")
    }

    @Test
    fun `confidence ramps with the number of packets`() {
        val one = MeshMood()
        one.onEvent(packet(1, 1000))
        assertEquals(0.1, one.advance(1000).activityConfidence, 1e-9)
        val ten = MeshMood()
        repeat(10) { ten.onEvent(packet(1L + it, 1000L + it)) }
        assertEquals(1.0, ten.advance(2000).activityConfidence, 1e-9)
    }

    @Test
    fun `sources that need fresh samples lose confidence when the mesh goes quiet`() {
        val sim = Sim()
        val busy = sim.run(30, 5.0)
        assertEquals(1.0, busy.reachConfidence, 1e-9)
        assertEquals(1.0, busy.linkQualityConfidence, 1e-9)
        val quiet = sim.run(3 * 60, 0.0)
        assertTrue(quiet.reachConfidence < 0.01)
        assertTrue(quiet.linkQualityConfidence < 0.01)
        assertEquals(0.0, quiet.sendersConfidence)
    }

    @Test
    fun `silence follows the log of the time since the last packet from 10 seconds to 10 minutes`() {
        val m = MeshMood()
        repeat(3) { m.onEvent(packet(1L + it, 0L)) }
        assertEquals(0.0, m.advance(10_000).silence, 1e-9)
        assertEquals(ln(6.0) / ln(60.0), m.advance(60_000).silence, 1e-9)
        assertEquals(1.0, m.advance(600_000).silence, 1e-9)
        assertEquals(1.0, m.advance(3 * HOUR).silence, 1e-9)
    }

    @Test
    fun `the surge from a packet decays with a 20 second time constant`() {
        val m = MeshMood()
        repeat(3) { m.onEvent(packet(1L + it, 0L)) }
        val s0 = m.advance(0).surge
        val s20 = m.advance(20_000).surge
        assertTrue(s0 > 0.0 && s0 < 1.0)
        assertEquals(kotlin.math.exp(-1.0), ln(1 - s20) / ln(1 - s0), 1e-9)
    }

    @Test
    fun `chat counts texts and direct messages and decays with a 10 minute time constant`() {
        val m = MeshMood()
        m.onEvent(packet(1, 0, kind = PacketKind.Position))
        m.onEvent(packet(2, 0, kind = PacketKind.Telemetry))
        assertEquals(0.0, m.advance(0).chat)
        m.onEvent(packet(3, 0, kind = PacketKind.Text))
        m.onEvent(packet(4, 0, kind = PacketKind.Other, toUs = true))
        val c0 = m.advance(0).chat
        val c10 = m.advance(10 * MINUTE).chat
        assertTrue(c0 > 0.0)
        assertEquals(kotlin.math.exp(-1.0), ln(1 - c10) / ln(1 - c0), 1e-9)
    }

    @Test
    fun `senders counts distinct nodes heard in the last 45 minutes`() {
        val m = MeshMood()
        m.onEvent(packet(1, 0))
        m.onEvent(packet(2, 0))
        m.onEvent(packet(1, 4 * MINUTE))
        m.onEvent(packet(3, 30 * MINUTE))
        assertEquals(1.0, m.advance(44 * MINUTE).sendersConfidence, 1e-9)
        assertEquals(1.0 / 3.0, m.advance(50 * MINUTE).sendersConfidence, 1e-9)
        assertEquals(0.0, m.advance(80 * MINUTE).sendersConfidence, 1e-9)
    }

    @Test
    fun `arrival ignores the warm up snapshot and counts each later new sender once`() {
        val m = MeshMood()
        m.onEvent(MeshEvent.Link(LinkState.WarmingUp, 0))
        for (n in 1L..3L) m.onEvent(MeshEvent.NodeSeen(n, null, null, true, n))
        m.onEvent(packet(2, 100))
        m.onEvent(packet(50, 150))
        m.onEvent(MeshEvent.Link(LinkState.Connected, 200))
        assertEquals(0.0, m.advance(200).arrival)

        m.onEvent(packet(1, 300))
        m.onEvent(packet(3, 300))
        assertEquals(0.0, m.advance(300).arrival)

        m.onEvent(MeshEvent.NodeSeen(4, null, null, true, 400))
        m.onEvent(MeshEvent.NodeSeen(4, null, null, true, 400))
        m.onEvent(packet(4, 400))
        assertEquals(1 - kotlin.math.exp(-0.5), m.advance(400).arrival, 1e-9)

        m.onEvent(packet(99, 400))
        assertEquals(1 - kotlin.math.exp(-1.0), m.advance(400).arrival, 1e-9)
    }

    @Test
    fun `reach rises when the mean hop count rises against its own history`() {
        val sim = Sim()
        sim.run(12 * 60, 2.0, hops = 1)
        val s = sim.run(90, 2.0, hops = 5)
        assertEquals(1.0, s.reach, 1e-9)
        val flat = Sim()
        val f = flat.run(24 * 60, 2.0, hops = 3)
        assertEquals(0.5, f.reach, 1e-6)
    }

    @Test
    fun `link quality falls when the mean snr drops against its own history`() {
        val sim = Sim()
        sim.run(12 * 60, 2.0, snr = 8f)
        val s = sim.run(90, 2.0, snr = -12f)
        assertEquals(0.0, s.linkQuality, 1e-9)
    }

    @Test
    fun `packets without hops or snr and single samples never give NaN`() {
        val m = MeshMood()
        m.onEvent(packet(1, 0, snr = null, hops = null))
        assertTrue(m.advance(0).allFinite())
        assertEquals(0.0, m.advance(0).reachConfidence)
        m.onEvent(packet(2, 1000, snr = 3f, hops = 0))
        assertTrue(m.advance(1000).allFinite())
        assertTrue(m.advance(1000 + 400 * HOUR).allFinite())
    }

    @Test
    fun `a clock that runs backwards never moves a signal`() {
        val m = MeshMood()
        repeat(5) { m.onEvent(packet(1L + it, 10 * MINUTE)) }
        val a = m.advance(20 * MINUTE)
        val b = m.advance(5 * MINUTE)
        assertEquals(a, b)
    }

    private fun fallingPressure(m: MeshMood, nodes: List<Long>, perHour: Double, base: Float = 1010f, hours: Double = 3.5) {
        var t = 0L
        while (t <= (hours * HOUR).toLong()) {
            for (n in nodes) m.onEvent(environment(n, t, pressure = (base + perHour * t / HOUR).toFloat()))
            t += 30 * MINUTE
        }
    }

    @Test
    fun `the pressure trend is the median of per node changes over 3 hours`() {
        val m = MeshMood()
        fallingPressure(m, listOf(1, 2, 3), -1.0)
        val s = m.advance((3.5 * HOUR).toLong())
        assertEquals(-0.5, s.pressureTrend, 1e-6)
        assertEquals(1.0, s.pressureTrendConfidence, 1e-9)
        assertEquals(-1, s.rotation.direction)
        assertEquals(0.5, s.rotation.directionStrength, 1e-6)
        assertEquals(9.0, s.rotation.tiltDegrees, 1e-6)
    }

    @Test
    fun `a node reporting a different absolute pressure does not shift the trend`() {
        val m = MeshMood()
        fallingPressure(m, listOf(1, 2, 3), -1.0)
        fallingPressure(m, listOf(4), 0.0, base = 1040f)
        assertEquals(-0.5, m.advance((3.5 * HOUR).toLong()).pressureTrend, 1e-6)
    }

    @Test
    fun `nodes with less than 3 hours of data are left out of the trend`() {
        val m = MeshMood()
        fallingPressure(m, listOf(1, 2), -1.0)
        for (t in 2L..3L) m.onEvent(environment(9, (t * 60 + 30) * MINUTE, pressure = 1030f - t))
        val s = m.advance((3.5 * HOUR).toLong())
        assertEquals(-0.5, s.pressureTrend, 1e-6)
        assertEquals(2.0 / 3.0, s.pressureTrendConfidence, 1e-9)
    }

    @Test
    fun `under 3 hours of pressure data the trend is neutral and tilt stays at the constant`() {
        val m = MeshMood()
        fallingPressure(m, listOf(1, 2, 3), -1.0, hours = 2.5)
        val s = m.advance((2.5 * HOUR).toLong())
        assertEquals(0.0, s.pressureTrendConfidence)
        assertEquals(0.0, s.pressureTrend)
        assertEquals(0.0, s.rotation.tiltDegrees)
        assertNull(s.params.drivers.tilt)
    }

    @Test
    fun `wind sets the wobble between 1 and 6 degrees`() {
        val m = MeshMood()
        m.onEvent(environment(1, 0, wind = 7.5f))
        m.onEvent(environment(2, 0, wind = 7.5f))
        val s = m.advance(MINUTE)
        assertEquals(0.5, s.wind, 1e-9)
        assertEquals(3.5, s.rotation.wobbleDegrees, 1e-9)
        val calm = MeshMood()
        calm.onEvent(environment(1, 0, wind = 0f))
        calm.onEvent(environment(2, 0, wind = 0f))
        assertEquals(1.0, calm.advance(MINUTE).rotation.wobbleDegrees, 1e-9)
    }

    @Test
    fun `without weather a falling activity turns the cloud counter clockwise`() {
        val sim = Sim()
        sim.run(6 * 60, 5.0)
        val s = sim.run(30, 0.5)
        assertTrue(s.activityTrendConfidence > 0.0)
        assertTrue(s.activityTrend < -0.25, "trend ${s.activityTrend}")
        assertEquals(-1, s.rotation.direction)
        assertTrue(s.rotation.directionStrength >= 0.25)
        assertEquals("activity trend", s.params.drivers.rotationDirection)
    }

    @Test
    fun `without weather a rising activity turns the cloud clockwise`() {
        val sim = Sim()
        sim.run(6 * 60, 0.5)
        val s = sim.run(30, 5.0)
        assertTrue(s.activityTrend > 0.25, "trend ${s.activityTrend}")
        assertEquals(1, s.rotation.direction)
    }

    @Test
    fun `without weather tilt follows relative reach and is neutral without hops`() {
        val sim = Sim()
        sim.run(12 * 60, 2.0, hops = 1)
        val far = sim.run(90, 2.0, hops = 5)
        assertEquals(14.0, far.rotation.tiltDegrees, 1e-6)
        assertEquals("reach", far.params.drivers.tilt)
        val none = Sim()
        assertEquals(0.0, none.run(60, 2.0, hops = null).rotation.tiltDegrees)
    }

    @Test
    fun `phrase speed is 1 without data and spans the four tempo bands with the activity range`() {
        assertEquals(1.0, MeshMood().advance(0).params.phraseSpeed)
        val sim = Sim()
        sim.run(12 * 60, 1.0)
        assertEquals(1.5, sim.run(30, 20.0).params.phraseSpeed)
        assertEquals(0.5, sim.run(3 * 60, 0.0).params.phraseSpeed)
    }

    @Test
    fun `mode lean is bright for a link better than usual and dark for worse and zero with no data`() {
        assertEquals(0.0, MeshMood().advance(0).params.modeLean)
        val good = Sim()
        good.run(12 * 60, 2.0, snr = -10f)
        assertEquals(0.5, good.run(90, 2.0, snr = 10f).params.modeLean, 1e-9)
        val bad = Sim()
        bad.run(12 * 60, 2.0, snr = 10f)
        assertEquals(-0.5, bad.run(90, 2.0, snr = -10f).params.modeLean, 1e-9)
    }

    @Test
    fun `warmth alone sets the mode lean`() {
        val m = MeshMood()
        for (h in 0..23) for (n in 1L..2L) m.onEvent(environment(n, h * HOUR, temperature = (h % 12).toFloat()))
        val s = m.advance(23 * HOUR + MINUTE)
        assertEquals(1.0, s.warmthConfidence, 1e-9)
        assertEquals((s.warmth - 0.5), s.params.modeLean, 1e-9)
    }

    @Test
    fun `pentatonic bias opens with distinct senders and closes at the top of activity`() {
        assertEquals(0.0, MeshMood().advance(0).params.scalePentatonicBias)
        val sim = Sim()
        sim.run(12 * 60, 1.0)
        val busy = sim.run(30, 20.0)
        assertEquals(0.0, busy.params.scalePentatonicBias, 1e-9)
        val calm = sim.run(20, 0.0)
        assertTrue(calm.params.scalePentatonicBias > 0.05, "bias ${calm.params.scalePentatonicBias}")
        assertTrue(calm.params.scalePentatonicBias <= 1.0)
    }

    @Test
    fun `an ordinary mesh never gives an out of range value`() {
        val sim = Sim()
        val s = sim.run(3 * 60, 4.0)
        for (v in listOf(s.activity, s.surge, s.silence, s.reach, s.linkQuality, s.senders, s.chat, s.arrival, s.warmth, s.wind)) {
            assertTrue(v in 0.0..1.0)
        }
        assertTrue(abs(s.params.modeLean) <= 1.0)
        assertTrue(s.rotation.tiltDegrees + s.rotation.wobbleDegrees <= 20.0)
    }
}
