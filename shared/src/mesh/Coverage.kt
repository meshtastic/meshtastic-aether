package org.meshtastic.ambient.mesh

/**
 * A tally of what the mesh actually reports: the distinct nodes per telemetry field, the role and hardware mix of the
 * nodes known, and whether our own node ever delivered LocalStats. Feed it every event; [describe] is the hourly log line.
 */
class Coverage {
    private val known = HashSet<Long>()
    private val roleOf = HashMap<Long, String>()
    private val hardwareOf = HashMap<Long, String>()
    private val temp = HashSet<Long>()
    private val hum = HashSet<Long>()
    private val press = HashSet<Long>()
    private val lux = HashSet<Long>()
    private val wind = HashSet<Long>()
    private val batt = HashSet<Long>()
    private val volt = HashSet<Long>()
    private var stats = false

    fun add(e: MeshEvent) {
        when (e) {
            is MeshEvent.NodeSeen -> {
                known += e.node
                e.role?.let { roleOf[e.node] = it }
                e.hardware?.let { hardwareOf[e.node] = it }
            }
            is MeshEvent.NodeGone -> {
                known -= e.node
                roleOf -= e.node
                hardwareOf -= e.node
            }
            is MeshEvent.Environment -> {
                if (e.temperatureC != null) temp += e.node
                if (e.humidityPct != null) hum += e.node
                if (e.pressureHpa != null) press += e.node
                if (e.lux != null) lux += e.node
                if (e.windMps != null || e.windGustMps != null || e.windLullMps != null || e.windDirectionDeg != null) wind += e.node
            }
            is MeshEvent.Health -> {
                if (e.batteryPct != null) batt += e.node
                if (e.voltage != null) volt += e.node
            }
            is MeshEvent.Stats -> stats = true
            else -> Unit
        }
    }

    /** One stable line: counts, then the five most common roles and hardware models (ties by name). */
    fun describe(): String =
        "coverage nodes=${known.size} temp=${temp.size} hum=${hum.size} press=${press.size} lux=${lux.size} " +
            "wind=${wind.size} batt=${batt.size} volt=${volt.size} stats=${if (stats) "yes" else "no"} " +
            "roles=${top(roleOf.values)} hw=${top(hardwareOf.values)}"

    fun reset() {
        known.clear()
        roleOf.clear()
        hardwareOf.clear()
        listOf(temp, hum, press, lux, wind, batt, volt).forEach { it.clear() }
        stats = false
    }

    private fun top(names: Collection<String>): String {
        if (names.isEmpty()) return "-"
        return names.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(TOP).joinToString(",") { "${it.key}:${it.value}" }
    }

    private companion object {
        const val TOP = 5
    }
}
