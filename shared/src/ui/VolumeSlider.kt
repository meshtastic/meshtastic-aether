package org.meshtastic.aether.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private const val PERCENT = 100
private val SLIDER_WIDTH = 140.dp

/** The HUD's volume slider; moving it unmutes. */
@Composable
internal fun VolumeSlider(controls: Controls, isTelevision: Boolean) {
    val focus = LocalFocusManager.current
    val remote = if (isTelevision) Modifier.onPreviewKeyEvent { televisionSliderKey(it, controls, focus) } else Modifier
    Slider(
        value = controls.volume,
        onValueChange = {
            controls.volume = it
            controls.muted = false
        },
        modifier = Modifier.width(SLIDER_WIDTH).heightIn(min = MinTouch)
            .then(remote)
            .semantics {
                contentDescription = "Volume"
                val percent = (controls.volume * PERCENT).roundToInt()
                stateDescription = if (controls.muted) "$percent percent, muted" else "$percent percent"
            },
    )
}

/**
 * On a television the volume slider must not hold the D-pad: Left and Right move focus along the toolbar and Up and
 * Down change the volume, the same as the Up and Down keys do from the scene. Everything else reaches the slider.
 */
private fun televisionSliderKey(event: KeyEvent, controls: Controls, focus: FocusManager): Boolean {
    val down = event.type == KeyEventType.KeyDown
    val direction = when (event.key) {
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
    val volume = when (event.key) {
        Key.DirectionUp -> KeyAction.VolumeUp
        Key.DirectionDown -> KeyAction.VolumeDown
        else -> null
    }
    if (down && direction != null) focus.moveFocus(direction)
    if (down && volume != null) controls.apply(volume, fullscreen = false, canFullscreen = false)
    // The slider has no click, so Select would fall through to the scene and close the HUD.
    return direction != null || volume != null || event.key == Key.DirectionCenter
}
