package org.meshtastic.ambient.mesh

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
    )
}

internal fun Telemetry.toVitals(nowMillis: Long): MeshEvent.Vitals? {
    val d = device_metrics ?: return null
    return MeshEvent.Vitals(d.channel_utilization, d.air_util_tx, nowMillis)
}

internal fun NodeInfo.toNodeSeen(first: Boolean, nowMillis: Long): MeshEvent.NodeSeen {
    val lat = position?.latitude_i
    val lon = position?.longitude_i
    val geo = if (lat != null && lon != null && (lat != 0 || lon != 0)) GeoPoint(lat * 1e-7, lon * 1e-7) else null
    val name = user?.long_name?.takeIf { it.isNotBlank() } ?: user?.short_name?.takeIf { it.isNotBlank() }
    return MeshEvent.NodeSeen(num.toLong() and 0xFFFFFFFFL, name, geo, first, nowMillis)
}
