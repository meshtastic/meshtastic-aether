package org.meshtastic.aether.visual

import org.meshtastic.aether.mesh.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val SPIRAL_STEPS = 600

/** Each spiral step reaches this fraction of [SceneModel.MIN_SEPARATION] times the square root of its index. */
private const val SPIRAL_STEP_FRACTION = 0.9
private const val METERS_PER_DEGREE = 111_320.0
private const val DEGREES_PER_HALF_TURN = 180.0

/** A place this close to the reference is the reference itself. */
private const val CENTERED_EPSILON = 1e-9

/**
 * Lays the orbs out in the unit disc around the reference point (our node, else the centroid of the positioned ones):
 * true bearings with a percentile distance fit, seeded places for nodes with no position, then orbs kept apart in
 * screen terms.
 */
internal class SceneLayout {
    private val placement = ArrayList<Orb>()
    private var aspect = 1.0

    fun layout(orbs: Collection<Orb>, origin: GeoPoint?, aspect: Double) {
        this.aspect = aspect
        val ref = origin ?: centroid(orbs)
        val placed = ArrayList<Orb>()
        val radii = ArrayList<Double>()
        for (orb in orbs) {
            val pos = orb.position
            if (pos == null || ref == null) {
                val seeded = SceneModel.seededPosition(orb.node)
                orb.x = seeded.first
                orb.y = seeded.second
            } else {
                placed += orb
                val east = (pos.lon - ref.lon) * cos(ref.lat * PI / DEGREES_PER_HALF_TURN) * METERS_PER_DEGREE
                val north = (pos.lat - ref.lat) * METERS_PER_DEGREE
                orb.x = east
                orb.y = -north
                radii += hypot(east, north)
            }
        }
        if (placed.isNotEmpty()) fitToDisc(placed, SceneModel.fitRadius(radii))
        separate(orbs)
    }

    private fun fitToDisc(placed: List<Orb>, fit: Double) {
        for (orb in placed) {
            val r = hypot(orb.x, orb.y)
            if (r < CENTERED_EPSILON) {
                orb.x = 0.0
                orb.y = 0.0
            } else {
                val s = SceneModel.screenRadius(r, fit) / r
                orb.x *= s
                orb.y *= s
            }
        }
    }

    /**
     * Moves each orb that sits closer than [SceneModel.MIN_SEPARATION] (in screen terms at [aspect]) to an earlier one,
     * in node order, along a golden-angle spiral to the nearest free place inside the unit ellipse; orbs that are apart
     * stay put.
     */
    private fun separate(orbs: Collection<Orb>) {
        val list = placement
        list.clear()
        list.addAll(orbs)
        list.sortBy { it.node }
        for (i in list.indices) {
            val o = list[i]
            if (!isFree(list, i, o.x, o.y)) moveToFreePlace(list, i, o)
        }
        for (o in list) {
            o.z = SceneModel.depthFor(o.node, o.x * o.x + o.y * o.y)
            if (!o.placed) {
                o.shownX = o.x
                o.shownY = o.y
                o.shownZ = o.z
                o.placed = true
            }
        }
    }

    private fun moveToFreePlace(list: List<Orb>, i: Int, o: Orb) {
        for (k in 1..SPIRAL_STEPS) {
            val r = SceneModel.MIN_SEPARATION * sqrt(k.toDouble()) * SPIRAL_STEP_FRACTION
            val a = k * GOLDEN_ANGLE
            val nx = o.x + r * cos(a) / aspect
            val ny = o.y + r * sin(a)
            if (nx * nx + ny * ny <= 1.0 && isFree(list, i, nx, ny)) {
                o.x = nx
                o.y = ny
                return
            }
        }
    }

    private fun isFree(list: List<Orb>, upTo: Int, x: Double, y: Double): Boolean {
        for (j in 0 until upTo) {
            val dx = (list[j].x - x) * aspect
            val dy = list[j].y - y
            if (dx * dx + dy * dy < SceneModel.MIN_SEPARATION * SceneModel.MIN_SEPARATION) return false
        }
        return true
    }

    private fun centroid(orbs: Collection<Orb>): GeoPoint? {
        var lat = 0.0
        var lon = 0.0
        var n = 0
        for (orb in orbs) {
            val p = orb.position ?: continue
            lat += p.lat
            lon += p.lon
            n++
        }
        return if (n == 0) null else GeoPoint(lat / n, lon / n)
    }

    private companion object {
        val GOLDEN_ANGLE = PI * (3.0 - sqrt(5.0))
    }
}
