package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.PacketKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bounds and neutrals of the per-event visual attributes; expected values are hand computed from the design's ranges. */
class EventLookTest {
    private val eps = 1e-9

    // Regression: a router drawn like a tracker loses the role cue; unknown roles must keep the neutral size.
    @Test
    fun `routers get a larger softer core and trackers a smaller crisp one and unknown stays at one`() {
        assertTrue(EventLook.coreSize("ROUTER") > 1.0 && EventLook.coreAlpha("ROUTER") < 1.0)
        assertTrue(EventLook.coreSize("repeater") > 1.0)
        assertTrue(EventLook.coreSize("TRACKER") < 1.0 && EventLook.coreAlpha("TRACKER") == 1.0)
        assertEquals(1.0, EventLook.coreSize("CLIENT"), eps)
        assertEquals(1.0, EventLook.coreSize(null), eps)
        assertEquals(1.0, EventLook.coreAlpha(null), eps)
    }

    // Regression: a hue offset beyond a few degrees would repaint the node's identity color.
    @Test
    fun `the hardware tint is stable within eight degrees and absent when unknown`() {
        assertEquals(0, EventLook.hardwareHueDegrees(null))
        assertEquals(0, EventLook.hardwareHueDegrees(""))
        val names = listOf("HELTEC_V3", "TBEAM", "RAK4631", "T_ECHO", "STATION_G2", "TLORA_V2_1_1P6", "HELTEC_V2_1", "NANO_G1", "T_DECK", "SEEED_XIAO_S3")
        val degrees = names.map { EventLook.hardwareHueDegrees(it) }
        assertTrue(degrees.all { it in -8..8 })
        assertEquals(degrees, names.map { EventLook.hardwareHueDegrees(it) })
        assertTrue(degrees.toSet().size >= 4, "ten models spread over at least four offsets: $degrees")
    }

    // The signal range is -120 dBm (far) to -30 dBm (near); -75 is the midpoint, so half of the 0.15 reach.
    @Test
    fun `a strong signal pulls toward the camera and a weak or unknown one does not`() {
        assertEquals(0.0, EventLook.rssiDepthBias(null), eps)
        assertEquals(0.0, EventLook.rssiDepthBias(-120.0), eps)
        assertEquals(0.0, EventLook.rssiDepthBias(-140.0), eps)
        assertEquals(-0.075, EventLook.rssiDepthBias(-75.0), eps)
        assertEquals(-0.15, EventLook.rssiDepthBias(-30.0), eps)
        assertEquals(-0.15, EventLook.rssiDepthBias(10.0), eps)
        assertEquals(0.0, EventLook.rssiDepthBias(Double.NaN), eps)
    }

    @Test
    fun `high ground lifts toward the camera by at most a fifth and low ground sinks`() {
        assertEquals(0.0, EventLook.altitudeLift(null, 100), eps)
        assertEquals(-0.1, EventLook.altitudeLift(250, 100), eps)
        assertEquals(-0.2, EventLook.altitudeLift(2000, 100), eps)
        assertEquals(0.2, EventLook.altitudeLift(-900, 100), eps)
        assertEquals(-0.1, EventLook.altitudeLift(150, null), eps)
    }

    // Regression: a flicker above 2 Hz or one that goes dark is a flashing hazard; no flicker at all above 30 percent.
    @Test
    fun `battery flicker never exceeds two hertz and never dims past twelve percent`() {
        for (pct in -50..150) {
            val hz = BatteryFlicker.flickerHz(pct)
            assertTrue(hz in 0.0..2.0, "pct $pct gave $hz")
            for (k in 0 until 16) {
                val f = BatteryFlicker.flickerFactor(pct, k * PI / 8)
                assertTrue(f in 0.88..1.0, "pct $pct phase $k gave $f")
            }
        }
        assertEquals(0.0, BatteryFlicker.flickerHz(null), eps)
        assertEquals(0.0, BatteryFlicker.flickerHz(30), eps)
        assertEquals(0.0, BatteryFlicker.flickerHz(101), eps)
        assertEquals(1.0, BatteryFlicker.flickerHz(15), eps)
        assertEquals(2.0, BatteryFlicker.flickerHz(0), eps)
        assertEquals(1.0, BatteryFlicker.flickerFactor(null, PI), eps)
    }

    @Test
    fun `the flicker phase accumulator advances two pi per cycle and wraps`() {
        assertEquals(PI, BatteryFlicker.advanceFlicker(0.0, 1.0, 0.5), eps)
        assertEquals(0.5, BatteryFlicker.advanceFlicker(2 * PI - 0.5, 2.0, 0.5 + 0.25 / PI), 1e-6)
    }

