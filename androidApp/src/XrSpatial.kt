package org.meshtastic.aether

import android.view.Choreographer
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.xr.arcore.ArDevice
import androidx.xr.arcore.TrackingState
import androidx.xr.runtime.Config
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.runtime.SessionCreateSuccess
import androidx.xr.runtime.math.BoundingBox
import androidx.xr.runtime.math.Pose
import androidx.xr.runtime.math.Quaternion
import androidx.xr.runtime.math.Vector3
import androidx.xr.runtime.math.Vector4
import androidx.xr.scenecore.AlphaMode
import androidx.xr.scenecore.ByteBufferRegion
import androidx.xr.scenecore.CustomMesh
import androidx.xr.scenecore.Entity
import androidx.xr.scenecore.KhronosUnlitMaterial
import androidx.xr.scenecore.MeshBuffer
import androidx.xr.scenecore.MeshEntity
import androidx.xr.scenecore.MeshSubsetTopology
import androidx.xr.scenecore.Space
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.SpatialEnvironment.SpatialEnvironmentPreference
import androidx.xr.scenecore.VertexAttribute
import androidx.xr.scenecore.VertexAttributeType
import androidx.xr.scenecore.VertexLayout
import androidx.xr.scenecore.scene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.visual.SpatialLayout
import org.meshtastic.aether.visual.SpatialMesh
import org.meshtastic.aether.visual.SpatialSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "xr"
private const val XR_SPATIAL_FEATURE = "android.software.xr.api.spatial"

/**
 * The XR system's spatial API version that takes MeshBuffer updates; an API 1 system aborts the process on the first
 * update ("SplitEngineBridge ... Unexpected command").
 */
private const val MIN_SPATIAL_API = 3

/** Frames to wait for head tracking in Full Space before opening anyway: about three seconds at 72 Hz. */
private const val TRACKING_WAIT_FRAMES = 216
private const val SPRITE_CAPACITY = 1024
private const val LINE_CAPACITY = 1024

/** Position then a UBYTE4_NORM color: 12 + 4 bytes. */
private const val VERTEX_BYTES = 16

/** SceneCore's index format is 32-bit (its MeshBuffer.BYTES_PER_INDEX, internal in 1.1.0-alpha02). */
private const val INDEX_BYTES = 4

/** The cloud reaches no further than this from the viewer, so the mesh is never culled. */
private const val REACH_METERS = SpatialLayout.FAR_METERS.toFloat() + 1f

/**
 * The Android XR host for the spatial view, or null off a headset, so phones and TVs never create a session or ask for
 * ARCore. The XR session is created on the first [XrSpatialHost.enter], not at launch.
 */
internal fun xrSpatialHost(activity: ComponentActivity): XrSpatialHost? =
    if (activity.packageManager.hasSystemFeature(XR_SPATIAL_FEATURE, MIN_SPATIAL_API)) XrSpatialHost(activity) else null

internal class XrSpatialHost(private val activity: ComponentActivity) {
    private var view: XrSpatial? = null
    private var starting = false

    fun enter(source: SpatialSource) {
        view?.let {
            it.enter(source)
            return
        }
        if (starting) return
        starting = true
        activity.lifecycleScope.launch {
            try {
                view = start()?.also { if (!source.closed) it.enter(source) }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "spatial view failed to start: $e")
            } finally {
                starting = false
            }
        }
    }

    private suspend fun start(): XrSpatial? {
        val result = Session.create(activity, Dispatchers.Main, activity)
        val session = (result as? SessionCreateSuccess)?.session
        if (session == null) {
            Log.w(TAG, "no session: $result")
            return null
        }
        val tracking = Config.Builder(session.config).setDeviceTracking(DeviceTrackingMode.SPATIAL).build()
        Log.i(TAG, "session created, device tracking ${session.configure(tracking)}")
        val material = KhronosUnlitMaterial.create(session, AlphaMode.BLEND)
        material.setBaseColorFactor(Vector4(1f, 1f, 1f, 1f))
        return XrSpatial(session, material)
    }
}

/**
 * The spatial view on a headset: [enter] asks for Full Space and draws the cloud around the viewer until the source
 * closes or the app leaves Full Space. The mesh and its entities live as long as the session and are hidden between
 * views, since SceneCore aborts the process when a mesh is released while an entity still holds it.
 */
internal class XrSpatial(private val session: Session, material: KhronosUnlitMaterial) : Choreographer.FrameCallback {
    private val mesh = SpatialMesh(SPRITE_CAPACITY, LINE_CAPACITY)
    private val vertices = ByteBuffer.allocateDirect(mesh.maxVertices * VERTEX_BYTES).order(ByteOrder.nativeOrder())
    private val indices = ByteBuffer.allocateDirect(mesh.maxIndices * INDEX_BYTES).order(ByteOrder.nativeOrder())
    private val buffer: MeshBuffer
    private val root: Entity

