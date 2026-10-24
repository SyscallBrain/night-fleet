package pt.tiago.nightfleet.ui.intro

import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.EffectEvent
import pt.tiago.nightfleet.ui.board.EffectKind
import pt.tiago.nightfleet.ui.board.ReconMark
import pt.tiago.nightfleet.ui.board.ShipOutline
import pt.tiago.nightfleet.ui.board.SonarMark

/**
 * One illustration of an intro card: the final board ([model], the static frame of reduced motion) and the
 * effects that play over it. Pure data, so the JVM tests can check the timing.
 */
data class IntroScene(val model: BoardRenderModel, val effects: List<EffectEvent>) {
    /** When the last effect ends, in ms. The card must stay within [MAX_ANIMATION_MILLIS]. */
    val animationMillis: Long get() = effects.maxOfOrNull { it.startMillis + it.durationMillis } ?: 0L

    /** Pause before the animation starts again. */
    val loopMillis: Long get() = animationMillis + LOOP_PAUSE_MILLIS

    companion object {
        /** Animations of the intro cards last at most 3 s. */
        const val MAX_ANIMATION_MILLIS = 3000L
        const val LOOP_PAUSE_MILLIS = 600L
    }
}

/**
 * The illustrations of the 17 intro cards, drawn with the board kit on a 6×6 sample sea. Each one shows the
 * mechanic with the same effects the battle plays for it.
 */
object IntroScenes {

    private const val SIZE = 6

    fun of(mechanic: MechanicIntro): IntroScene = when (mechanic) {
        MechanicIntro.LIGHTHOUSE_PUZZLE -> scene(
            marks = mapOf(at(0, 0) to CellVisual.WATER, at(5, 0) to CellVisual.WATER),
            ships = listOf(ship(at(0, 1), at(1, 1), at(2, 1)), ship(at(4, 4), at(4, 5))),
            effects = listOf(splash(at(5, 5), 0L), splash(at(3, 3), 500L)),
        )
        MechanicIntro.ISLANDS_REEFS -> scene(
            marks = mapOf(
                at(1, 1) to CellVisual.ISLAND, at(2, 1) to CellVisual.ISLAND, at(1, 2) to CellVisual.ISLAND,
                at(4, 3) to CellVisual.REEF, at(4, 4) to CellVisual.REEF, at(3, 4) to CellVisual.REEF,
            ),
            effects = listOf(splash(at(0, 5), 0L)),
        )
        MechanicIntro.SONAR -> IntroScene(
            model = board(
                sonar = listOf(SonarMark(centerIndex = at(2, 2), count = 2, fresh = true)),
                ships = listOf(ship(at(1, 1), at(1, 2))),
            ),
            effects = listOf(effect(EffectKind.SONAR_PULSE, listOf(at(2, 2)), 0L)),
        )
        MechanicIntro.SHOT_CLOCK -> scene(
            marks = mapOf(at(1, 1) to CellVisual.WATER),
            effects = listOf(splash(at(1, 1), 0L), splash(at(4, 3), 1000L)),
        )
        MechanicIntro.MINES -> scene(
            marks = mapOf(at(3, 2) to CellVisual.MINE, at(1, 4) to CellVisual.WATER),
            effects = listOf(effect(EffectKind.MINE_FLASH, listOf(at(3, 2)), 0L)),
        )
        MechanicIntro.AIR_RECON -> IntroScene(
            model = board(recon = listOf(ReconMark(isRow = true, index = 3, present = true))),
            effects = listOf(effect(EffectKind.RECON_SHADOW, (0 until SIZE).map { at(it, 3) }, 0L, isRow = true)),
        )
        MechanicIntro.SCHOOL_RULES -> scene(
            marks = mapOf(
                at(1, 1) to CellVisual.OWN_SHIP, at(2, 1) to CellVisual.OWN_SHIP,
                at(3, 1) to CellVisual.OWN_SHIP, at(2, 2) to CellVisual.OWN_SHIP,
                at(4, 4) to CellVisual.OWN_SHIP,
            ),
            ships = listOf(ship(at(1, 1), at(2, 1), at(3, 1), at(2, 2)), ship(at(4, 4))),
            effects = listOf(splash(at(0, 5), 0L)),
        )
        MechanicIntro.CONVOY -> scene(
            marks = mapOf(
                at(0, 4) to CellVisual.OWN_SHIP, at(1, 4) to CellVisual.OWN_SHIP,
                at(3, 4) to CellVisual.OWN_SHIP_HIT, at(4, 4) to CellVisual.OWN_SHIP,
                at(4, 1) to CellVisual.OWN_SHIP, at(5, 1) to CellVisual.OWN_SHIP,
            ),
            ships = listOf(ship(at(0, 4), at(1, 4)), ship(at(3, 4), at(4, 4)), ship(at(4, 1), at(5, 1))),
            effects = listOf(effect(EffectKind.EXPLOSION, listOf(at(3, 4)), 0L), splash(at(5, 0), 600L)),
        )
        MechanicIntro.DECOY -> scene(
            marks = mapOf(at(3, 2) to CellVisual.DECOY_WRECK),
            effects = listOf(effect(EffectKind.EXPLOSION, listOf(at(3, 2)), 0L)),
        )
        MechanicIntro.TORPEDO -> scene(
            marks = mapOf(at(4, 3) to CellVisual.HIT),
            ships = listOf(ship(at(4, 3), at(5, 3))),
            effects = listOf(
                effect(EffectKind.TORPEDO_TRAIL, listOf(at(0, 3), at(1, 3), at(2, 3), at(3, 3)), 0L),
                effect(EffectKind.EXPLOSION, listOf(at(4, 3)), 220L),
            ),
        )
        MechanicIntro.DIVING_SUBS -> scene(
            marks = mapOf(at(2, 2) to CellVisual.CONTACT),
            effects = listOf(splash(at(2, 2), 0L)),
        )
        MechanicIntro.REPAIR -> scene(
            marks = mapOf(
                at(1, 2) to CellVisual.OWN_SHIP, at(2, 2) to CellVisual.REPAIRED, at(3, 2) to CellVisual.OWN_SHIP,
            ),
            ships = listOf(ship(at(1, 2), at(2, 2), at(3, 2))),
            effects = listOf(splash(at(2, 2), 0L)),
        )
        MechanicIntro.SALVO -> scene(
            marks = mapOf(at(1, 1) to CellVisual.WATER, at(3, 1) to CellVisual.WATER),
            effects = listOf(
                splash(at(1, 1), 0L),
                splash(at(3, 1), 300L),
                splash(at(1, 4), 600L),
                splash(at(4, 3), 900L),
            ),
        )
        MechanicIntro.CROSS_BARRAGE -> scene(
            marks = mapOf(at(2, 2) to CellVisual.WATER),
            effects = listOf(
                effect(EffectKind.CROSS, listOf(at(2, 2), at(2, 1), at(3, 2), at(2, 3), at(1, 2)), 0L),
            ),
        )
        MechanicIntro.LARGE_GRID -> scene(
            marks = emptyMap(),
            effects = (0 until SIZE).map { splash(at(it, it), it * 150L) },
        )
        MechanicIntro.FOG -> IntroScene(
            model = board(
                marks = mapOf(at(1, 1) to CellVisual.WATER, at(2, 2) to CellVisual.WATER, at(4, 3) to CellVisual.WATER),
                faded = mapOf(at(1, 1) to 0.4f, at(2, 2) to 0.4f),
            ),
            effects = listOf(splash(at(4, 1), 0L), splash(at(5, 4), 800L)),
        )
        MechanicIntro.MOVING_SHIPS -> scene(
            marks = mapOf(at(4, 4) to CellVisual.WATER),
            effects = listOf(splash(at(4, 4), 0L), splash(at(1, 1), 800L)),
        )
    }

