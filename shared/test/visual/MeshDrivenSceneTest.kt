package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import org.meshtastic.aether.mesh.MeshEvent
import org.meshtastic.aether.mesh.PacketKind
import org.meshtastic.aether.mood.MeshMood
import org.meshtastic.aether.mood.ParamDrivers
import org.meshtastic.aether.mood.ParamValues
import org.meshtastic.aether.mood.RotationIntegrator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The integrated rotation, the phase accumulators and the per-event looks of [SceneModel]; expected values are hand computed. */
class MeshDrivenSceneTest {
    private val sr = 48_000
    private val deg = PI / 180.0

    private fun scene() = SceneModel(sr)

    private fun SceneModel.at(seconds: Double) = update((seconds * sr).toLong())

    /** Steps the scene in [dt] second frames from [from] to [to] seconds, calling [each] after every frame. */
    private fun SceneModel.run(from: Double, to: Double, dt: Double = 1.0, each: (Double) -> Unit = {}) {
        var t = from
        while (t < to - 1e-9) {
            t += dt
            at(t)
            each(t)
        }
    }

    private fun heard(node: Long, kind: PacketKind = PacketKind.Text, rssi: Int? = -80, viaMqtt: Boolean = false, payload: Int? = null, text: Int? = null, channel: Int? = null) =
        MeshEvent.PacketHeard(node, kind, 5f, rssi, 0, viaMqtt, 0, payloadBytes = payload, textLength = text, channel = channel)

    private fun driven(period: Double = 600.0, dir: Int = 1, tilt: Double = 0.0, wobble: Double = 8.0, strength: Double = 1.0) = ParamValues(
        rotationPeriodSeconds = period, rotationDirection = dir, tiltDegrees = tilt, wobbleDegrees = wobble,
        drivers = ParamDrivers(rotationDirection = "pressure trend"), directionStrength = strength,
    )

    // Regression: neutral params must reproduce today's closed form: one turn in 600 s, a 3 degree pitch swing every 90 s (review item 4: neutral wobble 3).
    @Test
    fun `neutral params turn once in ten minutes and swing the pitch three degrees every ninety seconds`() {
        val s = scene()
        s.at(0.0)
        s.run(0.0, 22.5, 0.5)
        assertEquals(3.0 * deg, s.rotation.tiltRadians + s.rotation.wobbleRadians, 1e-9)
        s.run(22.5, 45.0, 0.5)
        assertEquals(0.0, s.rotation.tiltRadians + s.rotation.wobbleRadians, 1e-9)
        s.run(45.0, 67.5, 0.5)
        assertEquals(-3.0 * deg, s.rotation.tiltRadians + s.rotation.wobbleRadians, 1e-9)
        s.run(67.5, 300.0, 0.5)
        assertEquals(PI, s.rotation.yawRadians, 1e-9)
        s.run(300.0, 600.0)
        assertEquals(2 * PI, s.rotation.yawRadians, 1e-9)
    }

    // Regression: a closed-form yaw jumps when its period changes; the integrated one only changes its speed.
    @Test
    fun `yaw stays continuous when the target period changes`() {
        val s = scene()
        s.at(0.0)
        var last = 0.0
        var lastStep = 0.0
        var worst = 0.0
        s.run(0.0, 100.0) { last = s.rotation.yawRadians }
        s.run(100.0, 101.0) { lastStep = s.rotation.yawRadians - last; last = s.rotation.yawRadians }
        s.onParams(driven(period = 150.0))
        s.run(101.0, 300.0) {
            val step = s.rotation.yawRadians - last
            last = s.rotation.yawRadians
            worst = maxOf(worst, abs(step - lastStep))
            lastStep = step
        }
        assertTrue(worst <= RotationIntegrator.MAX_ACCELERATION * 1.0 + 1e-9, "per-second change in step $worst")
        assertTrue(s.rotation.angularVelocity > 2 * PI / 600.0 * 1.5, "it did speed up: ${s.rotation.angularVelocity}")
        assertTrue(s.rotation.angularVelocity <= RotationIntegrator.MAX_ANGULAR_VELOCITY + 1e-12)
    }

