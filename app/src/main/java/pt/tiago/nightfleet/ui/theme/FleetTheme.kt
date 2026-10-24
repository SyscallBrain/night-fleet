package pt.tiago.nightfleet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import pt.tiago.nightfleet.R

// The only place in the app where colour literals are allowed.
// Every token is spelled out for each theme so that the contrast test in app/src/test can check them.

/** The four visual directions. B is the main one; A, C and D are selectable in Settings. */
enum class ThemeId {
    /** B · Carta Náutica (default). */
    CHART,

    /** A · Sonar Abissal. */
    SONAR,

    /** C · Frota de Papel. */
    PAPER,

    /** D · Holo Tático. */
    HOLO,
}

/** Colour-blind palettes; they replace `hit`, `miss` and `accent` in every theme. */
enum class ColorblindPalette { NORMAL, DEUTERANOPIA, PROTANOPIA, TRITANOPIA }

/** Colour tokens. Nothing outside this package uses colour literals. */
data class FleetColors(
    val bg: Color,
    val bg2: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val sea: Color,
    val grid: Color,
    val hit: Color,
    val miss: Color,
    val ship: Color,
    val shipEdge: Color,
    val island: Color,
    val danger: Color,
    val difficultyEasy: Color,
    val difficultyMedium: Color,
    val difficultyHard: Color,
)

/** Corner radii per theme. Sheets use [sheet] in every theme. */
data class FleetShapes(
    val card: Dp,
    val button: Dp,
    val cell: Dp,
    val sheet: Dp = 28.dp,
)

/** Font families and the type scale. */
data class FleetType(
    /** Titles and the brand: IM FELL, Chakra Petch, Fredoka or Oxanium. */
    val display: FontFamily,
    /** Body and controls: Figtree. */
    val body: FontFamily,
    /** Coordinates and counts: IBM Plex Mono. */
    val data: FontFamily,
    val brand: TextStyle,
    val screenTitle: TextStyle,
    val seaName: TextStyle,
    val cardTitle: TextStyle,
    val bodyText: TextStyle,
    val button: TextStyle,
    val caption: TextStyle,
    val hudMission: TextStyle,
    val hudShots: TextStyle,
    val coordinate: TextStyle,
    val label: TextStyle,
)

/** Shared difficulty chip colours (text colour on a 15 % background, same in every theme). */
private val DifficultyEasy = Color(0xFF9ECE6A)
private val DifficultyMedium = Color(0xFFE0AF68)
private val DifficultyHard = Color(0xFFF7768E)

/** Grand Admiral gold: replaces `accent` and `shipEdge` when the finish is unlocked. */
private val GrandAdmiralGold = Color(0xFFE8C66A)

/** Colour-blind overrides. Validated with deltaE2000 in the JVM tests. */
private data class ColorblindOverride(val hit: Color, val miss: Color, val accent: Color)

private fun colorblindOverride(palette: ColorblindPalette): ColorblindOverride? = when (palette) {
    ColorblindPalette.NORMAL -> null
    ColorblindPalette.DEUTERANOPIA -> ColorblindOverride(
        hit = Color(0xFFF28E2B), miss = Color(0xFF4E9BFF), accent = Color(0xFFF1CE63),
    )
    // Protanopia: the accent is yellow like the hit colour, so star highlights should use `text`.
    ColorblindPalette.PROTANOPIA -> ColorblindOverride(
        hit = Color(0xFFF5C342), miss = Color(0xFF4E9BFF), accent = Color(0xFFF5C342),
    )
    // Tritanopia: the first accent (pink #F7A1C4) fell to ΔE2000 8 from the teal miss under deuteranopia and 11
    // under protanopia. The gold #F1CE63 (the deuteranopia accent) keeps 25 and 35.
    ColorblindPalette.TRITANOPIA -> ColorblindOverride(
        hit = Color(0xFFE8505B), miss = Color(0xFF2EC4B6), accent = Color(0xFFF1CE63),
    )
}

