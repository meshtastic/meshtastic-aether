package org.meshtastic.aether

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import org.meshtastic.aether.log.Log
import org.meshtastic.aether.visual.SpatialFrame
import org.meshtastic.aether.visual.SpatialLayout
import org.meshtastic.aether.visual.SpatialSource

private const val TAG = "phonear"

/** How often, and how many times, to ask again while ARCore is still working out whether this phone is supported. */
private const val AVAILABILITY_RETRY_MS = 200L
private const val AVAILABILITY_RETRIES = 25

/** A tracked upward plane at least this far under the camera is taken as the floor. */
private const val FLOOR_BELOW_CAMERA_METERS = 0.5f

/**
 * Frames to look for the floor before keeping the assumed eye height. Plane finding stays on: reconfiguring the session
 * pauses every anchor for seconds while it relocalizes.
 */
private const val FLOOR_SEARCH_FRAMES = 900

/** A camera forward with less horizontal reach than this (looking at the floor or the sky) has no usable heading. */
private const val MIN_HEADING_REACH = 0.2f

/** The camera image starts dimmed so additive glows still read over a bright room; the slider sets it from 0 to 1. */
private const val DEFAULT_CAMERA_BRIGHTNESS = 0.55f
private const val BRIGHTNESS_STEPS = 100
private const val PREFS = "phone_ar"
private const val PREF_BRIGHTNESS = "camera_brightness"
private const val NEAR_CLIP = 0.05f
private const val FAR_CLIP = 30f
private const val HINT_ALPHA = 0.8f

/** The HUD's idle time: the controls hide after this long untouched. */
private const val CONTROLS_IDLE_MS = 15_000L
private const val CONTROLS_FADE_IN_MS = 200L
private const val CONTROLS_FADE_OUT_MS = 500L
private const val TEXT_PAD_DP = 16

/**
 * The phone AR host for the spatial view, offered on any phone or tablet with a camera and no XR headset. Nothing touches
 * ARCore until the first press: that press checks support, asks for the camera and, if needed, installs Google Play
 * Services for AR, then draws the cloud over the camera image until the source closes or the person presses Back.
 */
internal fun phoneArSpatialHost(activity: ComponentActivity, isTelevision: Boolean): PhoneArHost? =
    if (isTelevision || !activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) null else PhoneArHost(activity)

internal class PhoneArHost(private val activity: ComponentActivity) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var pending: SpatialSource? = null
    private var view: PhoneArView? = null
    private var availabilityTries = 0
    private var installAsked = false
    private var awaitingInstall = false

    private val cameraLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) proceed() else fail(R.string.ar_no_camera, "camera permission denied")
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    fun enter(source: SpatialSource) {
        handler.removeCallbacksAndMessages(null)
        view?.close()
        pending?.takeIf { it !== source }?.close()
        pending = source
        availabilityTries = 0
        installAsked = false
        awaitingInstall = false
        proceed()
    }

    override fun onResume(owner: LifecycleOwner) {
        view?.resume()
        if (awaitingInstall) {
            awaitingInstall = false
            proceed()
        }
    }

    override fun onPause(owner: LifecycleOwner) {
        view?.pause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        handler.removeCallbacksAndMessages(null)
        view?.close()
        pending?.close()
        pending = null
    }

    /** Takes the next step toward an open view; each step that waits on the person or ARCore calls this again. */
    private fun proceed() {
        val source = pending ?: return
        if (source.closed) {
            pending = null
            return
        }
        val availability = ArCoreApk.getInstance().checkAvailability(activity)
        if (availability.isTransient) {
            if (++availabilityTries > AVAILABILITY_RETRIES) return fail(R.string.ar_unsupported, "availability $availability")
            handler.postDelayed(::proceed, AVAILABILITY_RETRY_MS)
            return
        }
        if (availability.isUnsupported) return fail(R.string.ar_unsupported, "availability $availability")
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        val session = try {
            if (ArCoreApk.getInstance().requestInstall(activity, !installAsked) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installAsked = true
                awaitingInstall = true
                return
            }
            Session(activity)
        } catch (e: UnavailableUserDeclinedInstallationException) {
            return fail(R.string.ar_declined, "$e")
        } catch (e: UnavailableException) {
            return fail(R.string.ar_failed, "$e")
        } catch (e: SecurityException) {
            return fail(R.string.ar_no_camera, "$e")
        } catch (e: RuntimeException) {
            // ARCore's FatalException and the like: report it rather than end the app.
            return fail(R.string.ar_failed, "$e")
        }
        pending = null
        val opened = PhoneArView(activity, session, source) { closed -> if (view === closed) view = null }
        if (opened.open()) view = opened
    }

    private fun fail(message: Int, why: String) {
        Log.w(TAG, "spatial view not opened: $why")
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        pending?.close()
        pending = null
        awaitingInstall = false
    }
}

