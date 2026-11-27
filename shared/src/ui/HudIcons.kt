package org.meshtastic.aether.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.theme.PaletteChoice

// Vector fills are tinted away by Icon, so only the alpha of this opaque ink matters; it is not a theme color.
private val INK = SolidColor(Color.Black)
private const val VIEWPORT = 24f
private const val STROKE = 2f

private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, VIEWPORT, VIEWPORT).apply(build).build()

/** Strokes SVG path data, in the 24 unit viewport, with round caps and joins. */
private fun ImageVector.Builder.stroked(pathData: String) = addPath(
    addPathNodes(pathData),
    fill = null,
    stroke = INK,
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
)

/** Fills SVG path data, in the 24 unit viewport. */
private fun ImageVector.Builder.filled(pathData: String) = addPath(addPathNodes(pathData), fill = INK)

private const val SPEAKER = "M3 9.5 L7 9.5 L12 5 L12 19 L7 14.5 L3 14.5 Z"

/** The HUD toolbar's icons, drawn here as SVG path data: the app takes no icon library. */
internal object HudIcons {
    val VolumeOn: ImageVector by lazy {
        icon("VolumeOn") {
            filled(SPEAKER)
            stroked("M15.5 9 A4 4 0 0 1 15.5 15 M18 6.5 A8 8 0 0 1 18 17.5")
        }
    }

    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            filled(SPEAKER)
            stroked("M16 9.5 L21 14.5 M21 9.5 L16 14.5")
        }
    }

    /** A disc with its left half filled: the standard look. */
    val LookStandard: ImageVector by lazy {
        icon("LookStandard") {
            stroked("M12 4 A8 8 0 1 1 12 20 A8 8 0 1 1 12 4 Z")
            filled("M12 4 A8 8 0 0 0 12 20 Z")
        }
    }

    /** A lens with its beam: the projector look. */
    val LookProjector: ImageVector by lazy {
        icon("LookProjector") { stroked("M3 7 L13 7 L13 17 L3 17 Z M13 10 L21 6 M13 14 L21 18") }
    }

    /** A crescent: the OLED night look. */
    val LookOled: ImageVector by lazy {
        icon("LookOled") { filled("M20 14.5 A8 8 0 1 1 9.5 4 A6.5 6.5 0 0 0 20 14.5 Z") }
    }

    /** Three slider rows, each a short tick and a long bar. */
    val Drivers: ImageVector by lazy {
        icon("Drivers") {
            stroked("M4 7 L5 7 M9 7 L20 7 M4 12 L5 12 M9 12 L20 12 M4 17 L5 17 M9 17 L20 17")
        }
    }

    /** Headphones: a band over two ear cups. */
    val Headphones: ImageVector by lazy {
        icon("Headphones") {
            stroked(
                "M4 15 L4 12 A8 8 0 0 1 20 12 L20 15 " +
                    "M4 15 L6.5 15 L6.5 20 L4 20 Z M20 15 L17.5 15 L17.5 20 L20 20 Z",
            )
        }
    }

    /** A check mark: the selected item in a menu. */
    val Check: ImageVector by lazy {
        icon("Check") { stroked("M5 12.5 L10 17.5 L19 7") }
    }

    val Fullscreen: ImageVector by lazy {
        icon("Fullscreen") {
            stroked("M4 9 L4 4 L9 4 M15 4 L20 4 L20 9 M20 15 L20 20 L15 20 M9 20 L4 20 L4 15")
        }
    }

    /** The power glyph: Disconnect. */
    val Disconnect: ImageVector by lazy {
        icon("Disconnect") { stroked("M12 3 L12 11 M7 6.5 A8 8 0 1 0 17 6.5") }
    }

    /** A ring with eight spokes: settings. */
    val Settings: ImageVector by lazy {
        icon("Settings") {
            stroked(
                "M15.5 12 A3.5 3.5 0 1 1 8.5 12 A3.5 3.5 0 1 1 15.5 12 Z " +
                    "M18.4 12 L21.2 12 M16.53 16.53 L18.51 18.51 M12 18.4 L12 21.2 M7.47 16.53 L5.49 18.51 " +
                    "M5.6 12 L2.8 12 M7.47 7.47 L5.49 5.49 M12 5.6 L12 2.8 M16.53 7.47 L18.51 5.49",
            )
        }
    }

    /** An arrow pointing left: back out of a screen. */
    val Back: ImageVector by lazy {
        icon("Back") { stroked("M19 12 L5 12 M11 6 L5 12 L11 18") }
    }

    /** An arrow down into a tray: an update is ready. */
    val Update: ImageVector by lazy {
        icon("Update") { stroked("M12 4 L12 15 M7.5 10.5 L12 15 L16.5 10.5 M5 19.5 L19 19.5") }
    }

    fun look(choice: PaletteChoice): ImageVector = when (choice) {
        PaletteChoice.Standard -> LookStandard
        PaletteChoice.Projector -> LookProjector
        PaletteChoice.OledNight -> LookOled
    }
}