/** Base colours of one theme, before colour-blind and Grand Admiral overrides. */
fun themeColors(themeId: ThemeId): FleetColors = when (themeId) {
    ThemeId.CHART -> FleetColors(
        bg = Color(0xFF0B1528),
        bg2 = Color(0xFF13233F),
        surface = Color(0xFF13243F),
        surface2 = Color(0xFF1A2E50),
        line = Color(0xFF2C4670),
        text = Color(0xFFF2E8D0),
        textMuted = Color(0xFFA9A58F),
        accent = Color(0xFFE0B154),
        onAccent = Color(0xFF1B1306),
        sea = Color(0xFF102544),
        grid = Color(0xFF1B3459),
        hit = Color(0xFFD2493A),
        miss = Color(0xFF97A6C4),
        ship = Color(0xFF263E64),
        shipEdge = Color(0xFFE0B154),
        island = Color(0xFF3D3524),
        danger = Color(0xFFD2493A),
        difficultyEasy = DifficultyEasy,
        difficultyMedium = DifficultyMedium,
        difficultyHard = DifficultyHard,
    )
    ThemeId.SONAR -> FleetColors(
        bg = Color(0xFF03100E),
        bg2 = Color(0xFF06221E),
        surface = Color(0xFF0A201D),
        surface2 = Color(0xFF0E2C28),
        line = Color(0xFF1A4A41),
        text = Color(0xFFD4FFEE),
        textMuted = Color(0xFF79B2A3),
        accent = Color(0xFF3CFFB4),
        onAccent = Color(0xFF02130C),
        sea = Color(0xFF05201B),
        grid = Color(0xFF0C302A),
        hit = Color(0xFFFFB23C),
        miss = Color(0xFF3D8A77),
        ship = Color(0xFF145846),
        shipEdge = Color(0xFF3CFFB4),
        island = Color(0xFF22352B),
        danger = Color(0xFFFF6B6B),
        difficultyEasy = DifficultyEasy,
        difficultyMedium = DifficultyMedium,
        difficultyHard = DifficultyHard,
    )
    ThemeId.PAPER -> FleetColors(
        bg = Color(0xFF17204A),
        bg2 = Color(0xFF242E63),
        surface = Color(0xFF252F65),
        surface2 = Color(0xFF2E3976),
        line = Color(0xFF414D90),
        text = Color(0xFFFFF7EA),
        textMuted = Color(0xFFBBBEE2),
        accent = Color(0xFFFFC75A),
        onAccent = Color(0xFF2B1A00),
        sea = Color(0xFF1F2B68),
        grid = Color(0xFF2A3780),
        hit = Color(0xFFFF6F61),
        miss = Color(0xFFA3B5FF),
        ship = Color(0xFFF1E3CF),
        shipEdge = Color(0xFFFFFFFF),
        island = Color(0xFF2E7A63),
        danger = Color(0xFFFF6F61),
        difficultyEasy = DifficultyEasy,
        difficultyMedium = DifficultyMedium,
        difficultyHard = DifficultyHard,
    )
    ThemeId.HOLO -> FleetColors(
        bg = Color(0xFF050911),
        bg2 = Color(0xFF0B1424),
        surface = Color(0xFF0C1627),
        surface2 = Color(0xFF122038),
        line = Color(0xFF1F3757),
        text = Color(0xFFE8F4FF),
        textMuted = Color(0xFF7F99BA),
        accent = Color(0xFFFF8B3D),
        onAccent = Color(0xFF1E0C00),
        sea = Color(0xFF08121F),
        grid = Color(0xFF11223A),
        hit = Color(0xFFFF8B3D),
        miss = Color(0xFF3DD6FF),
        ship = Color(0xFF0E3653),
        shipEdge = Color(0xFF3DD6FF),
        island = Color(0xFF18203A),
        danger = Color(0xFFFF4D6D),
        difficultyEasy = DifficultyEasy,
        difficultyMedium = DifficultyMedium,
        difficultyHard = DifficultyHard,
    )
}

/**
 * Final colours for a theme, applying the colour-blind palette first and the Grand Admiral finish last.
 * Pure function, so the JVM tests can check every combination.
 */
fun fleetColors(
    themeId: ThemeId,
    palette: ColorblindPalette = ColorblindPalette.NORMAL,
    grandAdmiral: Boolean = false,
): FleetColors {
    var colors = themeColors(themeId)
    colorblindOverride(palette)?.let { override ->
        colors = colors.copy(hit = override.hit, miss = override.miss, accent = override.accent)
    }
    if (grandAdmiral) {
        colors = colors.copy(accent = GrandAdmiralGold, shipEdge = GrandAdmiralGold)
    }
    return colors
}

/** Corner radii per theme. */
fun fleetShapes(themeId: ThemeId): FleetShapes = when (themeId) {
    ThemeId.CHART -> FleetShapes(card = 4.dp, button = 4.dp, cell = 1.dp)
    ThemeId.SONAR -> FleetShapes(card = 8.dp, button = 6.dp, cell = 2.dp)
    ThemeId.PAPER -> FleetShapes(card = 20.dp, button = 16.dp, cell = 7.dp)
    ThemeId.HOLO -> FleetShapes(card = 6.dp, button = 4.dp, cell = 2.dp)
}

/**
 * Builds the type scale for the given font families. Screens below 360 dp wide use
 * a smaller brand (36/34 sp) themselves; this is the default size.
 */