/**
 * One AR view: a full-screen GL surface over the app with the camera image and the cloud. The scene advances on the UI
 * thread, which [SpatialSource.frame] needs, and is copied for the GL thread under [renderer]'s lock.
 */
internal class PhoneArView(
    private val activity: ComponentActivity,
    private val session: Session,
    private val source: SpatialSource,
    private val onClosed: (PhoneArView) -> Unit,
) : Choreographer.FrameCallback {
    private val renderer = PhoneArRenderer(session, activity)
    private val surface = GLSurfaceView(activity)
    private val hint = TextView(activity)
    private val root = FrameLayout(activity)
    private var closed = false
    private var resumed = false
    private var shownTracking: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())
    private var controls: List<View> = emptyList()
    private var controlsShown = false

    private val hideControls = Runnable {
        handler.removeCallbacksAndMessages(null)
        controlsShown = false
        for (view in controls) {
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(CONTROLS_FADE_OUT_MS).withEndAction { view.visibility = View.INVISIBLE }.start()
        }
    }

    /** A turn straight to the opposite orientation keeps the surface size, so onSurfaceChanged alone misses it. */
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            renderer.displayChanged = true
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    private val back = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = source.close()
    }

    /** Starts the camera and puts the view on screen; false, with the source closed, when the camera won't start. */
    fun open(): Boolean {
        try {
            session.configure(
                Config(session).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    focusMode = Config.FocusMode.AUTO
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "session not configured: $e")
            Toast.makeText(activity, R.string.ar_failed, Toast.LENGTH_SHORT).show()
            closed = true
            session.close()
            source.close()
            onClosed(this)
            return false
        }
        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(3)
        surface.setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        surface.setRenderer(renderer)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        val pad = (TEXT_PAD_DP * activity.resources.displayMetrics.density).toInt()
        hint.setText(R.string.ar_hint)
        val disclosure = TextView(activity).apply {
            setText(R.string.ar_disclosure)
            textSize = 11f
        }
        for (text in listOf(hint, disclosure)) {
            text.setTextColor(Color.WHITE)
            text.alpha = HINT_ALPHA
            text.setShadowLayer(4f, 0f, 0f, Color.BLACK)
            text.gravity = Gravity.CENTER
            text.setPadding(pad, pad, pad, pad)
        }
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        renderer.cameraBrightness = prefs.getFloat(PREF_BRIGHTNESS, DEFAULT_CAMERA_BRIGHTNESS)
        val brightness = SeekBar(activity).apply {
            max = BRIGHTNESS_STEPS
            progress = (renderer.cameraBrightness * BRIGHTNESS_STEPS).roundToInt()
            contentDescription = activity.getString(R.string.ar_camera_brightness)
            setPadding(pad * 2, pad, pad * 2, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    renderer.cameraBrightness = value.toFloat() / BRIGHTNESS_STEPS
                }

                override fun onStartTrackingTouch(bar: SeekBar) = handler.removeCallbacks(hideControls)

                override fun onStopTrackingTouch(bar: SeekBar) {
                    prefs.edit().putFloat(PREF_BRIGHTNESS, renderer.cameraBrightness).apply()
                    showControls()
                }
            })
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_brightness)
            alpha = HINT_ALPHA
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val slider = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad * 2, pad, pad, 0)
            addView(icon, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(brightness, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        brightness.setPadding(pad, pad, pad, pad)
        val bottom = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(disclosure, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        controls = listOf(hint, bottom)
        // Like the HUD: a tap shows the controls or hides them, and they hide themselves after a while untouched.
        surface.setOnClickListener { if (controlsShown) hideControls.run() else showControls() }
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // The surface fills the screen; the controls keep clear of the camera cutout and the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (hint.layoutParams as FrameLayout.LayoutParams).setMargins(safe.left, safe.top, safe.right, 0)
            (bottom.layoutParams as FrameLayout.LayoutParams).setMargins(safe.left, 0, safe.right, safe.bottom)
            hint.requestLayout()
            bottom.requestLayout()
            insets
        }
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.onBackPressedDispatcher.addCallback(back)
        activity.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, handler)
        source.whenClosed(::close)
        if (closed) return false
        resume()
        if (closed) return false
        showControls()
        Choreographer.getInstance().postFrameCallback(this)
        Log.i(TAG, "view open")
        return true
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (closed) return
        if (source.closed) return close()
        if (renderer.cameraLost) {
            // Another app took the camera: end the view rather than leave a black screen until Back.
            Toast.makeText(activity, R.string.ar_failed, Toast.LENGTH_SHORT).show()
            return source.close()
        }
        renderer.stage(source.frame())
        val tracking = renderer.tracking
        if (tracking != shownTracking) {
            // Losing the cloud brings the help back, since it says how to find it again.
            if (shownTracking == true && !tracking) showControls()
            shownTracking = tracking
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun showControls() {
        handler.removeCallbacks(hideControls)
        controlsShown = true
        for (view in controls) {
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(CONTROLS_FADE_IN_MS).start()
        }
        handler.postDelayed(hideControls, CONTROLS_IDLE_MS)
    }

    /** Camera first, then the GL thread, so its first frame has a running session. */
    fun resume() {
        if (closed || resumed) return
        try {
            session.resume()
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "camera not available: $e")
            Toast.makeText(activity, R.string.ar_failed, Toast.LENGTH_SHORT).show()
            source.close()
            return
        }
        surface.onResume()
        resumed = true
    }

    /** The GL thread first: onPause blocks until it has stopped, so no frame updates a paused session. */
    fun pause() {
        if (!resumed) return
        resumed = false
        surface.onPause()
        session.pause()
        renderer.replace = true
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        Choreographer.getInstance().removeFrameCallback(this)
        back.remove()
        if (resumed) surface.onPause()
        resumed = false
        session.close()
        (root.parent as? ViewGroup)?.removeView(root)
        source.close()
        onClosed(this)
        Log.i(TAG, "view closed")
    }
}

