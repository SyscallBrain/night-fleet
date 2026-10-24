package pt.tiago.nightfleet.core.engine

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.RuleSet

/** Small explicit battles for engine tests: 8 by 8 seas, one destroyer per side by default. */
internal object Fx {
    val PAIR = Fleet("PAIR", listOf(ShipClass.DESTROYER))
    val TWIN = Fleet("TWIN", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))
    val MIDGET = Fleet("MIDGET", listOf(ShipClass.MIDGET_SUB))

    /** Side 1 destroyer at A1-B1 (top-left corner). */
    val TOP_LEFT = dest(0, 0)

    /** Side 0 destroyer at G8-H8 (bottom-right corner). */
    val BOTTOM_RIGHT = dest(7, 6)

    fun dest(row: Int, col: Int, rot: Int = 0) = Placement(ShipClass.DESTROYER, Cell(row, col), rot)

    fun rules(
        fleet: Fleet = PAIR,
        terrain: List<Terrain> = emptyList(),
        maxTurns: Int = 400,
        shipsMayTouch: Boolean = false,
    ) = RuleSet(
        width = 8,
        height = 8,
        fleet = fleet,
        terrain = terrain,
        maxTurns = maxTurns,
        shipsMayTouch = shipsMayTouch,
    )

    /** Builds a started battle. Side 0 is [p0], side 1 is [p1]. Fails the test if the setup is rejected. */
    fun start(
        p0: List<Placement>,
        p1: List<Placement>,
        rules: RuleSet = rules(),
        mission: MissionType = MissionType.DUEL,
        firstPlayer: Int = 0,
        budget: Int? = null,
    ): BattleState {
        val result = BattleSetup.create(rules, mission, listOf(p0, p1), firstPlayer, attemptSeed = 42L, shotBudget = budget)
        return (result as? SetupResult.Created)?.state
            ?: throw AssertionError("setup rejected: ${(result as SetupResult.Rejected).reasons}")
    }

    /** The usual duel: side 0 at G8-H8, side 1 at A1-B1. */
    fun duel(rules: RuleSet = rules()): BattleState = start(listOf(BOTTOM_RIGHT), listOf(TOP_LEFT), rules)

    fun fire(state: BattleState, label: String): Outcome =
        Engine.apply(state, Action.Fire(Cell.parse(label) ?: throw AssertionError("bad label $label")))

    fun fireAt(state: BattleState, row: Int, col: Int): Outcome = Engine.apply(state, Action.Fire(Cell(row, col)))

    fun illegalReason(outcome: Outcome): String? =
        outcome.events.filterIsInstance<Event.IllegalAction>().firstOrNull()?.reason

    fun labels(cells: List<Cell>): List<String> = cells.map { it.label() }
}
