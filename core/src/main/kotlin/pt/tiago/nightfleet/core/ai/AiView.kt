package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.engine.ShipState
import pt.tiago.nightfleet.core.engine.ShotLog
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.observation.ClassCount
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.observation.SunkShape
import pt.tiago.nightfleet.core.rules.RuleSet

/** Evidence from a sonar reading: the number of live, unhit ship squares in the window of [center]. */
data class SonarReading(val center: Cell, val count: Int, val turn: Int) : Reading

/** Evidence from aerial reconnaissance of one line: whether any live, unhit ship square lies on it. */
data class ReconReading(val isRow: Boolean, val index: Int, val present: Boolean, val turn: Int) : Reading

/**
 * Evidence about the enemy beyond the observation: weapon readings. M2 fills it.
 * Every reading is public information of the viewer.
 */
sealed interface Reading

/**
 * The only input of the admirals. It is built from an [Observation] and never holds the
 * enemy's hidden fleet, mines or decoys. Readings, the enemy move and the weak evidence are optional
 * extras that the M2 engine will fill; the M1 engine leaves them empty.
 *
 * - [known]: the enemy sea, row-major, as the viewer knows it.
 * - [enemyRemaining]: the enemy classes still afloat, with their counts.
 * - [ownCharges]: the viewer's own weapon charges (public).
 * - [moveUsed]: whether the viewer already moved a ship this battle (not part of the observation yet).
 * - [enemyShipMovedAtTurn]: the turn of the enemy's ship move, if any.
 * - [waterTurns]: turn of each miss of the viewer, by enemy square, including the misses that fog has faded
 *   from the observation (the viewer's own shot log, see [from]). Water shot before [enemyShipMovedAtTurn] is
 *   weak evidence. Water with no entry (automatic water) is strong.
 */
class AiView internal constructor(
    val width: Int,
    val height: Int,
    val known: List<KnownCell>,
    val enemySunk: List<SunkShape>,
    val enemyRemaining: List<ClassCount>,
    val rules: RuleSet,
    val mission: MissionType,
    val shotBudget: Int?,
    val turn: Int,
    val ownCharges: Map<Weapon, Int>,
    val ownBoard: Board,
    val ownShips: List<ShipState>,
    val ownIncoming: ShotLog,
    val moveUsed: Boolean,
    val repairUsed: Boolean,
    val readings: List<Reading>,
    val enemyShipMovedAtTurn: Int?,
    val waterTurns: Map<Int, Int>,
) {
    private val cells: Array<KnownCell> = known.toTypedArray()

    init {
        require(known.size == width * height) { "Enemy sea must have ${width * height} squares" }
    }

    val size: Int get() = width * height

    val shipsMayTouch: Boolean get() = rules.shipsMayTouch

    val diving: Boolean get() = rules.diving != null

    /**
     * What the viewer knows of square [index]. A miss that fog has faded from the observation is still water
     * here: the viewer's own shot log keeps it (see [waterTurns]), so the admirals do not shoot it again.
     */
    fun kind(index: Int): KnownCell =
        if (cells[index] == KnownCell.UNKNOWN && index in waterTurns) KnownCell.WATER else cells[index]

    fun cell(index: Int): Cell = Cell(index / width, index % width)

    fun index(cell: Cell): Int = cell.row * width + cell.col

    /** Enemy squares the viewer has not shot at yet, ascending. */
    fun unknownCells(): IntArray = (0 until size).filter { cells[it] == KnownCell.UNKNOWN }.toIntArray()

    /**
     * Whether the engine accepts a shot at square [index]: unknown, or a contact or repaired square
     * still to be hit. Fog-faded misses count here, because the engine does not remember them either.
     */
    fun firable(index: Int): Boolean = cells[index].isFireable

    /** Enemy squares the engine accepts a shot at, ascending. A salvo has as many squares as this has. */
    fun firableCells(): IntArray = (0 until size).filter { cells[it].isFireable }.toIntArray()

    /** Whether square [index] is worth a shot for the admirals: [firable] and not a remembered miss. */
    fun fresh(index: Int): Boolean = kind(index).isFireable

    /** The squares of [fresh]: the shots the admirals consider first, ascending. */
    fun freshCells(): IntArray = (0 until size).filter { kind(it).isFireable }.toIntArray()

    /**
     * The same view with [index] known as water: the hypothetical miss used when a salvo picks squares one
     * after another (conditional density). Nothing else changes.
     */
    internal fun withHypotheticalWater(index: Int): AiView {
        val copy = known.toMutableList()
        copy[index] = KnownCell.WATER
        return AiView(
            width, height, copy, enemySunk, enemyRemaining, rules, mission, shotBudget, turn, ownCharges,
            ownBoard, ownShips, ownIncoming, moveUsed, repairUsed, readings, enemyShipMovedAtTurn, waterTurns,
        )
    }

    /** Water of a square that is weak evidence: shot before an enemy move. */
    internal fun isWeakWater(index: Int): Boolean {
        val moved = enemyShipMovedAtTurn ?: return false
        if (kind(index) != KnownCell.WATER) return false
        val shotTurn = waterTurns[index] ?: return false
        return shotTurn < moved
    }

    override fun toString(): String = "AiView(${width}x$height, turn=$turn, unknown=${unknownCells().size})"

    companion object {
        /** Builds the view of the side that [observation] belongs to. */
        fun from(
            observation: Observation,
            extraReadings: List<Reading> = emptyList(),
            enemyShipMovedAtTurn: Int? = observation.enemyShipMovedAtTurns.maxOrNull(),
            waterTurns: Map<Int, Int> = observation.enemyWaterTurns,
        ): AiView {
            val state = observation.publicState
            val readings = observation.sonarReadings.map { SonarReading(it.center, it.count, it.turn) } +
                observation.reconReadings.map { ReconReading(it.isRow, it.index, it.present, it.turn) } +
                extraReadings
            return AiView(
                width = observation.width,
                height = observation.height,
                known = observation.enemySea,
                enemySunk = observation.enemySunk,
                enemyRemaining = observation.enemyRemaining,
                rules = state.rules,
                mission = state.mission,
                shotBudget = state.shotBudget,
                turn = state.turn,
                ownCharges = state.ownCharges,
                ownBoard = observation.ownSea.board,
                ownShips = observation.ownSea.ships,
                ownIncoming = observation.ownSea.incoming,
                moveUsed = state.ownMoveUsed,
                repairUsed = state.ownRepairUsed,
                readings = readings,
                enemyShipMovedAtTurn = enemyShipMovedAtTurn,
                waterTurns = waterTurns,
            )
        }
    }
}