/**
 * Draws the camera image, then the cloud additively with the WebXR view's shaders. The cloud hangs from an ARCore anchor
 * under where the phone was on the first tracked frame, turned so north is where it faced; the floor is assumed
 * [SpatialLayout.EYE_METERS] below until a tracked plane says otherwise.
 */
internal class PhoneArRenderer(private val session: Session, private val activity: ComponentActivity) : GLSurfaceView.Renderer {
    private val lock = Any()
    private var stagedSprites = FloatArray(0)
    private var stagedLines = FloatArray(0)
    private var stagedSpriteCount = 0
    private var stagedLineCount = 0
    private var staged = 0L
    private var uploaded = -1L

    private var spriteData: FloatBuffer = floats(0)
    private var lineData: FloatBuffer = floats(0)
    private var spriteCount = 0
    private var lineCount = 0

    /** How much of the camera image shows under the cloud, 0 to 1; set from the UI thread. */
    @Volatile var cameraBrightness = DEFAULT_CAMERA_BRIGHTNESS

    /** Whether the cloud is placed and tracked, read on the UI thread for the hint. */
    @Volatile var tracking = false
        private set

    private var anchor: Anchor? = null

    /** Set on a display rotation or surface change; the GL thread hands ARCore the new geometry before its next update. */
    @Volatile var displayChanged = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Set on the GL thread when ARCore loses the camera to another app; the UI thread then ends the view. */
    @Volatile var cameraLost = false
        private set

    /** Set from the UI thread when the view pauses; the next tracked frame places the cloud again. */
    @Volatile var replace = false
    private var loggedCamera: TrackingState? = null
    private var loggedAnchor: TrackingState? = null
    private var floorLocked = false
    private var floorSearch = 0
    private val model = FloatArray(16)
    private val view = FloatArray(16)
    private val viewModel = FloatArray(16)
    private val proj = FloatArray(16)