    // Design: a flip decelerates through a stop and turns within 30 s; it needs 30 minutes between flips and evidence.
    @Test
    fun `a direction flip passes through a stop and finishes within thirty seconds`() {
        val s = scene()
        s.at(0.0)
        s.run(0.0, 100.0)
        s.onParams(driven(dir = -1))
        var minSpeed = Double.MAX_VALUE
        var sawPositive = false
        var sawNegative = false
        s.run(100.0, 130.0) {
            minSpeed = minOf(minSpeed, abs(s.rotation.angularVelocity))
            if (s.rotation.angularVelocity > 1e-6) sawPositive = true
            if (s.rotation.angularVelocity < -1e-6) sawNegative = true
        }
        assertTrue(sawPositive && sawNegative, "it turned from clockwise to counter-clockwise")
        assertTrue(minSpeed < 1e-9, "it came to a stop on the way: $minSpeed")
        assertEquals(-1, s.rotation.direction)
        assertTrue(s.rotation.angularVelocity < 0.0)
    }

    @Test
    fun `a second flip inside thirty minutes is refused and is allowed after`() {
        val s = scene()
        s.at(0.0)
        s.run(0.0, 100.0)
        s.onParams(driven(dir = -1))
        s.run(100.0, 200.0)
        assertEquals(-1, s.rotation.direction)
        s.onParams(driven(dir = 1))
        s.run(200.0, 1850.0)
        assertEquals(-1, s.rotation.direction, "the first flip finished near 110 s so 1850 s is inside the 30 minutes")
        s.run(1850.0, 2100.0)
        assertEquals(1, s.rotation.direction)
    }

    @Test
    fun `a direction with no driver never flips the cloud`() {
        val s = scene()
        s.onParams(ParamValues(rotationDirection = -1))
        s.at(0.0)
        s.run(0.0, 3600.0, 5.0)
        assertEquals(1, s.rotation.direction)
        assertTrue(s.rotation.angularVelocity > 0.0)
    }

    // Comfort cap: tilt plus wobble at most 20 degrees off level, however large the request.
    private fun pressureMood(perHour: Double): MeshMood {
        val m = MeshMood()
        val hour = 3_600_000L
        var t = 0L
        while (t <= 7 * hour / 2) {
            for (n in 1L..3L) m.onEvent(MeshEvent.Environment(n, null, null, (1010.0 + perHour * t / hour).toFloat(), null, null, t))
            t += hour / 2
        }
        return m
    }

    // Regression: the scene passed strength 1.0 whenever a driver existed, so a 0.2 trend flipped the cloud (review item 2).
    // A pressure fall of 0.4 hPa an hour is a trend of 0.2, below the 0.25 band; 1.0 an hour is 0.5, above it.
    @Test
    fun `a faint pressure fall from the mood never turns the cloud and a clear one does`() {
        val faint = pressureMood(-0.4).advance(7 * 3_600_000L / 2)
        assertEquals(-1, faint.params.rotationDirection)
        assertEquals(0.2, faint.params.directionStrength, 1e-4)
        val a = scene()
        a.onParams(faint.params)
        a.at(0.0)
        a.run(0.0, 600.0)
        assertEquals(1, a.rotation.direction, "inside the hysteresis band it keeps turning clockwise")

        val clear = pressureMood(-1.0).advance(7 * 3_600_000L / 2)
        assertEquals(0.5, clear.params.directionStrength, 1e-6)
        val b = scene()
        b.onParams(clear.params)
        b.at(0.0)
        b.run(0.0, 120.0)
        assertEquals(-1, b.rotation.direction, "a clear fall turns it counter-clockwise")
    }

    @Test
    fun `tilt plus wobble never leave twenty degrees`() {
        val s = scene()
        s.onParams(driven(tilt = 30.0, wobble = 30.0))
        s.at(0.0)
        var worst = 0.0
        s.run(0.0, 3600.0, 2.0) {
            worst = maxOf(worst, abs(s.rotation.tiltRadians + s.rotation.wobbleRadians))
        }
        assertTrue(worst <= 20.0 * deg + 1e-9, "worst ${worst / deg} degrees")
        assertTrue(worst > 15.0 * deg, "it did lean: ${worst / deg}")
    }

