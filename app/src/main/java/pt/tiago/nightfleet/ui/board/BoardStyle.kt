package pt.tiago.nightfleet.ui.board

import pt.tiago.nightfleet.ui.theme.ThemeId

/** Shape of the hit mark: the shape is part of the theme, never only the colour. */
enum class HitShape {
    /** B: wax seal, a circle with a darker rim and a small highlight. */
    SEAL,

    /** A: square echo with a glow. */
    ECHO,

    /** C: five-pointed star. */
    STAR,

    /** D: diamond with a glow. */
    DIAMOND,
}

/** Scenery layer drawn over the cells at 0.2 alpha or less. */
enum class OverlayKind {
    /** B: compass rose in a corner and two rhumb lines. */
    COMPASS_ROSE,

    /** A: circular sweep every 5 s with concentric rings. */
    SONAR_SWEEP,

    /** C: reflection of the moon. */
    MOON_REFLECTION,

    /** D: horizontal scan line every 4 s. */
    SCAN_LINE,
}

/** Pattern drawn on islands, on top of the island colour. */
enum class IslandPattern { DOTS, HATCH, TUFTS, LINES }

/** How a hull is drawn for each theme. */
enum class HullStyle {
    /** B: engraved chart ship, filled with a brass outline. */
    ENGRAVED,

    /** A: contour line only. */
    LINE,

    /** C: folded paper boat, light fill with a white edge. */
    FOLDED,

    /** D: light hull with a glow. */
    LIGHT,
}

/** The shape vocabulary of one theme. Colours come from `FleetColors`; this only holds the forms. */
data class BoardStyle(
    val theme: ThemeId,
    val hitShape: HitShape,
    val overlay: OverlayKind,
    /** Period of the overlay animation in ms (sweep or scan line); 0 when the overlay is static. */
    val overlayPeriodMs: Int,
    val island: IslandPattern,
    val hull: HullStyle,
)

/** Board style per theme. Pure, so it is unit-tested. */
fun boardStyle(theme: ThemeId): BoardStyle = when (theme) {
    ThemeId.CHART -> BoardStyle(theme, HitShape.SEAL, OverlayKind.COMPASS_ROSE, 0, IslandPattern.DOTS, HullStyle.ENGRAVED)
    ThemeId.SONAR -> BoardStyle(theme, HitShape.ECHO, OverlayKind.SONAR_SWEEP, 5000, IslandPattern.HATCH, HullStyle.LINE)
    ThemeId.PAPER -> BoardStyle(theme, HitShape.STAR, OverlayKind.MOON_REFLECTION, 0, IslandPattern.TUFTS, HullStyle.FOLDED)
    ThemeId.HOLO -> BoardStyle(theme, HitShape.DIAMOND, OverlayKind.SCAN_LINE, 4000, IslandPattern.LINES, HullStyle.LIGHT)
}

/** Outline of the five-pointed star of theme C, in a 100×100 box (the CSS clip-path of the mockup). */
val STAR_POINTS: List<Pair<Float, Float>> = listOf(
    50f to 6f, 61f to 37f, 94f to 38f, 68f to 58f, 78f to 92f,
    50f to 72f, 22f to 92f, 32f to 58f, 6f to 38f, 39f to 37f,
)

/** Outline of the diamond of theme D, in a 100×100 box. */
val DIAMOND_POINTS: List<Pair<Float, Float>> = listOf(50f to 10f, 90f to 50f, 50f to 90f, 10f to 50f)
