package org.meshtastic.aether.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import org.meshtastic.aether.theme.PaletteChoice

// Vector fills are tinted away by Icon, so only the alpha of this opaque ink matters; it is not a theme color.
private val INK = SolidColor(Color.Black)
private const val VIEWPORT = 24f
private const val STROKE = 2f

private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, VIEWPORT, VIEWPORT).apply(build).build()

private fun ImageVector.Builder.stroked(block: PathBuilder.() -> Unit) = path(
    fill = null,
    stroke = INK,
    strokeLineWidth = STROKE,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
    pathBuilder = block,
)

private fun ImageVector.Builder.filled(block: PathBuilder.() -> Unit) = path(fill = INK, pathBuilder = block)

private fun PathBuilder.speaker() {
    moveTo(3f, 9.5f)
    lineTo(7f, 9.5f)
    lineTo(12f, 5f)
    lineTo(12f, 19f)
    lineTo(7f, 14.5f)
    lineTo(3f, 14.5f)
    close()
}

/** The HUD toolbar's icons, drawn here as vectors: the app takes no icon library. */
internal object HudIcons {
    val VolumeOn: ImageVector by lazy {
        icon("VolumeOn") {
            filled { speaker() }
            stroked {
                moveTo(15.5f, 9f)
                arcTo(4f, 4f, 0f, false, true, 15.5f, 15f)
                moveTo(18f, 6.5f)
                arcTo(8f, 8f, 0f, false, true, 18f, 17.5f)
            }
        }
    }

    val VolumeOff: ImageVector by lazy {
        icon("VolumeOff") {
            filled { speaker() }
            stroked {
                moveTo(16f, 9.5f)
                lineTo(21f, 14.5f)
                moveTo(21f, 9.5f)
                lineTo(16f, 14.5f)
            }
        }
    }

    /** A disc with its left half filled: the standard look. */
    val LookStandard: ImageVector by lazy {
        icon("LookStandard") {
            stroked {
                moveTo(12f, 4f)
                arcTo(8f, 8f, 0f, true, true, 12f, 20f)
                arcTo(8f, 8f, 0f, true, true, 12f, 4f)
                close()
            }
            filled {
                moveTo(12f, 4f)
                arcTo(8f, 8f, 0f, false, false, 12f, 20f)
                close()
            }
        }
    }

    /** A lens with its beam: the projector look. */
    val LookProjector: ImageVector by lazy {
        icon("LookProjector") {
            stroked {
                moveTo(3f, 7f)
                lineTo(13f, 7f)
                lineTo(13f, 17f)
                lineTo(3f, 17f)
                close()
                moveTo(13f, 10f)
                lineTo(21f, 6f)
                moveTo(13f, 14f)
                lineTo(21f, 18f)
            }
        }
    }

    /** A crescent: the OLED night look. */
    val LookOled: ImageVector by lazy {
        icon("LookOled") {
            filled {
                moveTo(20f, 14.5f)
                arcTo(8f, 8f, 0f, true, true, 9.5f, 4f)
                arcTo(6.5f, 6.5f, 0f, false, false, 20f, 14.5f)
                close()
            }
        }
    }

    val Drivers: ImageVector by lazy {
        icon("Drivers") {
            stroked {
                for (y in listOf(7f, 12f, 17f)) {
                    moveTo(4f, y)
                    lineTo(5f, y)
                    moveTo(9f, y)
                    lineTo(20f, y)
                }
            }
        }
    }

    /** Headphones: a band over two ear cups. */
    val Headphones: ImageVector by lazy {
        icon("Headphones") {
            stroked {
                moveTo(4f, 15f)
                lineTo(4f, 12f)
                arcTo(8f, 8f, 0f, false, true, 20f, 12f)
                lineTo(20f, 15f)
                moveTo(4f, 15f)
                lineTo(6.5f, 15f)
                lineTo(6.5f, 20f)
                lineTo(4f, 20f)
                close()
                moveTo(20f, 15f)
                lineTo(17.5f, 15f)
                lineTo(17.5f, 20f)
                lineTo(20f, 20f)
                close()
            }
        }
    }

    val Fullscreen: ImageVector by lazy {
        icon("Fullscreen") {
            stroked {
                moveTo(4f, 9f)
                lineTo(4f, 4f)
                lineTo(9f, 4f)
                moveTo(15f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 9f)
                moveTo(20f, 15f)
                lineTo(20f, 20f)
                lineTo(15f, 20f)
                moveTo(9f, 20f)
                lineTo(4f, 20f)
                lineTo(4f, 15f)
            }
        }
    }

    /** The power glyph: Disconnect. */
    val Disconnect: ImageVector by lazy {
        icon("Disconnect") {
            stroked {
                moveTo(12f, 3f)
                lineTo(12f, 11f)
                moveTo(7f, 6.5f)
                arcTo(8f, 8f, 0f, true, false, 17f, 6.5f)
            }
        }
    }

    fun look(choice: PaletteChoice): ImageVector = when (choice) {
        PaletteChoice.Standard -> LookStandard
        PaletteChoice.Projector -> LookProjector
        PaletteChoice.OledNight -> LookOled
    }
}
