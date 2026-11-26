package org.meshtastic.aether.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key

/** What a key press asks for; [keyActionFor] is the one table that maps keys to these. */
internal enum class KeyAction(
    /** True when holding the key should repeat the action. */
    val repeats: Boolean = false,
) {
    ToggleOverlay,
    ToggleFullscreen,
    Escape,
    VolumeUp(repeats = true),
    VolumeDown(repeats = true),
    ToggleMute,
    Disconnect,
}

/** H overlay, F fullscreen, Esc close or leave fullscreen, Up and Down volume, M mute, D disconnect; null for any other key. */
internal fun keyActionFor(key: Key): KeyAction? = when (key) {
    Key.H -> KeyAction.ToggleOverlay
    Key.F -> KeyAction.ToggleFullscreen
    Key.Escape -> KeyAction.Escape
    Key.DirectionUp -> KeyAction.VolumeUp
    Key.DirectionDown -> KeyAction.VolumeDown
    Key.M -> KeyAction.ToggleMute
    Key.D -> KeyAction.Disconnect
    else -> null
}

/** Something the host or the app must do after a key; [Controls] only changes its own state. */
internal enum class Effect { None, ToggleFullscreen, Disconnect }

/** The viewer's controls: overlay, volume and mute. Plain state, so a key table can be tested without a window. */
internal class Controls(volume: Float = 1f) {
    var overlayOpen by mutableStateOf(false)

    /** Whether the overlay lists the mesh-driven parameters; shown only while the overlay is. */
    var driversOpen by mutableStateOf(false)

    /** Volume 0..1 before muting. */
    var volume by mutableFloatStateOf(volume.coerceIn(0f, 1f))

    var muted by mutableStateOf(false)

    /** What the engine plays: silent while muted. */
    val effectiveVolume: Float get() = if (muted) 0f else volume

    /**
     * Applies [action]. [fullscreen] is whether the host is fullscreen now and [canFullscreen] whether it has a switch;
     * the result says what the caller must do and, through [consumed], whether the key was used.
     */
    fun apply(action: KeyAction, fullscreen: Boolean, canFullscreen: Boolean): Result = when (action) {
        KeyAction.ToggleOverlay -> {
            overlayOpen = !overlayOpen
            Result(true, Effect.None)
        }
        KeyAction.ToggleFullscreen -> if (canFullscreen) Result(true, Effect.ToggleFullscreen) else Result(false, Effect.None)
        KeyAction.Escape -> when {
            overlayOpen -> {
                overlayOpen = false
                Result(true, Effect.None)
            }
            fullscreen && canFullscreen -> Result(true, Effect.ToggleFullscreen)
            else -> Result(false, Effect.None)
        }
        KeyAction.VolumeUp -> step(VOLUME_STEP)
        KeyAction.VolumeDown -> step(-VOLUME_STEP)
        KeyAction.ToggleMute -> {
            muted = !muted
            Result(true, Effect.None)
        }
        KeyAction.Disconnect -> Result(true, Effect.Disconnect)
    }

    private fun step(delta: Float): Result {
        volume = (volume + delta).coerceIn(0f, 1f)
        muted = false
        return Result(true, Effect.None)
    }

    class Result(val consumed: Boolean, val effect: Effect)

    companion object {
        const val VOLUME_STEP = 0.05f
    }
}

/** How long the master volume takes to rise from silence at the start of each session. */
internal const val FADE_IN_MILLIS = 3_000L

/** The master fade-in multiplier 0..1 [elapsedMillis] after the session started: a straight ramp from silence. */
internal fun fadeIn(elapsedMillis: Long, rampMillis: Long = FADE_IN_MILLIS): Float =
    (elapsedMillis.toDouble() / rampMillis).coerceIn(0.0, 1.0).toFloat()

private const val OVERLAY_IDLE_MILLIS = 15_000L
private const val DRIVERS_IDLE_MILLIS = 60_000L

/** Whether the overlay should close: no input for 15 s, or for 60 s while the Drivers panel is open for reading. */
internal fun overlayExpired(idleMillis: Long, driversOpen: Boolean): Boolean =
    idleMillis >= if (driversOpen) DRIVERS_IDLE_MILLIS else OVERLAY_IDLE_MILLIS