fun fleetType(display: FontFamily, body: FontFamily, data: FontFamily): FleetType {
    return FleetType(
        display = display,
        body = body,
        data = data,
        brand = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 40.sp),
        screenTitle = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        seaName = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp),
        cardTitle = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        bodyText = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
        button = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        caption = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        hudMission = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
        // Figtree with tabular figures for HUD numbers in every theme (IM FELL has old-style figures).
        hudShots = TextStyle(
            fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 24.sp,
            fontFeatureSettings = "tnum",
        ),
        coordinate = TextStyle(fontFamily = data, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 12.sp),
        label = TextStyle(
            fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp,
            letterSpacing = 0.1.em,
        ),
    )
}

/** Resource-backed font families for a theme. Must be called from composition. */
@Composable
fun rememberFleetType(themeId: ThemeId): FleetType {
    val body = FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
    )
    val data = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    )
    // Variable fonts (Fredoka, Oxanium) are listed at each weight; Android sets the wght axis from it.
    val display = when (themeId) {
        ThemeId.CHART -> FontFamily(Font(R.font.imfell_english_sc, FontWeight.Normal))
        ThemeId.SONAR -> FontFamily(
            Font(R.font.chakra_petch_medium, FontWeight.Medium),
            Font(R.font.chakra_petch_bold, FontWeight.Bold),
        )
        ThemeId.PAPER -> FontFamily(
            Font(R.font.fredoka_variable, FontWeight.Medium),
            Font(R.font.fredoka_variable, FontWeight.SemiBold),
            Font(R.font.fredoka_variable, FontWeight.Bold),
        )
        ThemeId.HOLO -> FontFamily(
            Font(R.font.oxanium_variable, FontWeight.Medium),
            Font(R.font.oxanium_variable, FontWeight.Bold),
            Font(R.font.oxanium_variable, FontWeight.ExtraBold),
        )
    }
    return remember(themeId, display, body, data) { fleetType(display, body, data) }
}

val LocalFleetColors = staticCompositionLocalOf { fleetColors(ThemeId.CHART) }
val LocalFleetShapes = staticCompositionLocalOf { fleetShapes(ThemeId.CHART) }
val LocalFleetType = staticCompositionLocalOf<FleetType> { error("FleetTheme() is missing from the composition") }

/**
 * The Material 3 colour roles mapped from the Night Fleet tokens. Pure, so the JVM tests can check that every
 * role of every theme comes from a token. Material defaults (purple containers, black content) must never show.
 */
fun fleetColorScheme(colors: FleetColors): ColorScheme = darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.accent,
    onPrimaryContainer = colors.onAccent,
    inversePrimary = colors.accent,
    secondary = colors.textMuted,
    onSecondary = colors.bg,
    secondaryContainer = colors.surface2,
    onSecondaryContainer = colors.text,
    tertiary = colors.accent,
    onTertiary = colors.onAccent,
    tertiaryContainer = colors.surface2,
    onTertiaryContainer = colors.text,
    background = colors.bg,
    onBackground = colors.text,
    surface = colors.surface,
    onSurface = colors.text,
    surfaceVariant = colors.surface2,
    onSurfaceVariant = colors.textMuted,
    surfaceTint = colors.accent,
    inverseSurface = colors.text,
    inverseOnSurface = colors.bg,
    surfaceBright = colors.surface2,
    surfaceDim = colors.bg,
    surfaceContainerLowest = colors.bg,
    surfaceContainerLow = colors.surface,
    surfaceContainer = colors.surface,
    surfaceContainerHigh = colors.surface2,
    surfaceContainerHighest = colors.surface2,
    error = colors.danger,
    onError = colors.bg,
    errorContainer = colors.danger,
    onErrorContainer = colors.text,
    outline = colors.line,
    outlineVariant = colors.line,
    scrim = Color.Black,
)

/**
 * Provides the Night Fleet tokens to every child, and a Material 3 colour scheme derived from them so that
 * default Material components follow the theme too.
 */
@Composable
fun FleetTheme(
    themeId: ThemeId = ThemeId.CHART,
    palette: ColorblindPalette = ColorblindPalette.NORMAL,
    grandAdmiral: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = remember(themeId, palette, grandAdmiral) { fleetColors(themeId, palette, grandAdmiral) }
    val shapes = remember(themeId) { fleetShapes(themeId) }
    val type = rememberFleetType(themeId)
    val scheme = fleetColorScheme(colors)
    val typography = Typography(
        titleLarge = type.screenTitle,
        bodyLarge = type.bodyText,
        labelLarge = type.button,
    )
    CompositionLocalProvider(
        LocalFleetColors provides colors,
        LocalFleetShapes provides shapes,
        LocalFleetType provides type,
        // Default content colour for Text, Icon and IconButton that set no colour: the theme text, never black.
        LocalContentColor provides colors.text,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