    @Test
    fun `the cloud leans to the requested tilt slowly and never snaps`() {
        val s = scene()
        s.onParams(driven(tilt = 14.0, wobble = 2.0))
        s.at(0.0)
        s.run(0.0, 1.0)
        assertTrue(s.rotation.tiltRadians < 0.1 * deg, "after a second ${s.rotation.tiltRadians / deg}")
        s.run(1.0, 1500.0)
        assertEquals(14.0 * deg, s.rotation.tiltRadians, 1e-6)
    }

    // Comfort cap: one turn never faster than 150 s, even under constant packets and an absurd period request.
    @Test
    fun `packets and a fast request never push a turn under one hundred fifty seconds`() {
        val s = scene()
        s.onParams(driven(period = 10.0))
        s.at(0.0)
        var fastest = 0.0
        var t = 0.0
        while (t < 900.0) {
            s.onEvent(heard(1, rssi = null), (t * sr).toLong())
            t += 1.0
            s.at(t)
            fastest = maxOf(fastest, abs(s.rotation.angularVelocity))
        }
        assertTrue(fastest <= 2 * PI / 150.0 + 1e-12, "fastest $fastest")
        assertTrue(fastest > 2 * PI / 160.0, "it did reach the floor: $fastest")
    }

    // Design: each packet adds a decaying push, visible within seconds and gone after minutes.
    @Test
    fun `one packet nudges the turn for a few seconds and then fades`() {
        val quiet = scene()
        val busy = scene()
        busy.onEvent(heard(1, rssi = null), 0)
        quiet.at(0.0)
        busy.at(0.0)
        quiet.run(0.0, 10.0)
        busy.run(0.0, 10.0)
        val extra = busy.rotation.yawRadians - quiet.rotation.yawRadians
        assertTrue(extra in 0.004..0.05, "extra yaw after 10 s $extra")
        quiet.run(10.0, 400.0)
        busy.run(10.0, 400.0)
        assertEquals(quiet.rotation.angularVelocity, busy.rotation.angularVelocity, 1e-6)
    }

    // Regression: sin(2 pi t / period) jumps when the period changes at t=100; an accumulator only changes its rate.
    @Test
    fun `the cloud breath orb breath and drift never jump when their period changes`() {
        val s = scene()
        s.at(0.0)
        s.run(0.0, 100.0, 0.1)
        val cloud = s.cloudBreath
        val orb = s.breath(0x1234)
        val dx = s.driftX(7)
        s.cloudBreathPeriodScale = 2.0
        s.orbBreathPeriodScale = 2.0
        s.driftPeriodScale = 2.0
        s.at(100.1)
        assertTrue(abs(s.cloudBreath - cloud) < 0.001, "cloud ${s.cloudBreath - cloud}")
        assertTrue(abs(s.breath(0x1234) - orb) < 0.01, "orb ${s.breath(0x1234) - orb}")
        assertTrue(abs(s.driftX(7) - dx) < 1e-4, "drift ${s.driftX(7) - dx}")
    }

    @Test
    fun `doubling the period scale makes the cloud breathe over a hundred twenty seconds`() {
        val s = scene()
        s.at(0.0)
        s.run(0.0, 17.0, 0.5)
        s.cloudBreathPeriodScale = 2.0
        s.at(17.5)
        val start = s.cloudBreath
        s.run(17.5, 137.5, 0.5)
        assertEquals(start, s.cloudBreath, 1e-9)
        s.run(137.5, 167.5, 0.5)
        assertTrue(abs(s.cloudBreath - start) > 0.02, "half a period later it is on the other side")
    }

    @Test
    fun `the anchor breath never jumps when its period scale changes`() {
        val s = scene()
        s.at(0.0)
        s.run(0.0, 50.0, 0.1)
        val before = s.anchorBreath
        s.anchorPeriodScale = 3.0
        s.at(50.1)
        assertTrue(abs(s.anchorBreath - before) < 0.05, "anchor ${s.anchorBreath - before}")
        val now = s.anchorBreath
        s.run(50.1, 58.1, 0.1)
        assertTrue(abs(s.anchorBreath - now) > 0.05, "a third of a 24 s cycle on it has moved: ${s.anchorBreath - now}")
        s.run(58.1, 74.1, 0.1)
        assertEquals(now, s.anchorBreath, 1e-6, "the linked 8 s cycle at scale 3 takes 24 s")
    }

