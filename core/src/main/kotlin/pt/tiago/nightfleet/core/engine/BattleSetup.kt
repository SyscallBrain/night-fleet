package pt.tiago.nightfleet.core.engine

import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon

sealed class SetupResult {
    data class Created(val state: BattleState) : SetupResult()

    /** [reasons] are stable codes, one per problem. */
    data class Rejected(val reasons: List<String>) : SetupResult()
}

/** Builds the first [BattleState] of a battle. */
object BattleSetup {

    /**
     * Creates a battle.
     *
     * [placements] has one entry per side. A `null` entry is a hidden fleet: it is generated with
     * [RandomPlacer] from the LAYOUT stream of [attemptSeed] and the side's fleet ([RuleSet.fleetFor]).
     * A given list is validated: overlaps, touching, terrain, mines and the fleet composition.
     *
     * [decoys] has one list per side: the decoys (Iscos) of that sea, each one water square that
     * touches no ship and holds no mine. DUEL, CONVOY and BOSS take no [shotBudget]; HUNT needs one
     * (at least 1) and must start with side 0. CONVOY needs [RuleSet.convoy]. BOSS duels are DUELs.
     */
    fun create(
        rules: RuleSet,
        mission: MissionType,
        placements: List<List<Placement>?>,
        firstPlayer: Int,
        attemptSeed: Long,
        shotBudget: Int? = null,
        decoys: List<List<Cell>> = listOf(emptyList(), emptyList()),
    ): SetupResult {
        val reasons = mutableListOf<String>()
        reasons += rules.problems()
        if (placements.size != 2) reasons += "PLACEMENTS: exactly two sides"
        if (decoys.size != 2) reasons += "DECOYS: exactly two sides"
        if (firstPlayer !in 0..1) reasons += "FIRST_PLAYER: must be 0 or 1"
        when (mission) {
            MissionType.DUEL, MissionType.BOSS -> if (shotBudget != null) reasons += "BUDGET_ONLY_FOR_HUNT"
            MissionType.CONVOY -> {
                if (shotBudget != null) reasons += "BUDGET_ONLY_FOR_HUNT"
                if (rules.convoy == null) reasons += "CONVOY_NEEDS_RULE"
            }
            MissionType.HUNT -> {
                if (shotBudget == null || shotBudget < 1) reasons += "HUNT_NEEDS_BUDGET"
                if (firstPlayer != 0) reasons += "HUNT_FIRST_PLAYER_MUST_BE_0"
            }
        }
        if (reasons.isNotEmpty()) return SetupResult.Rejected(reasons)

        val sides = ArrayList<SideState>(2)
        for (side in 0..1) {
            val board = rules.boardFor(side)
            val given = placements[side]
            val chosen: List<Placement> = if (given == null) {
                val rng = SeedStreams.rng(attemptSeed, SeedStreams.Stream.LAYOUT, side)
                when (val result = RandomPlacer.place(board, rules.fleetFor(side), rng, shipsMayTouch = rules.shipsMayTouch)) {
                    is PlacementResult.Success -> result.placements
                    is PlacementResult.Failure -> return SetupResult.Rejected(listOf("LAYOUT_FAILED: ${result.reason}"))
                }
            } else {
                val problems = PlacementValidator.validate(board, rules.fleetFor(side), given, rules.shipsMayTouch)
                if (problems.isNotEmpty()) {
                    return SetupResult.Rejected(problems.map { "PLACEMENT side $side: ${it.code}" })
                }
                given
            }

            val decoyCells = decoys[side]
            val decoyIssues = decoyCells.flatMap { cell ->
                PlacementValidator.validateDecoy(board, chosen, cell, rules.shipsMayTouch).map { "DECOY side $side: ${it.code}" }
            }
            if (decoyCells.toSet().size != decoyCells.size) reasons += "DECOY side $side: DUPLICATE"
            reasons += decoyIssues
            if (reasons.isNotEmpty()) return SetupResult.Rejected(reasons)

            sides += SideState(
                board = board,
                ships = chosen.map { ShipState(placement = it) },
                decoys = decoyCells.map { DecoyState(cell = board.index(it)) },
                incoming = ShotLog(),
                charges = Weapon.entries.associateWith { weapon ->
                    rules.weapons[weapon]?.let { minOf(it.initial, it.max) } ?: 0
                },
                moveUsed = false,
                repairUsed = false,
                hitStreak = 0,
            )
        }

        return SetupResult.Created(
            BattleState(
                engineVersion = ENGINE_VERSION,
                rules = rules,
                mission = mission,
                attemptSeed = attemptSeed,
                shotBudget = shotBudget,
                sides = sides,
                turn = 0,
                round = 0,
                current = firstPlayer,
                pendingSkips = listOf(0, 0),
                rngState = SeedStreams.seed(attemptSeed, SeedStreams.Stream.ENGINE),
                result = null,
            ),
        )
    }
}
