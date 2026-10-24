package pt.tiago.nightfleet.game.freeplay

import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.MatchSetup

/** Free play fixtures for the tests: a config with every variant on, and a valid setup from a seed. */
object FreePlayFixtures {

    /** Salvo, islands, mines, diving, fog, moving ships and every weapon, on the classic 10 by 10 sea. */
    fun allVariants(level: OpponentLevel, admiralId: String): FreePlayConfig = FreePlayConfig(
        admiralId = admiralId,
        level = level,
        fleet = FleetChoice.CLASSIC,
        islands = true,
        salvo = true,
        diving = true,
        fog = true,
        movingShips = true,
        minesPerSide = 2,
        weapons = Weapon.entries.toSet(),
        starter = Starter.PLAYER,
    )

    /** The setup of a battle for [config], from the first seed of [seed0] that gives a valid one. */
    fun setupFor(config: FreePlayConfig, seed0: Long): MatchSetup {
        for (seed in seed0 until seed0 + 200) {
            val terrain = FreePlayBuilder.terrainFor(config, seed)
            val base = FreePlayBuilder.baseRules(config, terrain)
            val placed = RandomPlacer.place(base.boardFor(0), base.fleetFor(0), SplitMix64(seed), shipsMayTouch = base.shipsMayTouch)
            val placements = (placed as? PlacementResult.Success)?.placements ?: continue
            FreePlayBuilder.finish(config, terrain, seed, placements)?.let { return it }
        }
        error("No setup for ${config.level} from seed $seed0")
    }

    /** The first state of a battle from [setup]. */
    fun startOf(setup: MatchSetup): BattleMatch = BattleMatch((setup.create() as SetupResult.Created).state)
}
