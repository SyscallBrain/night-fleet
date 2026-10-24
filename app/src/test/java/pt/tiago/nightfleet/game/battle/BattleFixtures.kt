package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.random.SplitMix64

/** Deterministic layouts and matches for the battle tests. */
object BattleFixtures {

    const val SEED = 0x5EED_0001L

    /** Hand-made layout: five horizontal ships on rows 0, 2, 4, 6 and 8, from column 0. */
    fun fixedPlacements(rowOffset: Int = 0): List<Placement> = listOf(
        Placement(ShipClass.CARRIER, Cell(rowOffset, 0)),
        Placement(ShipClass.BATTLESHIP, Cell(2 + rowOffset, 0)),
        Placement(ShipClass.CRUISER, Cell(4 + rowOffset, 0)),
        Placement(ShipClass.SUBMARINE, Cell(6 + rowOffset, 0)),
        Placement(ShipClass.DESTROYER, Cell(8 + rowOffset, 0)),
    )

    /** A random but legal layout, deterministic for [seed]. */
    fun randomPlacements(seed: Long = 7L): List<Placement> {
        val rules = QuickBattle.rules()
        val result = RandomPlacer.place(rules.boardFor(0), rules.fleet, SplitMix64(seed))
        return (result as PlacementResult.Success).placements
    }

    fun matchWith(placements: List<Placement>, attemptSeed: Long = SEED): BattleMatch {
        val setup = BattleMatch.create(placements, attemptSeed)
        return BattleMatch(state = (setup as SetupResult.Created).state)
    }

    fun newMatch(attemptSeed: Long = SEED, placementSeed: Long = 7L): BattleMatch =
        matchWith(randomPlacements(placementSeed), attemptSeed)

    fun cell(index: Int, width: Int = 10) = Cell(index / width, index % width)

    fun index(cell: Cell, width: Int = 10) = cell.row * width + cell.col

    /** Row-major squares of the player's (side 0) ship number [ship]. */
    fun ownSquares(match: BattleMatch, ship: Int): List<Int> =
        match.state.sides[0].ships[ship].placement.cells().map { index(it) }

    /** Row-major squares of the enemy (side 1) ship number [ship]. */
    fun enemySquares(match: BattleMatch, ship: Int): List<Int> =
        match.state.sides[1].ships[ship].placement.cells().map { index(it) }
}