    // The cloud breath is 1 plus or minus 0.03 over 60 s and the drift at most 0.012 (the brief); neither may leave that.
    @Test
    fun `neutral breathing repeats every sixty seconds inside three percent and drift stays small`() {
        val s = scene()
        s.at(0.0)
        val first = ArrayList<Double>()
        s.run(0.0, 60.0, 0.5) { first += s.cloudBreath }
        val second = ArrayList<Double>()
        s.run(60.0, 120.0, 0.5) { second += s.cloudBreath }
        for (i in first.indices) assertEquals(first[i], second[i], 1e-9, "sample $i")
        assertTrue(first.all { it in 0.97 - 1e-9..1.03 + 1e-9 })
        assertTrue(first.max() > 1.029 && first.min() < 0.971, "it uses the full range")
        for (node in listOf(0x1234L, 7L, 99L)) {
            assertTrue(abs(s.driftX(node)) <= 0.012 + 1e-9 && abs(s.driftY(node)) <= 0.012 + 1e-9)
        }
    }

    // Regression: absent attributes must leave the orb and the ripple exactly as drawn before.
    @Test
    fun `a node with no attributes keeps today's look`() {
        val s = scene()
        s.onEvent(MeshEvent.NodeSeen(1, "a", null, true, 0), 0)
        s.onEvent(heard(1, PacketKind.Text, rssi = null), 0)
        s.at(0.0)
        val r = s.ripples.single()
        s.run(0.0, 30.0)
        val o = assertNotNull(s.orb(1))
        assertEquals(1.0, o.coreSize, 0.0)
        assertEquals(1.0, o.coreAlpha, 0.0)
        assertEquals(0, o.haloHueDegrees)
        assertEquals(1.0, o.haloScale, 0.0)
        assertEquals(1.0, o.brightness, 0.0)
        assertEquals(1.0, o.flicker, 0.0)
        assertEquals(0.0, o.zOffset, 0.0)
        assertEquals(1.0, r.widthTrim, 0.0)
        assertEquals(1, r.ringCount)
        assertEquals(0, r.hueDegrees)
        assertEquals(0f, r.coolMix)
        assertEquals(RingStyle.Solid, r.style)
    }

    @Test
    fun `role and hardware from a node announcement shape the core and the halo tint and a later blank one keeps them`() {
        val s = scene()
        s.onEvent(MeshEvent.NodeSeen(1, "r", null, true, 0, role = "ROUTER", hardware = "HELTEC_V3"), 0)
        s.onEvent(MeshEvent.NodeSeen(2, "t", null, true, 0, role = "TRACKER"), 0)
        val r = s.orb(1)!!
        assertTrue(r.coreSize > 1.0 && r.coreAlpha < 1.0)
        assertEquals(EventLook.hardwareHueDegrees("HELTEC_V3"), r.haloHueDegrees)
        assertTrue(s.orb(2)!!.coreSize < 1.0)
        s.onEvent(MeshEvent.NodeSeen(1, "r", null, false, 0), 0)
        assertEquals("ROUTER", s.orb(1)!!.role)
        assertTrue(s.orb(1)!!.coreSize > 1.0)
    }

    // Regression: a flicker faster than two hertz is a flashing hazard. 15 percent is 1 Hz, empty is 2 Hz: count the dips in ten seconds.
    @Test
    fun `a low battery flickers at its rate and a healthy or powered node does not`() {
        fun dips(pct: Int): Int {
            val s = scene()
            s.onEvent(MeshEvent.Health(1, pct, null, null, 0), 0)
            s.at(0.0)
            var prev = s.orb(1)!!.flicker
            var falling = false
            var count = 0
            var t = 0.0
            var lo = 1.0
            while (t < 10.0 - 1e-9) {
                t += 0.01
                s.at(t)
                val f = s.orb(1)!!.flicker
                lo = minOf(lo, f)
                assertTrue(f in 0.88..1.0, "factor $f")
                if (falling && f > prev) count++
                falling = f < prev
                prev = f
            }
            return count
        }
        assertTrue(dips(15) in 9..11, "15 percent is 1 Hz")
        assertTrue(dips(0) in 19..21, "empty is 2 Hz")
        assertEquals(0, dips(60))
        assertEquals(0, dips(101))
    }