    private val quad = floats(8).apply { put(QUAD).flip() }
    private val quadUv = floats(8)
    private var cameraTexture = 0
    private var cameraProg = 0
    private var cameraPos = 0
    private var cameraUv = 0
    private var cameraDim = 0
    private var spriteProg = 0
    private var lineProg = 0
    private var spriteVao = 0
    private var lineVao = 0
    private var spriteBuf = 0
    private var lineBuf = 0
    private var spriteView = 0
    private var spriteProj = 0
    private var lineView = 0
    private var lineProj = 0

    /** Copies this frame's arrays for the GL thread; grows the copies only when a frame outgrows them. */
    fun stage(frame: SpatialFrame) {
        val spriteFloats = frame.spriteCount * SpatialFrame.SPRITE_STRIDE
        val lineFloats = frame.lineVertexCount * SpatialFrame.LINE_STRIDE
        synchronized(lock) {
            if (stagedSprites.size < spriteFloats) stagedSprites = FloatArray(frame.sprites.size)
            if (stagedLines.size < lineFloats) stagedLines = FloatArray(frame.lines.size)
            System.arraycopy(frame.sprites, 0, stagedSprites, 0, spriteFloats)
            System.arraycopy(frame.lines, 0, stagedLines, 0, lineFloats)
            stagedSpriteCount = frame.spriteCount
            stagedLineCount = frame.lineVertexCount
            staged++
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        cameraTexture = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        session.setCameraTextureName(cameraTexture)

        cameraProg = program(CAMERA_VS, CAMERA_FS)
        cameraPos = GLES30.glGetAttribLocation(cameraProg, "aPos")
        cameraUv = GLES30.glGetAttribLocation(cameraProg, "aUv")
        cameraDim = GLES30.glGetUniformLocation(cameraProg, "uDim")

        val ids = IntArray(3)
        spriteProg = program(SPRITE_VS, SPRITE_FS)
        GLES30.glGenVertexArrays(2, ids, 0)
        spriteVao = ids[0]
        lineVao = ids[1]
        GLES30.glGenBuffers(3, ids, 0)
        val cornerBuf = ids[0]
        spriteBuf = ids[1]
        lineBuf = ids[2]
        GLES30.glBindVertexArray(spriteVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerBuf)
        val corners = floats(8).apply { put(QUAD).flip() }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, corners, GLES30.GL_STATIC_DRAW)
        attribute(spriteProg, "aCorner", 2, 2, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteBuf)
        attribute(spriteProg, "aPlace", 4, SpatialFrame.SPRITE_STRIDE, 0, 1)
        attribute(spriteProg, "aColor", 4, SpatialFrame.SPRITE_STRIDE, 4, 1)
        attribute(spriteProg, "aCrisp", 1, SpatialFrame.SPRITE_STRIDE, 8, 1)
        lineProg = program(LINE_VS, LINE_FS)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuf)
        attribute(lineProg, "aPlace", 3, SpatialFrame.LINE_STRIDE, 0, 0)
        attribute(lineProg, "aColor", 4, SpatialFrame.LINE_STRIDE, 3, 0)
        GLES30.glBindVertexArray(0)
        spriteView = GLES30.glGetUniformLocation(spriteProg, "uView")
        spriteProj = GLES30.glGetUniformLocation(spriteProg, "uProj")
        lineView = GLES30.glGetUniformLocation(lineProg, "uView")
        lineProj = GLES30.glGetUniformLocation(lineProg, "uProj")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        displayChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (displayChanged) {
            displayChanged = false
            session.setDisplayGeometry(displayRotation(), surfaceWidth, surfaceHeight)
        }
        val frame = try {
            session.update()
        } catch (e: CameraNotAvailableException) {
            if (!cameraLost) Log.w(TAG, "camera lost: $e")
            cameraLost = true
            tracking = false
            return
        }
        if (frame.hasDisplayGeometryChanged()) {
            quad.rewind()
            quadUv.rewind()
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, quadUv)
            quad.rewind()
            quadUv.rewind()
        }
        if (frame.timestamp == 0L) return
        drawCamera()

        val camera = frame.camera
        val anchorState = anchor?.trackingState
        if (camera.trackingState != loggedCamera || anchorState != loggedAnchor) {
            loggedCamera = camera.trackingState
            loggedAnchor = anchorState
            Log.i(TAG, "camera ${camera.trackingState} (${camera.trackingFailureReason}), anchor $anchorState")
        }
        if (camera.trackingState != TrackingState.TRACKING) {
            tracking = false
            return
        }
        // After a pause, or when ARCore stops the anchor for good, the cloud is placed again around the phone as it is now
        // rather than waiting for the old anchor to relocalize.
        if (replace || anchorState == TrackingState.STOPPED) {
            replace = false
            anchor?.detach()
            anchor = null
            floorLocked = false
            floorSearch = 0
        }
        val pose = camera.displayOrientedPose
        if (anchor == null) anchor = session.createAnchor(placement(pose, pose.ty() - SpatialLayout.EYE_METERS.toFloat()))
        if (!floorLocked) findFloor(frame, pose)
        val placed = anchor ?: return
        if (placed.trackingState != TrackingState.TRACKING) {
            tracking = false
            return
        }
        tracking = true
        placed.pose.toMatrix(model, 0)
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, NEAR_CLIP, FAR_CLIP)
        Matrix.multiplyMM(viewModel, 0, view, 0, model, 0)
        upload()
        drawCloud()
    }

    /** Under the phone at [floorY], turned so the frame's -z (north) is where the phone faces. */
    private fun placement(pose: Pose, floorY: Float): Pose {
        val z = pose.zAxis
        var fx = -z[0]
        var fz = -z[2]
        if (hypot(fx, fz) < MIN_HEADING_REACH) {
            // Pointed at the floor or the sky: the top of the screen points where the person faces.
            val y = pose.yAxis
            fx = if (z[1] < 0f) y[0] else -y[0]
            fz = if (z[1] < 0f) y[2] else -y[2]
        }
        val half = Math.toRadians(SpatialLayout.facingYawDegrees(fx, fz).toDouble()) / 2
        return Pose(floatArrayOf(pose.tx(), floorY, pose.tz()), floatArrayOf(0f, sin(half).toFloat(), 0f, cos(half).toFloat()))
    }

    /** Moves the cloud onto the first tracked floor under the phone, once; gives up after [FLOOR_SEARCH_FRAMES]. */
    private fun findFloor(frame: com.google.ar.core.Frame, pose: Pose) {
        val placed = anchor ?: return
        var floor = Float.NaN
        for (plane in frame.getUpdatedTrackables(Plane::class.java)) {
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING || plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val y = plane.centerPose.ty()
            if (y <= pose.ty() - FLOOR_BELOW_CAMERA_METERS && (floor.isNaN() || y < floor)) floor = y
        }
        if (floor.isNaN() && ++floorSearch < FLOOR_SEARCH_FRAMES) return
        floorLocked = true
        if (!floor.isNaN()) {
            val at = placed.pose
            anchor = session.createAnchor(Pose(floatArrayOf(at.tx(), floor, at.tz()), at.rotationQuaternion))
            placed.detach()
            Log.i(TAG, "floor at ${pose.ty() - floor} m under the phone")
        } else {
            Log.i(TAG, "no floor found; keeping the assumed eye height")
        }
    }

    /** Uploads the staged frame, unless the GL thread already has it: it can draw more often than the scene advances. */
    private fun upload() {
        synchronized(lock) {
            if (staged == uploaded) return
            uploaded = staged
            spriteCount = stagedSpriteCount
            lineCount = stagedLineCount
            spriteData = fill(spriteData, stagedSprites, spriteCount * SpatialFrame.SPRITE_STRIDE)
            lineData = fill(lineData, stagedLines, lineCount * SpatialFrame.LINE_STRIDE)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, spriteBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, spriteData.limit() * 4, spriteData, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, lineData.limit() * 4, lineData, GLES30.GL_DYNAMIC_DRAW)
    }

    private fun drawCamera() {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(cameraProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glUniform1f(cameraDim, cameraBrightness)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(cameraPos)
        GLES30.glVertexAttribPointer(cameraPos, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glEnableVertexAttribArray(cameraUv)
        GLES30.glVertexAttribPointer(cameraUv, 2, GLES30.GL_FLOAT, false, 0, quadUv)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(cameraPos)
        GLES30.glDisableVertexAttribArray(cameraUv)
    }

    private fun drawCloud() {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(lineProg)
        GLES30.glUniformMatrix4fv(lineProj, 1, false, proj, 0)
        GLES30.glUniformMatrix4fv(lineView, 1, false, viewModel, 0)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, lineCount)
        GLES30.glUseProgram(spriteProg)
        GLES30.glUniformMatrix4fv(spriteProj, 1, false, proj, 0)
        GLES30.glUniformMatrix4fv(spriteView, 1, false, viewModel, 0)
        GLES30.glBindVertexArray(spriteVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, spriteCount)
        GLES30.glBindVertexArray(0)
    }

    private fun displayRotation(): Int = ContextCompat.getDisplayOrDefault(activity).rotation

    private companion object {
        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        fun floats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        /** Writes the first [count] of [from] into [into], or into a bigger buffer when it is too small. */
        fun fill(into: FloatBuffer, from: FloatArray, count: Int): FloatBuffer {
            val out = if (into.capacity() < count) floats(from.size) else into
            out.clear()
            out.put(from, 0, count)
            out.flip()
            return out
        }

        fun program(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            for ((type, source) in listOf(GLES30.GL_VERTEX_SHADER to vs, GLES30.GL_FRAGMENT_SHADER to fs)) {
                val s = GLES30.glCreateShader(type)
                GLES30.glShaderSource(s, source)
                GLES30.glCompileShader(s)
                val ok = IntArray(1)
                GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "phonear shader: ${GLES30.glGetShaderInfoLog(s)}" }
                GLES30.glAttachShader(p, s)
            }
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "phonear program: ${GLES30.glGetProgramInfoLog(p)}" }
            return p
        }

        fun attribute(prog: Int, name: String, size: Int, stride: Int, offset: Int, divisor: Int) {
            val at = GLES30.glGetAttribLocation(prog, name)
            GLES30.glEnableVertexAttribArray(at)
            GLES30.glVertexAttribPointer(at, size, GLES30.GL_FLOAT, false, stride * 4, offset * 4)
            GLES30.glVertexAttribDivisor(at, divisor)
        }

        const val CAMERA_VS = """#version 300 es
in vec2 aPos;
in vec2 aUv;
out vec2 vUv;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vUv = aUv;
}"""
        const val CAMERA_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTex;