    // 3.3 V to 4.2 V maps across 0.85 to 1.15, so 3.75 V is the midpoint 1.0; external power is the top.
    @Test
    fun `brightness stays within eighty five to one fifteen whatever the voltage`() {
        assertEquals(1.0, EventLook.brightness(null, null), eps)
        assertEquals(0.85, EventLook.brightness(3.0f, 50), eps)
        assertEquals(1.0, EventLook.brightness(3.75f, 50), 1e-6)
        assertEquals(1.15, EventLook.brightness(4.2f, 50), 1e-6)
        assertEquals(1.15, EventLook.brightness(null, 101), eps)
        for (v in listOf(-1f, 0f, 2f, 3.5f, 4.7f, 12f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertTrue(EventLook.brightness(v, 40) in 0.85..1.15, "voltage $v")
        }
    }

    // One hour is the small end and thirty days the large end of the log scale, so one day sits at 0.9 + 0.2 * log(24)/log(720).
    @Test
    fun `halo size follows uptime within ten percent`() {
        assertEquals(1.0, EventLook.haloScale(null), eps)
        assertEquals(0.9, EventLook.haloScale(0), eps)
        assertEquals(0.9, EventLook.haloScale(3600), eps)
        assertEquals(1.1, EventLook.haloScale(30L * 86400), eps)
        assertEquals(1.1, EventLook.haloScale(Long.MAX_VALUE), eps)
        assertEquals(0.9 + 0.2 * kotlin.math.ln(24.0) / kotlin.math.ln(720.0), EventLook.haloScale(86400), 1e-9)
    }

    // Regression: the width of a ripple is 2.5 dp for text and 1.5 dp otherwise, plus 2 dp thinning to 0 over the life.
    @Test
    fun `an unannotated ripple is as wide as before`() {
        assertEquals(4.5, RippleLook.rippleWidthDp(PacketKind.Text, 1.0, 0.0), eps)
        assertEquals(2.5, RippleLook.rippleWidthDp(PacketKind.Text, 1.0, 1.0), eps)
        assertEquals(3.5, RippleLook.rippleWidthDp(PacketKind.Telemetry, 1.0, 0.0), eps)
        assertEquals(1.5, RippleLook.rippleWidthDp(PacketKind.Position, 1.0, 1.0), eps)
    }

    @Test
    fun `payload size trims the ripple width between eighty five and a hundred twenty percent`() {
        assertEquals(1.0, RippleLook.payloadWidthTrim(null), eps)
        assertEquals(0.85, RippleLook.payloadWidthTrim(0), eps)
        assertEquals(1.2, RippleLook.payloadWidthTrim(233), eps)
        assertEquals(1.2, RippleLook.payloadWidthTrim(5000), eps)
        assertEquals(0.85 + 0.35 * 0.5, RippleLook.payloadWidthTrim(116) , 0.002)
    }

    @Test
    fun `only a long text earns more rings`() {
        assertEquals(1, RippleLook.ringCount(PacketKind.Text, null))
        assertEquals(1, RippleLook.ringCount(PacketKind.Text, 40))
        assertEquals(2, RippleLook.ringCount(PacketKind.Text, 41))
        assertEquals(2, RippleLook.ringCount(PacketKind.Text, 100))
        assertEquals(3, RippleLook.ringCount(PacketKind.Text, 101))
        assertEquals(1, RippleLook.ringCount(PacketKind.Telemetry, 200))
    }

    @Test
    fun `path packets draw a dashed ring and the rest a solid one`() {
        for (k in listOf(PacketKind.Routing, PacketKind.Traceroute, PacketKind.NeighborInfo)) assertEquals(RingStyle.Dashed, RippleLook.ringStyle(k))
        for (k in listOf(PacketKind.Text, PacketKind.Position, PacketKind.Telemetry, PacketKind.NodeInfo, PacketKind.Other)) {
            assertEquals(RingStyle.Solid, RippleLook.ringStyle(k))
        }
    }

    @Test
    fun `channel zero and unknown keep the hue and others shift by three degrees an index up to twenty one`() {
        assertEquals(0, RippleLook.channelHueDegrees(null))
        assertEquals(0, RippleLook.channelHueDegrees(0))
        assertEquals(6, RippleLook.channelHueDegrees(2))
        assertEquals(21, RippleLook.channelHueDegrees(7))
        assertEquals(21, RippleLook.channelHueDegrees(200))
        assertEquals(0, RippleLook.channelHueDegrees(-4))
    }

    @Test
    fun `only an mqtt ripple takes the cool tint`() {
        assertEquals(0f, RippleLook.mqttMix(false))
        assertTrue(RippleLook.mqttMix(true) in 0.2f..0.5f)
        assertTrue(abs(RippleLook.mqttMix(true) - 0.35f) < 1e-6f)
    }
}
