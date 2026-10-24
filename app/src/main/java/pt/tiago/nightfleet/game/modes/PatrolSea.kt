package pt.tiago.nightfleet.game.modes

import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.PatrolDefaults
import pt.tiago.nightfleet.core.content.PatrolDefaults.stageFor
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.freeplay.FreePlayAdmirals
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.SetupGenerators
import pt.tiago.nightfleet.game.freeplay.TerrainGenerator

/**
 * The battle of one patrol sea: a HUNT in which only the player fires, within the reserve of the run.
 * The enemy fleet, the islands and the mines come from the sea seed, so the same run gives the same seas. The
 * player's own fleet is placed automatically: the enemy never fires in a hunt.
 */
object PatrolSea {

    /** The weapons in play on the patrol: the ones of the campaign hunts, without the decoy and the repair. */
    val WEAPONS: Set<Weapon> = setOf(Weapon.SONAR, Weapon.TORPEDO, Weapon.RECON, Weapon.CROSS_SHELL)

    /** The battle of [run]'s sea, or null when the stage of the config cannot be built. */
    fun setup(run: PatrolRun, config: PatrolConfigDto): MatchSetup? {
        val stage = config.stageFor(run.sea)
        if (stage.width != stage.height) return null
        val seed = run.seaSeed
        val terrain = if (stage.terrain == PatrolDefaults.ISLANDS) {
            TerrainGenerator.generate(stage.width, stage.height, SplitMix64.mix(seed xor TERRAIN_SALT), fleetOf(stage.width)) ?: emptyList()
        } else {
            emptyList()
        }
        val freePlay = FreePlayConfig(
            admiralId = FreePlayAdmirals.DEFAULT_ID,
            level = levelOf(run.difficulty),
            grid = stage.width,
            islands = terrain.isNotEmpty(),
            weapons = WEAPONS,
        )
        val base = FreePlayBuilder.baseRules(freePlay, terrain)
        if (base.problems().isNotEmpty() || freePlay.problems().isNotEmpty()) return null

        val player = place(base, PLAYER, seed) ?: return null
        val enemy = place(base, ENEMY, seed) ?: return null
        val mines = SetupGenerators.mines(
            base.boardFor(ENEMY),
            enemy,
            stage.mines,
            SplitMix64(SplitMix64.mix(seed xor MINE_SALT)),
        )
        val setup = MatchSetup(
            rules = base.copy(mines = listOf(emptyList(), mines)),
            mission = MissionType.HUNT,
            attemptSeed = seed,
            placements = listOf(player, enemy),
            firstPlayer = PLAYER,
            opponentId = freePlay.admiralId,
            admiralLevel = freePlay.level,
            shotBudget = run.reserve,
        )
        return setup.takeIf { it.create() is SetupResult.Created }
    }

    /** Level of the sea's hunt (its brain is never used: the enemy does not fire). */
    fun levelOf(difficulty: Difficulty): OpponentLevel = when (difficulty) {
        Difficulty.EASY -> OpponentLevel.EASY
        Difficulty.MEDIUM -> OpponentLevel.MEDIUM
        Difficulty.HARD -> OpponentLevel.HARD
    }

    private fun fleetOf(side: Int) = FreePlayConfig(grid = side).fleetPreset

    private fun place(base: RuleSet, side: Int, seaSeed: Long): List<Placement>? {
        val rng = SeedStreams.rng(seaSeed, SeedStreams.Stream.LAYOUT, side)
        return when (val result = RandomPlacer.place(base.boardFor(side), base.fleetFor(side), rng, shipsMayTouch = base.shipsMayTouch)) {
            is PlacementResult.Success -> result.placements
            is PlacementResult.Failure -> null
        }
    }

    const val PLAYER = 0
    const val ENEMY = 1
    private const val TERRAIN_SALT = 0x54455252L
    private const val MINE_SALT = 0x4D494E50L
}
