@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.meshtastic.aether.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.meshtastic.aether.visual.SceneModel

/** Meshtastic brand colors and the tonal scales the theme uses, from the client design standards, sections 7.1-7.6. */
object Brand {
    val Accent = Color(0xFF67EA94)
    val Primary = Color(0xFF2C2D3C)
    val Neutral950 = Color(0xFF0F1017)
    val Neutral900 = Color(0xFF1A1B26)
    val Neutral800 = Color(0xFF2C2D3C)
    val Neutral700 = Color(0xFF3D3E50)
    val Neutral400 = Color(0xFF9496A6)
    val Neutral300 = Color(0xFFB8BAC8)
    val Neutral200 = Color(0xFFD5D6E0)
    val Neutral100 = Color(0xFFECEDF3)
    val NeutralVariant700 = Color(0xFF444660)
    val NeutralVariant500 = Color(0xFF767892)
    val NeutralVariant300 = Color(0xFFBDBFCF)
    val Green700 = Color(0xFF2D8F52)
    val Green400 = Color(0xFF8FF0B2)
    val Green300 = Color(0xFFB5F5CE)
    val Blue950 = Color(0xFF001849)
    val Blue700 = Color(0xFF2855A8)
    val Blue300 = Color(0xFFB0BFF0)
    val Blue50 = Color(0xFFE8EAF6)
    val Error800 = Color(0xFF690005)
    val Error700 = Color(0xFF93000A)
    val Error300 = Color(0xFFFFB4AB)
    val Error100 = Color(0xFFFDEAEA)
}

/** Section 8.3's surfaceContainer, interpolated between Neutral 900 and 800: the one scheme color off the scales. */
private val SurfaceContainer = Color(0xFF242533)

/** The design standards' dark scheme role mapping, section 8.3. */
fun ambientColorScheme(): ColorScheme = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Brand.Neutral950,
    primaryContainer = Brand.Green700,
    onPrimaryContainer = Brand.Green300,
    secondary = Brand.Neutral300,
    onSecondary = Brand.Neutral900,
    secondaryContainer = Brand.Neutral700,
    onSecondaryContainer = Brand.Neutral200,
    tertiary = Brand.Blue300,
    onTertiary = Brand.Blue950,
    tertiaryContainer = Brand.Blue700,
    onTertiaryContainer = Brand.Blue50,
    error = Brand.Error300,
    onError = Brand.Error800,
    errorContainer = Brand.Error700,
    onErrorContainer = Brand.Error100,
    background = Brand.Neutral900,
    onBackground = Brand.Neutral100,
    surface = Brand.Neutral900,
    onSurface = Brand.Neutral100,
    surfaceVariant = Brand.NeutralVariant700,
    onSurfaceVariant = Brand.NeutralVariant300,
    outline = Brand.NeutralVariant500,
    outlineVariant = Brand.NeutralVariant700,
    inverseSurface = Brand.Neutral100,
    inverseOnSurface = Brand.Neutral800,
    inversePrimary = Brand.Green700,
    surfaceTint = Brand.Accent,
    scrim = Color.Black,
    surfaceDim = Brand.Neutral950,
    surfaceBright = Brand.Neutral700,
    surfaceContainerLowest = Brand.Neutral950,
    surfaceContainerLow = Brand.Neutral900,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = Brand.Neutral800,
    surfaceContainerHighest = Brand.Neutral700,
)

/**
 * Every color the ambient scene and its overlay draw. [dimFloor] is the dimmest the scene gets at the deepest night
 * (1.0 is never dimmed).
 */
data class AmbientPalette(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val haze: Color,
    val edge: Color,
    val anchor: Color,
    val text: Color,
    val dimFloor: Double,
) {
    /** Scene brightness multiplier at [minuteOfDay]: 1.0 from 07:00 to 22:00, [dimFloor] from 00:00 to 05:00. */
    fun dim(minuteOfDay: Int): Double {
        val dimmed = SceneModel.nightDim(minuteOfDay) - SceneModel.NIGHT_DIM_MIN
        val night = (dimmed / (1.0 - SceneModel.NIGHT_DIM_MIN)).coerceIn(0.0, 1.0)
        return dimFloor + (1.0 - dimFloor) * night
    }
}

/** The selectable looks; the overlay offers these and the UI persists the choice. */
enum class PaletteChoice(val label: String) {
    Standard("Standard"),
    Projector("Projector"),
    OledNight("OLED night"),
    ;

    val palette: AmbientPalette
        get() = when (this) {
            Standard -> STANDARD
            Projector -> PROJECTOR
            OledNight -> OLED_NIGHT
        }
}

/** Neutral 900 at the top, down to Neutral 950. */
private val STANDARD = AmbientPalette(
    backgroundTop = Brand.Neutral900,
    backgroundBottom = Brand.Neutral950,
    haze = Brand.Neutral100,
    edge = Brand.Neutral300,
    anchor = Brand.Accent,
    text = Brand.Neutral100,
    dimFloor = 0.65,
)

/** Brighter background value and a high dim floor: a projector washes out a dark picture and a lit room adds to it. */
private val PROJECTOR = AmbientPalette(
    backgroundTop = Brand.Primary,
    backgroundBottom = Brand.Neutral900,
    haze = Brand.Neutral100,
    edge = Brand.Neutral200,
    anchor = Brand.Accent,
    text = Brand.Neutral100,
    dimFloor = 0.8,
)

/**
 * Neutral 950 over true black (standards section 2 allows pure black in dark mode) so an OLED pixel is off, and a deep
 * dim at night.
 */
private val OLED_NIGHT = AmbientPalette(
    backgroundTop = Brand.Neutral950,
    backgroundBottom = Color.Black,
    haze = Brand.Neutral400,
    edge = Brand.Neutral400,
    anchor = Brand.Accent,
    text = Brand.Neutral200,
    dimFloor = 0.25,
)

val LocalAmbientPalette = staticCompositionLocalOf { PaletteChoice.Standard.palette }

/**
 * Expressive Material theme with the standards' dark scheme and the calm standard motion scheme, and the chosen
 * [AmbientPalette] for the scene.
 */
@Composable
fun AmbientTheme(choice: PaletteChoice = PaletteChoice.Standard, content: @Composable () -> Unit) {
    val scheme = ambientColorScheme()
    CompositionLocalProvider(LocalAmbientPalette provides choice.palette) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.standard()) {
            // Plain Text outside a Surface would take the default content color, black, and vanish on the dark field.
            CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
        }
    }
}
