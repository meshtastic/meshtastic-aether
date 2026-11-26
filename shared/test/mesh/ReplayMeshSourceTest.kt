package org.meshtastic.ambient.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.PortNum
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayMeshSourceTest {
    private fun line(at: Long, f: FromRadio) = "$at\t${Base64.encode(f.encode())}"

    private val capture = listOf(
        line(1_000, frame(encryptedPacket(0x11, hopStart = 3, hopLimit = 3, snr = 5.5f, rssi = -90))),
        line(3_000, frame(decodedPacket(0x22, PortNum.TEXT_MESSAGE_APP, hopStart = 3, hopLimit = 1, snr = -2f, rssi = -110))),
        line(
            7_000,
            frame(
                decodedPacket(
                    0x33,
                    PortNum.TELEMETRY_APP,
                    payload = envTelemetry(18.0f, 55f, 1005f).encode(),
                ),
            ),
        ),
    )

    private val expected = listOf(
        MeshEvent.Link(LinkState.Replaying, 1_000),
        MeshEvent.PacketHeard(0x11, PacketKind.Encrypted, 5.5f, -90, 0, false, 1_000, payloadBytes = 4),
        MeshEvent.PacketHeard(0x22, PacketKind.Text, -2f, -110, 2, false, 3_000, payloadBytes = 0, textLength = 0, channel = 0),
        MeshEvent.PacketHeard(
            0x33, PacketKind.Telemetry, null, null, null, false, 7_000,
            payloadBytes = envTelemetry(18.0f, 55f, 1005f).encode().size, channel = 0,
        ),
        MeshEvent.Environment(0x33, 18.0f, 55f, 1005f, null, null, 7_000),
    )

    @Test
    fun `a three-line capture replays the same events in order with scaled delays`() = runTest {
        val seen = ArrayList<Pair<Long, MeshEvent>>()
        ReplayMeshSource(capture.asSequence(), speed = 2.0).events.collect { seen += currentTime to it }
        assertEquals(expected, seen.map { it.second })
        assertEquals(listOf(0L, 0L, 1_000L, 3_000L, 3_000L), seen.map { it.first })
    }

    @Test
    fun `at 1x the delays equal the capture gaps`() = runTest {
        ReplayMeshSource(capture.asSequence()).events.collect { }
        assertEquals(6_000L, currentTime)
    }

    @Test
    fun `malformed lines are skipped without stopping the replay`() = runTest {
        val noisy = sequenceOf("", "garbage", "abc\t!!!", "5\t***notbase64***") + capture.asSequence()
        val seen = ArrayList<MeshEvent>()
        ReplayMeshSource(noisy).events.collect { seen += it }
        assertEquals(expected, seen)
    }
}
