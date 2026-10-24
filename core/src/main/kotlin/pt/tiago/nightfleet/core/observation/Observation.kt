package pt.tiago.nightfleet.core.observation

import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.DecoyState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.ShipState
import pt.tiago.nightfleet.core.engine.ShotLog
import pt.tiago.nightfleet.core.engine.ShotRecord
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.engine.SubmarinePhase
import pt.tiago.nightfleet.core.engine.phaseOf
import pt.tiago.nightfleet.core.engine.roundsUntilPhaseChange
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/** What a viewer knows about one square of the enemy sea. */
enum class KnownCell {
    UNKNOWN,
    WATER,
    HIT,

    /** Part of a sunk ship; the ship's shape is in [Observation.enemySunk]. */
    SUNK,
    CONTACT,
    MINE,
    DECOY_WRECK,
    REPAIRED,

    /** Land, always visible to both sides. */
    ISLAND,

    /** Reef, always visible to both sides. */
    REEF,
    ;

    /**
     * Whether a shot at this square can still change something: unknown, or a contact or repaired square that is
     * a live ship square (the engine accepts a shot there). Water, hits, sunk ships and land are spent.
     */
    val isFireable: Boolean
        get() = this == UNKNOWN || this == CONTACT || this == REPAIRED
}

/** A sunk enemy ship: its class and its squares (public once sunk). */
data class SunkShape(val shipClass: ShipClass, val cells: List<Cell>)

/** Number of enemy ships of [shipClass] still afloat. */
data class ClassCount(val shipClass: ShipClass, val count: Int)

/** A sonar reading of the viewer, with the turn it was taken on. */
data class SonarReadingView(val center: Cell, val count: Int, val turn: Int)

/** A reconnaissance reading of the viewer: [present] tells whether an unhit surface ship square lies in the line. */
data class ReconReadingView(val isRow: Boolean, val index: Int, val present: Boolean, val turn: Int)

/** Everything the viewer has about its own sea, including the full fleet. */
data class OwnSea(
    val board: Board,
    val ships: List<ShipState>,
    val decoys: List<DecoyState>,
    val incoming: ShotLog,
)

/**
 * The public part of the state. Enemy mines are hidden, so the rules are redacted for the viewer.
 * [submarinePhase] and [roundsUntilSubmarinePhaseChange] are null when the diving rule is off.
 */
data class PublicState(
    val turn: Int,
    val round: Int,
    val current: Int,
    val pendingSkips: List<Int>,
    val rules: RuleSet,
    val mission: MissionType,
    val shotBudget: Int?,
    /** Shots the viewer has fired for the star count (see shotsForStars). */
    val shotsFired: Int,
    val ownCharges: Map<Weapon, Int>,
    val enemyCharges: Map<Weapon, Int>,
    /** Weapon configs of the battle, the same for both sides. */
    val weapons: Map<Weapon, WeaponConfig>,
    val ownHitStreak: Int,
    val enemyHitStreak: Int,
    val ownRepairUsed: Boolean,
    val ownMoveUsed: Boolean,
    val submarinePhase: SubmarinePhase?,
    val roundsUntilSubmarinePhaseChange: Int?,
    val result: BattleResult?,
)

/**
 * The view of one side. It is a pure function of the viewer's own sea and of the
 * shots the viewer fired, which are the only sources of information about the enemy. The enemy's
 * hidden placements, mines, decoys and submerged squares never appear in any field.
 *
 * [enemySea] is indexed by square (row-major) and has `width * height` entries.
 */
