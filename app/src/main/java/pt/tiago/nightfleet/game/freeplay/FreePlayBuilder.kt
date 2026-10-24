package pt.tiago.nightfleet.game.freeplay

import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.ai.AdmiralPlacer
import pt.tiago.nightfleet.core.ai.AdmiralProfile
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.battle.AdmiralOpponent
import pt.tiago.nightfleet.game.battle.WeaponCatalog

/**
 * Turns a [FreePlayConfig] into the battle of one game. Two steps, because the player places
 * the fleet between them: [baseRules] and [terrainFor] before the placement screen, [finish] after it. Every random
 * choice comes from streams of the attempt seed, so the same seed and the same placement give the same battle.
 */
object FreePlayBuilder {

    /** Rules of the placement screen: grid, fleet, terrain and the battle options. No mines and no decoys yet. */
    fun baseRules(config: FreePlayConfig, terrain: List<Terrain>): RuleSet = RuleSet(
        width = config.grid,
        height = config.grid,
        fleet = config.fleetPreset,
        terrain = terrain,
        salvo = config.salvo,
        diving = if (config.diving) DivingRule() else null,
        fog = if (config.fog) FogRule() else null,
        movingShips = config.movingShips,
        shotClockSeconds = config.shotClock,
        weapons = weaponConfigs(config.weapons),
    )

    /** The default configs of the weapons in play. */
    fun weaponConfigs(weapons: Set<Weapon>): Map<Weapon, WeaponConfig> =
        Weapon.entries.filter { it in weapons }.associateWith { WeaponCatalog.defaultConfig(it) }

    /** Islands of the game, from [seed]; empty (all water) when the islands are off or no map fits the fleet. */
    fun terrainFor(config: FreePlayConfig, seed: Long): List<Terrain> {
        if (!config.islands) return emptyList()
        return TerrainGenerator.generate(config.grid, config.grid, seed, config.fleetPreset) ?: emptyList()
    }

    /**
     * The battle of one game, once the player's fleet is placed: the admiral's fleet, the mines of each side (away
     * from the ships of that side), the decoys when the decoy weapon is in play, and who starts. Null when the
     * rules are not valid or no layout or decoy square exists (the caller asks for another setup).
     */
    fun finish(
        config: FreePlayConfig,
        terrain: List<Terrain>,
        attemptSeed: Long,
        playerPlacements: List<Placement>,
    ): MatchSetup? {
        val base = baseRules(config, terrain)
        if (base.problems().isNotEmpty() || config.problems().isNotEmpty()) return null
        val admiral = admiralPlacements(base, attemptSeed, AdmiralOpponent.profileOf(config.admiralId, attemptSeed)) ?: return null
        return build(
            config = config,
            base = base,
            attemptSeed = attemptSeed,
            placements = listOf(playerPlacements, admiral),
            firstPlayer = firstPlayer(config.starter, attemptSeed),
            opponentId = config.admiralId,
            names = emptyList(),
        )
    }

    /**
     * The battle of a two-player duel once both fleets are placed: the same rules and the same
     * mines and decoys as free play, with [firstPlayer] opening. Null when the setup is not valid.
     */
    fun duel(
        config: FreePlayConfig,
        terrain: List<Terrain>,
        attemptSeed: Long,
        first: List<Placement>,
        second: List<Placement>,
        firstPlayer: Int,
        names: List<String>,
    ): MatchSetup? {
        val base = baseRules(config, terrain)
        if (base.problems().isNotEmpty() || config.problems().isNotEmpty()) return null
        return build(config, base, attemptSeed, listOf(first, second), firstPlayer, opponentId = null, names = names)
    }

    /** Mines and decoys of both seas, then the validated setup. Shared by free play and the duel. */
    private fun build(
        config: FreePlayConfig,
        base: RuleSet,
        attemptSeed: Long,
        placements: List<List<Placement>>,
        firstPlayer: Int,
        opponentId: String?,
        names: List<String>,
    ): MatchSetup? {
        val withMines = base.copy(
            mines = listOf(
                SetupGenerators.mines(base.boardFor(PLAYER), placements[PLAYER], config.minesPerSide, stream(attemptSeed, MINE_SALT, PLAYER)),
                SetupGenerators.mines(base.boardFor(ADMIRAL), placements[ADMIRAL], config.minesPerSide, stream(attemptSeed, MINE_SALT, ADMIRAL)),
            ),
        )
        val wantsDecoys = Weapon.DECOY in config.weapons
        val decoys = listOf(PLAYER, ADMIRAL).map { side ->
            if (!wantsDecoys) {
                emptyList<Cell>()
            } else {
                listOfNotNull(SetupGenerators.decoy(withMines.boardFor(side), placements[side], stream(attemptSeed, DECOY_SALT, side)))
            }
        }
        if (wantsDecoys && decoys.any { it.isEmpty() }) return null

        val setup = MatchSetup(
            rules = withMines,
            mission = MissionType.DUEL,
            attemptSeed = attemptSeed,
            placements = placements,
            decoys = decoys,
            firstPlayer = firstPlayer,
            names = names,
            opponentId = opponentId,
            admiralLevel = if (opponentId != null) config.level else null,
        )
        return if (setup.create() is SetupResult.Created) setup else null
    }

    /** Who acts first: the chosen side, or a draw from the attempt seed. */
    fun firstPlayer(starter: Starter, attemptSeed: Long): Int = when (starter) {
        Starter.PLAYER -> PLAYER
        Starter.ADMIRAL -> ADMIRAL
        Starter.RANDOM -> SeedStreams.firstPlayer(attemptSeed)
    }

    /**
     * The admiral's fleet on the sea without mines, placed with the admiral's style ([AdmiralPlacer]): a few
     * attempts on different streams.
     */
    internal fun admiralPlacements(base: RuleSet, attemptSeed: Long, profile: AdmiralProfile): List<Placement>? {
        val board = base.boardFor(ADMIRAL)
        val fleet = base.fleetFor(ADMIRAL)
        for (attempt in 0 until LAYOUT_ATTEMPTS) {
            val rng = SplitMix64(SplitMix64.mix(attemptSeed xor (LAYOUT_SALT + attempt * 0x101L)))
            when (val result = AdmiralPlacer.place(board, fleet, profile, rng, shipsMayTouch = base.shipsMayTouch)) {
                is PlacementResult.Success -> return result.placements
                is PlacementResult.Failure -> Unit
            }
        }
        return null
    }

    private fun stream(attemptSeed: Long, salt: Long, side: Int): SplitMix64 =
        SplitMix64(SplitMix64.mix(attemptSeed xor (salt + side)))

    const val PLAYER = 0
    const val ADMIRAL = 1
    private const val LAYOUT_ATTEMPTS = 8
    private const val LAYOUT_SALT = 0x4C41594FL
    private const val MINE_SALT = 0x4D494E45L
    private const val DECOY_SALT = 0x44454345L
}
