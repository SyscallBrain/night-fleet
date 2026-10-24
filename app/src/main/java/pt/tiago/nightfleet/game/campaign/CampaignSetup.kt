package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.core.ai.AdmiralPlacer
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.Difficulty as ContentDifficulty
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.toPlacement
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.battle.AdmiralOpponent
import pt.tiago.nightfleet.game.battle.MatchSetup
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.SetupGenerators

/**
 * Builds the battle of a campaign variant.
 *
 * Two steps, as in free play: [plan] before the placement screen (rules, the attempt seed, who
 * starts and the certified enemy layout chosen from the pool by the attempt seed), then [finish]
 * once the player has placed the fleet. Everything random comes from streams of the attempt seed,
 * so the same variant, attempt and placement always give the same battle.
 */
object CampaignSetup {

    /** Side indexes of a battle: the player is side 0. */
    const val PLAYER = 0
    const val ADMIRAL = 1

    /** What the placement screen needs, and what [finish] needs to build the battle. */
    data class Plan(
        val mission: MissionKind,
        val difficulty: Difficulty,
        val variantRules: RuleSet,
        val attemptSeed: Long,
        val firstPlayer: Int,
        val enemy: List<Placement>,
        /** Shot budget of a HUNT (null for the other types). */
        val shotBudget: Int?,
        /** Index of the layout taken from the pool, for diagnostics and tests. */
        val layoutIndex: Int,
        /** The AI config of the variant: the admiral plays `ai.toSkill(profile, tier)`, as calibrated. */
        val ai: AiConfigDto,
    )

    /**
     * The plan of [variant] of [mission] for attempt number [attemptIndex]. Null when the variant has
     * no certified layout pool (PUZZLE, or a broken catalog) or the rules cannot be represented.
     */
    fun plan(
        mission: MissionDto,
        difficulty: Difficulty,
        attemptIndex: Long,
        terrains: Map<String, List<Terrain>>,
        pool: LayoutPoolDto?,
    ): Plan? {
        val variant: VariantDto = mission.variants[difficultyOf(difficulty)] ?: return null
        return planVariant(mission.type, variant, difficulty, attemptIndex, terrains, pool)
    }

    /**
     * The plan of one variant of any battle kind, campaign or daily (the daily hunt has its own variant and pool).
     * Null for puzzles, for a missing or empty pool and for rules that cannot be built.
     */
    fun planVariant(
        type: MissionKind,
        variant: VariantDto,
        difficulty: Difficulty,
        attemptIndex: Long,
        terrains: Map<String, List<Terrain>>,
        pool: LayoutPoolDto?,
    ): Plan? {
        if (type == MissionKind.PUZZLE || variant.puzzleId != null) return null
        if (pool == null || pool.layouts.isEmpty()) return null
        val rules = runCatching { variant.toRuleSet(terrains) }.getOrNull() ?: return null
        if (rules.problems().isNotEmpty()) return null
        val attemptSeed = SeedStreams.attemptSeed(variant.levelSeed, attemptIndex.toInt())
        val layoutIndex = SeedStreams.rng(attemptSeed, SeedStreams.Stream.LAYOUT, ADMIRAL)
            .nextInt(pool.layouts.size)
        return Plan(
            mission = type,
            difficulty = difficulty,
            variantRules = rules,
            attemptSeed = attemptSeed,
            firstPlayer = firstPlayer(variant.firstPlayer, attemptSeed),
            enemy = pool.layouts[layoutIndex].map { it.toPlacement() },
            shotBudget = variant.shotBudget.takeIf { type == MissionKind.HUNT },
            layoutIndex = layoutIndex,
            ai = variant.ai,
        )
    }

    /**
     * The battle, once the player's fleet is placed: the enemy layout of the plan, the decoys when the
     * decoy weapon is in play, and the admiral of the sea as the opponent. Null when the setup is invalid.
     */
    fun finish(plan: Plan, playerPlacements: List<Placement>, admiralId: String): MatchSetup? {
        val placements = listOf(playerPlacements, plan.enemy)
        val wantsDecoys = Weapon.DECOY in plan.variantRules.weapons.keys
        val profile = AdmiralOpponent.profileOf(admiralId, plan.attemptSeed)
        val decoys = listOf(PLAYER, ADMIRAL).map { side ->
            val rng = SeedStreams.rng(plan.attemptSeed, SeedStreams.Stream.LAYOUT, 10 + side)
            when {
                !wantsDecoys -> emptyList()
                // The admiral hides its decoy with its own style, as the leveltool simulates it.
                side == ADMIRAL -> AdmiralPlacer.placeDecoys(
                    plan.variantRules.boardFor(side), placements[side], 1, profile, rng, plan.variantRules.shipsMayTouch,
                )
                else -> listOfNotNull(SetupGenerators.decoy(plan.variantRules.boardFor(side), placements[side], rng))
            }
        }
        if (wantsDecoys && decoys.any { it.isEmpty() }) return null
        val setup = MatchSetup(
            rules = plan.variantRules,
            mission = plan.mission.toMissionType(),
            attemptSeed = plan.attemptSeed,
            placements = placements,
            decoys = decoys,
            firstPlayer = plan.firstPlayer,
            opponentId = admiralId,
            // The admiral's effective tier: the variant's, escalated above the difficulty when calibration asked for it.
            admiralLevel = OpponentLevel.valueOf(plan.ai.tier.name),
            shotBudget = plan.shotBudget,
            admiralAi = plan.ai,
        )
        return setup.takeIf { it.create() is SetupResult.Created }
    }

    /** Who opens the battle: the rule of the variant, or a draw from the attempt seed for ALTERNATE. */
    fun firstPlayer(rule: FirstPlayer, attemptSeed: Long): Int = when (rule) {
        FirstPlayer.PLAYER -> PLAYER
        FirstPlayer.ADMIRAL -> ADMIRAL
        FirstPlayer.ALTERNATE -> SeedStreams.firstPlayer(attemptSeed)
    }

    /** Maps the content kind to the engine mission type. PUZZLE has no battle. */
    fun MissionKind.toMissionType(): MissionType = when (this) {
        MissionKind.DUEL, MissionKind.PUZZLE -> MissionType.DUEL
        MissionKind.HUNT -> MissionType.HUNT
        MissionKind.CONVOY -> MissionType.CONVOY
        MissionKind.BOSS -> MissionType.BOSS
    }

    fun levelOf(difficulty: Difficulty): OpponentLevel = when (difficulty) {
        Difficulty.EASY -> OpponentLevel.EASY
        Difficulty.MEDIUM -> OpponentLevel.MEDIUM
        Difficulty.HARD -> OpponentLevel.HARD
    }

    fun difficultyOf(difficulty: Difficulty): ContentDifficulty = ContentDifficulty.valueOf(difficulty.name)

    /** Terrain cells by id, for [VariantDto.toRuleSet]. */
    fun terrainMap(terrains: List<TerrainDto>): Map<String, List<Terrain>> =
        terrains.associate { it.id to it.toTerrains() }
}