uniform float uDim;
in vec2 vUv;
out vec4 color;
void main() {
  color = vec4(texture(uTex, vUv).rgb * uDim, 1.0);
}"""

        // The sprite and line shaders are webApp/resources/spatial.js's, with the anchor already in uView and no drop.
        const val SPRITE_VS = """#version 300 es
uniform mat4 uProj;
uniform mat4 uView;
in vec2 aCorner;
in vec4 aPlace;
in vec4 aColor;
in float aCrisp;
out vec2 vCorner;
out vec4 vColor;
out float vCrisp;
void main() {
  vec4 eye = uView * vec4(aPlace.xyz, 1.0);
  eye.xy += aCorner * aPlace.w;
  gl_Position = uProj * eye;
  vCorner = aCorner;
  vColor = aColor;
  vCrisp = aCrisp;
}"""
        const val SPRITE_FS = """#version 300 es
precision mediump float;
in vec2 vCorner;
in vec4 vColor;
in float vCrisp;
out vec4 color;
void main() {
  float r = length(vCorner);
  float halo = max(0.0, 1.0 - r);
  float disc = 1.0 - smoothstep(0.8, 1.0, r);
  float a = vColor.a * mix(halo, disc, vCrisp);
  color = vec4(vColor.rgb * a, a);
}"""
        const val LINE_VS = """#version 300 es
uniform mat4 uProj;
uniform mat4 uView;
in vec3 aPlace;
in vec4 aColor;
out vec4 vColor;
void main() {
  gl_Position = uProj * uView * vec4(aPlace, 1.0);
  vColor = aColor;
}"""
        const val LINE_FS = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 color;
void main() {
  color = vec4(vColor.rgb * vColor.a, vColor.a);
}"""
    }
}