data class Observation(
    val viewer: Int,
    val width: Int,
    val height: Int,
    val enemySea: List<KnownCell>,
    val enemySunk: List<SunkShape>,
    val enemyRemaining: List<ClassCount>,
    val ownSea: OwnSea,
    val publicState: PublicState,
    /** The viewer's sonar readings, oldest first. */
    val sonarReadings: List<SonarReadingView>,
    /** The viewer's reconnaissance readings, oldest first. */
    val reconReadings: List<ReconReadingView>,
    /** Turns on which the enemy moved a ship. At most one entry: a side moves a ship once per battle. */
    val enemyShipMovedAtTurns: List<Int>,
    /**
     * Turn of the viewer's latest direct shot that missed, by enemy square (misses and torpedo trails), whether
     * fog has faded it from [enemySea] or not. This is the viewer's own shot log, which the viewer knows. The
     * admirals use it to remember their misses and to tell weak water (shot before an enemy move) from strong water.
     */
    val enemyWaterTurns: Map<Int, Int> = emptyMap(),
) {
    companion object {
        fun of(state: BattleState, viewer: Int): Observation {
            require(viewer == 0 || viewer == 1) { "Viewer must be side 0 or 1" }
            val enemyIndex = 1 - viewer
            val enemy = state.sides[enemyIndex]
            val own = state.sides[viewer]
            val board = enemy.board
            val fog = state.rules.fog

            val known = Array(board.size) { index ->
                when (board.terrainAt(index)) {
                    Terrain.WATER -> KnownCell.UNKNOWN
                    Terrain.ISLAND -> KnownCell.ISLAND
                    Terrain.REEF -> KnownCell.REEF
                }
            }
            // The viewer's latest record per square of the enemy sea is what the viewer knows.
            val latest = LinkedHashMap<Int, ShotRecord>()
            for (record in enemy.incoming.entries) if (record.attacker == viewer) latest[record.cell] = record
            val turnsTaken = own.turnsTaken
            for ((cell, record) in latest) {
                known[cell] = when (val result = record.result) {
                    ShotResult.Miss, ShotResult.Trail ->
                        if (Engine.isFaded(fog, turnsTaken, record)) KnownCell.UNKNOWN else KnownCell.WATER
                    ShotResult.AutoWater -> KnownCell.WATER
                    ShotResult.Hit -> KnownCell.HIT
                    ShotResult.MineHit -> KnownCell.MINE
                    ShotResult.Contact -> KnownCell.CONTACT
                    ShotResult.Repaired -> KnownCell.REPAIRED
                    ShotResult.DecoyHit ->
                        if (enemy.decoys.any { it.cell == cell && it.revealed }) KnownCell.DECOY_WRECK else KnownCell.HIT
                    is ShotResult.Sunk -> KnownCell.SUNK
                }
            }
            // Sunk ships: their shape is public, and every square of it is known as sunk.
            val sunk = enemy.incoming.entries.filter { it.attacker == viewer && it.result is ShotResult.Sunk }.map {
                val result = it.result as ShotResult.Sunk
                SunkShape(result.shipClass, result.cells.map { cell -> board.cell(cell) })
            }
            for (shape in sunk) for (c in shape.cells) known[board.index(c)] = KnownCell.SUNK

            val fleet = state.rules.fleetFor(enemyIndex).ships
            val remaining = ShipClass.entries.mapNotNull { shipClass ->
                val left = fleet.count { it == shipClass } - sunk.count { it.shipClass == shipClass }
                if (left > 0) ClassCount(shipClass, left) else null
            }

            val ownBoard = own.board
            return Observation(
                viewer = viewer,
                width = board.width,
                height = board.height,
                enemySea = known.toList(),
                enemySunk = sunk,
                enemyRemaining = remaining,
                ownSea = OwnSea(ownBoard, own.ships, own.decoys, own.incoming),
                publicState = PublicState(
                    turn = state.turn,
                    round = state.round,
                    current = state.current,
                    pendingSkips = state.pendingSkips,
                    rules = redactedRules(state.rules, viewer),
                    mission = state.mission,
                    shotBudget = state.shotBudget,
                    shotsFired = own.starShots,
                    ownCharges = own.charges,
                    enemyCharges = enemy.charges,
                    weapons = state.rules.weapons,
                    ownHitStreak = own.hitStreak,
                    enemyHitStreak = enemy.hitStreak,
                    ownRepairUsed = own.repairUsed,
                    ownMoveUsed = own.moveUsed,
                    submarinePhase = phaseOf(state.rules.diving, state.round),
                    roundsUntilSubmarinePhaseChange = roundsUntilPhaseChange(state),
                    result = state.result,
                ),
                sonarReadings = own.sonarReadings.map { SonarReadingView(board.cell(it.center), it.count, it.turn) },
                reconReadings = own.reconReadings.map { ReconReadingView(it.isRow, it.index, it.present, it.turn) },
                enemyShipMovedAtTurns = enemy.movedAtTurn?.let { listOf(it) } ?: emptyList(),
                enemyWaterTurns = latest
                    .filter { (_, record) -> record.result == ShotResult.Miss || record.result == ShotResult.Trail }
                    .mapValues { it.value.turn },
            )
        }

        /** Keeps the viewer's own mines and drops the enemy's, which are hidden. */
        private fun redactedRules(rules: RuleSet, viewer: Int): RuleSet =
            rules.copy(mines = List(2) { side -> if (side == viewer) rules.mines.getOrElse(side) { emptyList() } else emptyList() })
    }
}
