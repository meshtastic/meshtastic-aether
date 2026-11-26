package org.meshtastic.ambient.mesh

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

private fun Int.unsigned(): Long = toLong() and 0xFFFFFFFFL

/** DeviceMetrics of any node; null when the packet carries none of battery, voltage and uptime. */
internal fun Telemetry.toHealth(node: Long, nowMillis: Long): MeshEvent.Health? {
    val d = device_metrics ?: return null
    val battery = d.battery_level?.toInt()
    val volts = d.voltage
    val uptime = d.uptime_seconds?.unsigned()
    if (battery == null && volts == null && uptime == null) return null
    return MeshEvent.Health(node, battery, volts, uptime, nowMillis)
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
    val geo = if (lat != null && lon != null && (lat != 0 || lon != 0)) {
        GeoPoint(lat * 1e-7, lon * 1e-7, position?.altitude)
    } else {
        null
    }
    val name = user?.long_name?.takeIf { it.isNotBlank() } ?: user?.short_name?.takeIf { it.isNotBlank() }
    val hw = user?.hw_model?.takeIf { it != HardwareModel.UNSET }?.name
    return MeshEvent.NodeSeen(num.toLong() and 0xFFFFFFFFL, name, geo, first, nowMillis, role = user?.role?.name, hardware = hw)
}