    /** [SpatialMesh.colors] keep red in the low byte, which a little-endian buffer writes first. */
    private val littleEndian = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    private var source: SpatialSource? = null
    private var wasFull = false
    private var waited = 0

    init {
        val layout = VertexLayout.Builder()
            .addAttribute(VertexAttribute.POSITION, VertexAttributeType.FLOAT3)
            .addAttribute(VertexAttribute.COLOR, VertexAttributeType.UBYTE4_NORM)
            .build()
        buffer = MeshBuffer.createDynamic(session, layout, mesh.maxVertices, mesh.maxIndices)
        val custom = CustomMesh.BuilderFromMeshBuffer(session, buffer)
            .addSubset(MeshSubsetTopology.TRIANGLES, 0, mesh.maxIndices)
            .setBounds(
                BoundingBox.fromMinMax(
                    Vector3(-REACH_METERS, 0f, -REACH_METERS),
                    Vector3(REACH_METERS, REACH_METERS, REACH_METERS),
                ),
            )
            .build()
        root = Entity.create(session, "aether", Pose.Identity, session.scene.activitySpace)
        MeshEntity.create(session, custom, listOf(material), 0, Pose.Identity, root)
        root.setEnabled(false)
    }

    fun enter(next: SpatialSource) {
        val showing = source != null
        source = next
        wasFull = false
        waited = 0
        session.scene.requestFullSpace()
        if (!showing) Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val current = source ?: return
        // Full Space arrives a few frames after the request; leaving it later ends the view.
        val full = SpatialCapability.SPATIAL_3D_CONTENT in session.scene.spatialCapabilities
        if (current.closed || (wasFull && !full)) {
            exit(backToHome = full)
            return
        }
        // Placed once the head is tracked, since an untracked pose centres the cloud on the panel rather than the
        // viewer; after a few seconds without tracking it opens anyway rather than leave the button doing nothing.
        if (full && !wasFull) waited++
        val tracked = ArDevice.getInstance(session).state.value.trackingState == TrackingState.TRACKING
        if (full && !wasFull && (tracked || waited > TRACKING_WAIT_FRAMES)) {
            if (!tracked) Log.w(TAG, "head not tracked; placing the view at the last pose")
            wasFull = true
            root.setPose(rootPose())
            root.setEnabled(true)
            // No skybox or geometry is a dark void: the glows are alpha blended, so they only read on a dark field.
            session.scene.spatialEnvironment.preferredSpatialEnvironment = SpatialEnvironmentPreference(null, null)
            Log.i(TAG, "view open, root at ${root.getPose(Space.ACTIVITY)}")
        }
        if (wasFull) {
            val frame = current.frame()
            val head = ArDevice.getInstance(session).state.value.devicePose
            val eye = session.scene.perceptionSpace.transformPositionTo(head.translation, root)
            mesh.build(frame, eye.x, eye.y, eye.z)
            upload()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Hides the cloud and restores the environment; [backToHome] when the view ended with the app still in Full Space. */
    private fun exit(backToHome: Boolean) {
        source = null
        root.setEnabled(false)
        session.scene.spatialEnvironment.preferredSpatialEnvironment = null
        if (backToHome) session.scene.requestHomeSpace()
        Log.i(TAG, "view closed")
    }

    /** Under the viewer's head at floor height, turned so the frame's -z (north) is where they face now. */
    private fun rootPose(): Pose {
        val device = ArDevice.getInstance(session).state.value.devicePose
        val head = session.scene.perceptionSpace.transformPoseTo(device, session.scene.activitySpace)
        val forward = head.forward
        val at = head.translation
        return Pose(
            Vector3(at.x, at.y - SpatialLayout.EYE_METERS.toFloat(), at.z),
            Quaternion.fromAxisAngle(Vector3.Up, SpatialLayout.facingYawDegrees(forward.x, forward.z)),
        )
    }

    private fun upload() {
        vertices.clear()
        val p = mesh.positions
        val c = mesh.colors
        for (v in 0 until mesh.maxVertices) {
            vertices.putFloat(p[v * 3]).putFloat(p[v * 3 + 1]).putFloat(p[v * 3 + 2])
            vertices.putInt(if (littleEndian) c[v] else Integer.reverseBytes(c[v]))
        }
        vertices.flip()
        indices.clear()
        for (i in mesh.indices) indices.putInt(i)
        indices.flip()
        buffer.updateVertexData(0, ByteBufferRegion(vertices, 0, vertices.limit()))
        buffer.updateIndexData(ByteBufferRegion(indices, 0, indices.limit()))
    }
}
