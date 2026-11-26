package org.meshtastic.ambient.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.meshtastic.ambient.visual.SceneModel

/** Meshtastic brand and neutral colors from the client design standards, sections 7.1-7.3. */
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
}

/** The design standards' dark scheme role mapping, section 8.3. */
fun ambientColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF67EA94),
    onPrimary = Color(0xFF0F1017),
    primaryContainer = Color(0xFF2D8F52),
    onPrimaryContainer = Color(0xFFB5F5CE),
    secondary = Color(0xFFB8BAC8),
    onSecondary = Color(0xFF1A1B26),
    secondaryContainer = Color(0xFF3D3E50),
    onSecondaryContainer = Color(0xFFD5D6E0),
    tertiary = Color(0xFFB0BFF0),
    onTertiary = Color(0xFF001849),
    tertiaryContainer = Color(0xFF2855A8),
    onTertiaryContainer = Color(0xFFE8EAF6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFDEAEA),
    background = Color(0xFF1A1B26),
    onBackground = Color(0xFFECEDF3),
    surface = Color(0xFF1A1B26),
    onSurface = Color(0xFFECEDF3),
    surfaceVariant = Color(0xFF444660),
    onSurfaceVariant = Color(0xFFBDBFCF),
    outline = Color(0xFF767892),
    outlineVariant = Color(0xFF444660),
    inverseSurface = Color(0xFFECEDF3),
    inverseOnSurface = Color(0xFF2C2D3C),
    inversePrimary = Color(0xFF2D8F52),
    surfaceTint = Color(0xFF67EA94),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF0F1017),
    surfaceBright = Color(0xFF3D3E50),
    surfaceContainerLowest = Color(0xFF0F1017),
    surfaceContainerLow = Color(0xFF1A1B26),
    surfaceContainer = Color(0xFF242533),
    surfaceContainerHigh = Color(0xFF2C2D3C),
    surfaceContainerHighest = Color(0xFF3D3E50),
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
    /** Scene brightness multiplier at [minuteOfDay]: 1.0 in the afternoon, [dimFloor] at 02:00. */
    fun dim(minuteOfDay: Int): Double {
        val night = ((SceneModel.nightDim(minuteOfDay) - SceneModel.NIGHT_DIM_MIN) / (1.0 - SceneModel.NIGHT_DIM_MIN)).coerceIn(0.0, 1.0)
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

/** Neutral 900 toward Primary at the top, down to Neutral 950. */
private val STANDARD = AmbientPalette(
    backgroundTop = Color(0xFF1F2030),
    backgroundBottom = Brand.Neutral950,
    haze = Brand.Neutral100,
    edge = Brand.Neutral300,
    anchor = Brand.Accent,
    text = Brand.Neutral100,
    dimFloor = 0.45,
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

/** True black at the bottom so an OLED pixel is off, and a deep dim at night. */
private val OLED_NIGHT = AmbientPalette(
    backgroundTop = Color(0xFF07070B),
    backgroundBottom = Color(0xFF000000),
    haze = Brand.Neutral400,
    edge = Brand.Neutral400,
    anchor = Brand.Accent,
    text = Brand.Neutral200,
    dimFloor = 0.25,
)

val LocalAmbientPalette = staticCompositionLocalOf { PaletteChoice.Standard.palette }

/** Material theme with the standards' dark scheme, and the chosen [AmbientPalette] for the scene. */
@Composable
fun AmbientTheme(choice: PaletteChoice = PaletteChoice.Standard, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAmbientPalette provides choice.palette) {
        MaterialTheme(colorScheme = ambientColorScheme(), content = content)
    }
}
