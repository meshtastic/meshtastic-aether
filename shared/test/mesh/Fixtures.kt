package org.meshtastic.ambient.mesh

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.Data
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Telemetry

internal fun decodedPacket(
    from: Int,
    port: PortNum,
    hopStart: Int = 0,
    hopLimit: Int = 0,
    snr: Float = 0f,
    rssi: Int? = null,
    viaMqtt: Boolean = false,
    payload: ByteArray = ByteArray(0),
): MeshPacket = MeshPacket.Builder().also { b ->
    b.from = from
    b.decoded = Data.Builder().also { d ->
        d.portnum = port
        d.payload = payload.toByteString()
    }.build()
    b.hop_start = hopStart
    b.hop_limit = hopLimit
    b.rx_snr = snr
    b.rx_rssi = rssi
    b.via_mqtt = viaMqtt
}.build()

internal fun encryptedPacket(
    from: Int,
    hopStart: Int = 0,
    hopLimit: Int = 0,
    snr: Float = 0f,
    rssi: Int? = null,
    viaMqtt: Boolean = false,
): MeshPacket = MeshPacket.Builder().also { b ->
    b.from = from
    b.encrypted = byteArrayOf(9, 8, 7, 6).toByteString()
    b.hop_start = hopStart
    b.hop_limit = hopLimit
    b.rx_snr = snr
    b.rx_rssi = rssi
    b.via_mqtt = viaMqtt
}.build()

internal fun frame(p: MeshPacket): FromRadio = FromRadio.Builder().also { it.packet = p }.build()

internal fun envTelemetry(tempC: Float, humidity: Float, pressure: Float): Telemetry =
    Telemetry.Builder().also { t ->
        t.environment_metrics = EnvironmentMetrics.Builder().also { e ->
            e.temperature = tempC
            e.relative_humidity = humidity
            e.barometric_pressure = pressure
        }.build()
    }.build()