    private fun at(column: Int, row: Int): Int = row * SIZE + column

    private fun ship(vararg cells: Int): ShipOutline = ShipOutline(cells = cells.toList(), sunk = false)

    private fun splash(cell: Int, startMillis: Long): EffectEvent =
        effect(EffectKind.SPLASH, listOf(cell), startMillis)

    private fun effect(
        kind: EffectKind,
        cells: List<Int>,
        startMillis: Long,
        isRow: Boolean = true,
    ): EffectEvent = EffectEvent(kind = kind, cells = cells, startMillis = startMillis, seed = cells.first().toLong(), isRow = isRow)

    private fun board(
        marks: Map<Int, CellVisual> = emptyMap(),
        ships: List<ShipOutline> = emptyList(),
        sonar: List<SonarMark> = emptyList(),
        recon: List<ReconMark> = emptyList(),
        faded: Map<Int, Float> = emptyMap(),
    ): BoardRenderModel {
        val cells = MutableList(SIZE * SIZE) { CellVisual.UNKNOWN }
        for ((index, visual) in marks) cells[index] = visual
        return BoardRenderModel(
            width = SIZE,
            height = SIZE,
            cells = cells,
            ships = ships,
            sonarReadings = sonar,
            recon = recon,
            fadedMisses = faded,
        )
    }

    private fun scene(
        marks: Map<Int, CellVisual>,
        ships: List<ShipOutline> = emptyList(),
        effects: List<EffectEvent>,
    ): IntroScene = IntroScene(model = board(marks = marks, ships = ships), effects = effects)
}