    @Test
    fun `voltage sets brightness within eighty five to one fifteen and uptime the halo size`() {
        val s = scene()
        s.onEvent(MeshEvent.Health(1, 80, 3.3f, 3600, 0), 0)
        s.onEvent(MeshEvent.Health(2, 101, 5.0f, 30L * 86400, 0), 0)
        s.onEvent(MeshEvent.Health(3, null, null, null, 0), 0)
        assertEquals(0.85, s.orb(1)!!.brightness, 1e-6)
        assertEquals(0.9, s.orb(1)!!.haloScale, 1e-9)
        assertEquals(1.15, s.orb(2)!!.brightness, 1e-6)
        assertEquals(1.1, s.orb(2)!!.haloScale, 1e-9)
        assertEquals(1.0, s.orb(3)!!.brightness, 0.0)
        assertEquals(1.0, s.orb(3)!!.haloScale, 0.0)
    }

    @Test
    fun `a health report for our own node makes no orb`() {
        val s = scene()
        s.onEvent(MeshEvent.Self(9, "me", null, 0), 0)
        s.onEvent(MeshEvent.Health(9, 10, 3.5f, 10, 0), 0)
        assertTrue(s.orbs.isEmpty())
    }

    // Range: -30 dBm is the full 0.15 pull toward the camera; it eases in over seconds and never snaps.
    @Test
    fun `a strong signal draws the orb toward the camera by easing and a weak one leaves it`() {
        val s = scene()
        s.onEvent(heard(1, rssi = -30), 0)
        s.onEvent(heard(2, rssi = -120), 0)
        s.at(0.0)
        s.at(1.0)
        assertTrue(s.orb(1)!!.zOffset in -0.05..-0.001, "after a second ${s.orb(1)!!.zOffset}")
        s.run(1.0, 120.0)
        assertEquals(-0.15, s.orb(1)!!.zOffset, 1e-6)
        assertEquals(0.0, s.orb(2)!!.zOffset, 1e-9)
    }

    @Test
    fun `signal strength smooths over packets and altitude adds to the lift within the cap`() {
        val s = scene()
        s.origin = GeoPoint(45.0, -93.0, 100)
        s.onEvent(MeshEvent.NodeSeen(1, null, GeoPoint(45.001, -93.0, 2000), true, 0), 0)
        s.onEvent(heard(1, rssi = -30), 0)
        s.at(0.0)
        s.run(0.0, 200.0)
        assertEquals(-0.35, s.orb(1)!!.zOffset, 1e-6)
        s.onEvent(heard(1, rssi = -120), 0)
        assertTrue(s.orb(1)!!.rssi!! in -80.0..-40.0, "one weak packet moves the average only part way: ${s.orb(1)!!.rssi}")
    }

    private fun ripple(e: MeshEvent.PacketHeard): Ripple {
        val s = scene()
        s.onEvent(e, 0)
        s.at(0.0)
        return s.ripples.first { it.echo == 0 }
    }

    @Test
    fun `payload size reaches the ripple width trim`() {
        assertEquals(1.2, ripple(heard(1, payload = 233)).widthTrim, 1e-9)
    }

    @Test
    fun `text length reaches the ripple ring count`() {
        assertEquals(3, ripple(heard(1, PacketKind.Text, text = 120)).ringCount)
    }

    @Test
    fun `channel index reaches the ripple hue offset`() {
        assertEquals(6, ripple(heard(1, channel = 2)).hueDegrees)
    }

    @Test
    fun `an mqtt packet gives a cool ripple and a radio packet does not`() {
        assertEquals(0.35f, ripple(heard(1, viaMqtt = true)).coolMix, 1e-6f)
        assertEquals(0f, ripple(heard(1, viaMqtt = false)).coolMix)
    }

    @Test
    fun `a traceroute packet gives a dashed ripple`() {
        assertEquals(RingStyle.Dashed, ripple(heard(1, PacketKind.Traceroute)).style)
    }

    @Test
    fun `non finite params never poison the rotation`() {
        val s = scene()
        s.onParams(ParamValues(rotationPeriodSeconds = Double.NaN, tiltDegrees = Double.NaN, wobbleDegrees = Double.POSITIVE_INFINITY))
        s.at(0.0)
        s.run(0.0, 100.0)
        assertTrue(s.rotation.yawRadians.isFinite() && s.rotation.angularVelocity.isFinite())
        assertTrue((s.rotation.tiltRadians + s.rotation.wobbleRadians).isFinite())
    }
}
