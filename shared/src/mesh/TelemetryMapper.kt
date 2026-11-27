package org.meshtastic.aether.mesh

import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Telemetry

internal fun Telemetry.toEnvironment(node: Long, nowMillis: Long): MeshEvent.Environment? {
    val e = environment_metrics ?: return null
    return MeshEvent.Environment(
        node = node,
        temperatureC = e.temperature,
        humidityPct = e.relative_humidity,
        pressureHpa = e.barometric_pressure,
        windMps = e.wind_speed,
        lux = e.lux,
        atMillis = nowMillis,
        windDirectionDeg = e.wind_direction?.toFloat(),
        windGustMps = e.wind_gust,
        windLullMps = e.wind_lull,
    )
}

/** A proto `uint32` counter, which Wire reads as a signed Int, as its unsigned value. */
private fun Int.unsigned(): Long = toUInt().toLong()

/** DeviceMetrics of any node; null when the packet carries none of battery, voltage, uptime and channel use. */
internal fun Telemetry.toHealth(node: Long, nowMillis: Long): MeshEvent.Health? {
    val d = device_metrics ?: return null
    val battery = d.battery_level
    val volts = d.voltage
    val uptime = d.uptime_seconds?.unsigned()
    val channel = d.channel_utilization
    val reportsNothing = (battery ?: volts ?: uptime ?: channel) == null
    return if (reportsNothing) {
        null
    } else {
        MeshEvent.Health(node, battery, volts, uptime, nowMillis, channel, d.air_util_tx)
    }
}

/** LocalStats of our own node; zero node counts and a zero noise floor are the proto's "not reported". */
internal fun Telemetry.toStats(nowMillis: Long): MeshEvent.Stats? {
    val l = local_stats ?: return null
    return MeshEvent.Stats(
        numOnline = l.num_online_nodes.takeIf { it != 0 },
        numTotal = l.num_total_nodes.takeIf { it != 0 },
        rxTotal = l.num_packets_rx.unsigned(),
        rxDupe = l.num_rx_dupe.unsigned(),
        rxBad = l.num_packets_rx_bad.unsigned(),
        noiseFloorDbm = l.noise_floor.takeIf { it != 0 },
        uptimeSeconds = l.uptime_seconds.unsigned(),
        atMillis = nowMillis,
    )
}

internal fun Telemetry.toVitals(nowMillis: Long): MeshEvent.Vitals? {
    val d = device_metrics ?: return null
    return MeshEvent.Vitals(d.channel_utilization, d.air_util_tx, nowMillis)
}

internal fun NodeInfo.toNodeSeen(first: Boolean, nowMillis: Long): MeshEvent.NodeSeen {
    val lat = position?.latitude_i
    val lon = position?.longitude_i
    val geo = if (lat == null || lon == null || isNullIsland(lat, lon)) {
        null
    } else {
        GeoPoint(lat * DEGREES_PER_E7, lon * DEGREES_PER_E7, position?.altitude)
    }
    val name = user?.long_name?.takeIf { it.isNotBlank() } ?: user?.short_name?.takeIf { it.isNotBlank() }
    val hw = user?.hw_model?.takeIf { it != HardwareModel.UNSET }?.name
    return MeshEvent.NodeSeen(num.toNodeNum(), name, geo, first, nowMillis, role = user?.role?.name, hardware = hw)
}

/** 0, 0 is what an unset position reads as. */
private fun isNullIsland(latE7: Int, lonE7: Int) = latE7 == 0 && lonE7 == 0

/** `latitude_i` and `longitude_i` are degrees times 1e7. */
private const val DEGREES_PER_E7 = 1e-7
