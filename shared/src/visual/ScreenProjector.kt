package org.meshtastic.aether.visual

/** The cloud fills this fraction of the half-width and half-height. */
private const val FIELD_FILL = 0.9f
private const val MIN_ORB_CAPACITY = 64

/**
 * One frame's screen geometry: the cloud's centre and radii in pixels, every orb projected and sorted far to near, and
 * where an edge or pulse end lands. Reused across frames without allocating.
 */
internal class ScreenProjector {
    private val projection = Projection()
    private val tmp = Projected()
    private val buffers = OrbBuffers()
    private var scene: SceneModel? = null
    private var k = 1.0
    private var zReach = 0.0

    var cx = 0f
        private set
    var cy = 0f
        private set
    private var rx = 0f
    private var ry = 0f

    /** The scene radius in pixels: the smaller of the two radii. */
    var radius = 0f
        private set

    /** The orbs projected by [projectOrbs], indexed far to near by [orbAt]. */
    var orbCount = 0
        private set

    fun begin(scene: SceneModel, width: Float, height: Float) {
        this.scene = scene
        cx = width / 2
        cy = height / 2
        rx = width / 2 * FIELD_FILL
        ry = height / 2 * FIELD_FILL
        radius = minOf(rx, ry)
        k = scene.cloudBreath
        projection.update(scene.rotation, CloudMotion.CAMERA_DISTANCE)
        zReach = CloudMotion.Z_EXTENT * k
    }

    /** Projects a layout point into [out] as screen pixels, with the perspective scale and depth. */
    private fun project(x: Double, y: Double, z: Double, out: Projected) {
        projection.project(x * k, y * k, z * zReach, out)
        out.x = cx + out.x * rx
        out.y = cy + out.y * ry
    }

    /** Projects every orb of the scene and sorts them far to near. */
    fun projectOrbs() {
        val scene = checkNotNull(scene)
        val n = scene.orbs.size
        buffers.fit(n)
        var oi = 0
        for (o in scene.orbs) {
            project(
                o.shownX + o.phOx + scene.driftX(o.node),
                o.shownY + o.phOy + scene.driftY(o.node),
                o.shownZ + o.zOffset + scene.driftZ(o.node),
                tmp,
            )
            o.projX = tmp.x
            o.projY = tmp.y
            o.projScale = tmp.scale
            o.projDepth = tmp.depth
            buffers.orbs[oi] = o
            buffers.depth[oi] = tmp.depth
            oi++
        }
        DepthSort.backToFront(buffers.depth, buffers.order, n)
        orbCount = n
    }

    /** The [index]th orb in draw order, far to near. */
    fun orbAt(index: Int): Orb? = buffers.orbs[buffers.order[index]]

    /** Fills [out] with the screen position, scale and depth of [node] (null is the anchor); false when unknown. */
    fun resolve(node: Long?, out: Projected): Boolean {
        val scene = checkNotNull(scene)
        if (node == null || node == scene.anchor?.node) {
            resolveAnchor(scene, out)
            return true
        }
        val o = scene.orb(node)
        if (o != null) {
            out.x = o.projX
            out.y = o.projY
            out.scale = o.projScale
            out.depth = o.projDepth
        }
        return o != null
    }

    private fun resolveAnchor(scene: SceneModel, out: Projected) {
        val a = scene.anchor
        if (a == null) {
            out.x = cx.toDouble()
            out.y = cy.toDouble()
            out.scale = 1.0
            out.depth = 0.0
        } else {
            project(scene.driftX(a.node), scene.driftY(a.node), 0.0, out)
        }
    }
}

/** Reused per-frame arrays for the orb draw order; they grow only when the field does. */
private class OrbBuffers {
    var orbs = arrayOfNulls<Orb>(0)
    var depth = DoubleArray(0)
    var order = IntArray(0)
    private var size = -1

    /** Makes room for [n] orbs and, when the count changed, restarts the order as the identity. */
    fun fit(n: Int) {
        if (n > orbs.size) {
            val cap = maxOf(n, orbs.size * 2, MIN_ORB_CAPACITY)
            orbs = arrayOfNulls(cap)
            depth = DoubleArray(cap)
            order = IntArray(cap)
            size = -1
        }
        if (n != size) {
            for (i in 0 until n) order[i] = i
            size = n
        }
    }
}
