package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.engine.ShipState
import pt.tiago.nightfleet.core.engine.ShotLog
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.observation.ClassCount
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.RuleSet

/** Hand-built views for the tests of the inference: no engine involved, every square is explicit. */
internal object SyntheticViews {

    /** An enemy sea of [w] by [h] squares, all unknown, with the given enemy classes still afloat. */
    fun blank(
        w: Int,
        h: Int,
        classes: List<ShipClass> = listOf(ShipClass.DESTROYER),
        kinds: Map<String, KnownCell> = emptyMap(),
        readings: List<Reading> = emptyList(),
        turn: Int = 10,
        charges: Map<Weapon, Int> = emptyMap(),
        rules: RuleSet? = null,
        ownShips: List<ShipState> = emptyList(),
        enemyShipMovedAtTurn: Int? = null,
        waterTurns: Map<Int, Int> = emptyMap(),
        moveUsed: Boolean = false,
        incoming: ShotLog = ShotLog(),
    ): AiView {
        val known = MutableList(w * h) { KnownCell.UNKNOWN }
        for ((label, kind) in kinds) {
            val cell = pt.tiago.nightfleet.core.grid.Cell.parse(label) ?: error("bad label $label")
            known[cell.row * w + cell.col] = kind
        }
        val counts = classes.groupingBy { it }.eachCount().map { (cls, count) -> ClassCount(cls, count) }
        val fleet = Fleet("TEST", classes)
        val ruleSet = rules ?: RuleSet(width = w, height = h, fleet = fleet)
        return AiView(
            width = w,
            height = h,
            known = known,
            enemySunk = emptyList(),
            enemyRemaining = counts,
            rules = ruleSet,
            mission = MissionType.DUEL,
            shotBudget = null,
            turn = turn,
            ownCharges = Weapon.entries.associateWith { charges[it] ?: 0 },
            ownBoard = Board.water(w, h),
            ownShips = ownShips,
            ownIncoming = incoming,
            moveUsed = moveUsed,
            repairUsed = false,
            readings = readings,
            enemyShipMovedAtTurn = enemyShipMovedAtTurn,
            waterTurns = waterTurns,
        )
    }

    /** Rules for a move or a dive test on a [w] by [h] sea. */
    fun rules(w: Int, h: Int, classes: List<ShipClass>, movingShips: Boolean = false, diving: Boolean = false, touch: Boolean = false): RuleSet =
        RuleSet(
            width = w,
            height = h,
            fleet = Fleet("TEST", classes),
            movingShips = movingShips,
            diving = if (diving) DivingRule() else null,
            shipsMayTouch = touch,
        )

    /** Shorthand for a sonar reading. */
    fun sonar(label: String, count: Int, turn: Int = 5): SonarReading =
        SonarReading(pt.tiago.nightfleet.core.grid.Cell.parse(label)!!, count, turn)

    fun ownShip(shipClass: ShipClass, row: Int, col: Int, rotation: Int = 0, hits: List<Int> = emptyList()): ShipState =
        ShipState(
            placement = pt.tiago.nightfleet.core.fleet.Placement(shipClass, pt.tiago.nightfleet.core.grid.Cell(row, col), rotation),
            hits = hits,
            sunk = false,
        )
}
