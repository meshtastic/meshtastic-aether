package org.meshtastic.aether.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
    ToggleHeadphones,
}

/**
 * H overlay, F fullscreen, Esc close or leave fullscreen, Up and Down volume, M mute, P headphones, D disconnect; null for any other key.
 * A TV remote has no letters: Select (D-pad center) opens the HUD and Play/Pause mutes. A focused HUD button
 * consumes Select first, so the HUD's own buttons still work.
 */
internal fun keyActionFor(key: Key): KeyAction? = when (key) {
    Key.H, Key.DirectionCenter -> KeyAction.ToggleOverlay
    Key.F -> KeyAction.ToggleFullscreen
    Key.Escape -> KeyAction.Escape
    Key.DirectionUp -> KeyAction.VolumeUp
    Key.DirectionDown -> KeyAction.VolumeDown
    Key.M, Key.MediaPlayPause -> KeyAction.ToggleMute
    Key.D -> KeyAction.Disconnect
    Key.P -> KeyAction.ToggleHeadphones
    else -> null
}

/** Something the host or the app must do after a key; [Controls] only changes its own state. */
internal enum class Effect { None, ToggleFullscreen, Disconnect }

/** How long the second Disconnect press may follow the first. */
internal const val DISCONNECT_CONFIRM_MILLIS = 3_000L

/**
 * The two-press guard on Disconnect, shared by the button and the D key: the first press arms it and shows a hint, a
 * second within [DISCONNECT_CONFIRM_MILLIS] confirms. Time is passed in, so it tests without a clock.
 */
internal class DisconnectConfirm {
    private var armedAt by mutableLongStateOf(NOT_ARMED)

    /** True from the first press until it is confirmed or expires; drives the hint. */
    val armed: Boolean get() = armedAt != NOT_ARMED

    /** A press at [nowMillis]: true when it confirms an armed press, false when it only arms (or re-arms after expiry). */
    fun press(nowMillis: Long): Boolean {
        val first = armedAt
        if (first != NOT_ARMED && nowMillis - first in 0 until DISCONNECT_CONFIRM_MILLIS) {
            armedAt = NOT_ARMED
            return true
        }
        armedAt = nowMillis
        return false
    }

    /** Drops the armed state, after the window passed or the HUD went away. */
    fun clear() {
        armedAt = NOT_ARMED
    }

    private companion object {
        const val NOT_ARMED = Long.MIN_VALUE
    }
}

/** The viewer's controls: overlay, volume and mute. Plain state, so a key table can be tested without a window. */
internal class Controls(volume: Float = 1f, headphones: Boolean = false) {
    /** Whether the sound is placed for headphones; the engine and the saved setting follow it. */
    var headphones by mutableStateOf(headphones)

    var overlayOpen by mutableStateOf(false)

    val disconnect = DisconnectConfirm()

    /** Keyboard or screen-reader focus is inside the HUD; the auto-hide timer waits. */
    var focusWithin by mutableStateOf(false)

    /** A pointer is down on a HUD control; the auto-hide timer waits. */
    var pressed by mutableStateOf(false)

    /** A HUD menu is open (it takes focus into its own popup); the auto-hide timer waits. */
    var menuOpen by mutableStateOf(false)

    /** Whether the viewer is using a HUD control right now. */
    val held: Boolean get() = focusWithin || pressed || menuOpen

    /** Whether the overlay lists the mesh-driven parameters; shown only while the overlay is. */
    var driversOpen by mutableStateOf(false)

    /** Volume 0..1 before muting. */
    var volume by mutableFloatStateOf(volume.coerceIn(0f, 1f))

    var muted by mutableStateOf(false)

    /** What the engine plays: silent while muted. */
    val effectiveVolume: Float get() = if (muted) 0f else volume

    /**
     * Applies [action]. [fullscreen] is whether the host is fullscreen now and [canFullscreen] whether it has a switch;
     * the result says what the caller must do and, through [consumed], whether the key was used. [now] times the
     * Disconnect confirmation; its first press opens the overlay so the hint is on screen.
     */
    fun apply(action: KeyAction, fullscreen: Boolean, canFullscreen: Boolean, now: Long = nowMillis()): Result = when (action) {
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
        KeyAction.ToggleHeadphones -> {
            headphones = !headphones
            Result(true, Effect.None)
        }
        KeyAction.Disconnect -> if (disconnect.press(now)) {
            Result(true, Effect.Disconnect)
        } else {
            overlayOpen = true
            Result(true, Effect.None)
        }
    }

    /**
     * The Back key while the scene is up: closes the HUD and says so, or returns false when there is nothing to close
     * and the system's own Back (leave the session) applies. A focused HUD control would otherwise spend the first
     * Back on its focus.
     */
    fun back(): Boolean {
        if (!overlayOpen) return false
        overlayOpen = false
        return true
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

/**
 * Whether the overlay should close: no input for 15 s, or for 60 s while the Drivers panel is open for reading; never
 * while a control is [held] (focused or pressed).
 */
internal fun overlayExpired(idleMillis: Long, driversOpen: Boolean, held: Boolean = false): Boolean =
    !held && idleMillis >= if (driversOpen) DRIVERS_IDLE_MILLIS else OVERLAY_IDLE_MILLIS
