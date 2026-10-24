package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.ai.AdmiralAI
import pt.tiago.nightfleet.core.ai.AdmiralBrain
import pt.tiago.nightfleet.core.ai.AdmiralProfile
import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.ai.AiSkill
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.ai.AiView
import pt.tiago.nightfleet.core.ai.OfficerHint
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.toSkill
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.game.freeplay.OpponentLevel

/**
 * The real admirals of core/ai behind the battle's [OpponentBrain]. The adapter builds the
 * [AiView] from the admiral's observation, asks [AdmiralBrain] for an intent and converts it to an engine action.
 *
 * - With the salvo rule the admiral sends one salvo of `min(afloat, unknown)` distinct squares. Core/ai has no
 *   salvo decision, so the squares are chosen one by one: each choice is taken on a view where the earlier
 *   choices count as water (pending), and weapons are off for that turn.
 * - Whatever the core proposes, a Fire or a Salvo that is not legal on the observation is replaced by
 *   [fallback], the best HARD-density square. The engine still checks everything else; the battle controller
 *   calls [fallback] when the engine refuses a proposal, so the battle never stalls.
 * - Decisions depend only on the observation and the [rng], so a restored battle decides the same way.
 */
class AdmiralOpponent(
    val profile: AdmiralProfile,
    val tier: AiTier,
    private val ai: AdmiralAI = AdmiralBrain,
    /**
     * The effective numbers of this admiral. A campaign passes `variant.ai.toSkill(profile, tier)`, the
     * same mapping the leveltool calibrates with; free play keeps [AiSkill.derived].
     */
    val skill: AiSkill = AiSkill.derived(profile, tier),
) : OpponentBrain {

    override fun decide(obs: Observation, rng: SplitMix64): Action {
        val view = AiView.from(obs)
        val proposed = runCatching {
            if (obs.publicState.rules.salvo) salvo(obs, rng) else single(view, rng)
        }.getOrNull()
        return if (proposed != null && isLegal(obs, proposed)) proposed else fallback(obs, rng)
    }

    /** The best unknown square by HARD density; passes only when the enemy sea has no unknown square left. */
    override fun fallback(obs: Observation, rng: SplitMix64): Action {
        val index = OfficerHint.topCells(AiView.from(obs), 1).firstOrNull() ?: return Action.Timeout
        return Action.Fire(cellOf(index, obs.width))
    }

    private fun single(view: AiView, rng: SplitMix64): Action = ai.decide(view, profile, tier, rng, skill).toAction()

    /** One salvo of `min(afloat, unknown)` distinct squares, decided by the admiral's salvo policy. */
    private fun salvo(obs: Observation, rng: SplitMix64): Action {
        val free = obs.enemySea.count { it.isFireable }
        val afloat = obs.ownSea.ships.count { !it.sunk }
        val size = minOf(afloat, free)
        val cells = ai.decideSalvo(AiView.from(obs), profile, tier, rng, size, skill)
        return Action.Salvo(cells.map { cellOf(it, obs.width) })
    }

    /** Fire and salvo squares must be fireable on the observation: unknown, contact or repaired (the engine checks the rest). */
    private fun isLegal(obs: Observation, action: Action): Boolean = when (action) {
        is Action.Fire -> inside(obs, action.cell) && obs.enemySea[indexOf(obs, action.cell)].isFireable
        is Action.Salvo -> action.cells.size == minOf(obs.ownSea.ships.count { !it.sunk }, obs.enemySea.count { it.isFireable }) &&
            action.cells.toSet().size == action.cells.size &&
            action.cells.all { inside(obs, it) && obs.enemySea[indexOf(obs, it)].isFireable }
        else -> true
    }

    private fun inside(obs: Observation, cell: Cell): Boolean = cell.row in 0 until obs.height && cell.col in 0 until obs.width

    private fun indexOf(obs: Observation, cell: Cell): Int = cell.row * obs.width + cell.col

    private fun cellOf(index: Int, width: Int): Cell = Cell(index / width, index % width)

    companion object {
        /** The admiral of a free play or quick battle id. Shade is drawn from the attempt seed. */
        fun profileOf(admiralId: String, attemptSeed: Long): AdmiralProfile = when (admiralId) {
            SHADE_ID -> Admirals.captainShade(attemptSeed)
            else -> Admirals.byId(admiralId) ?: Admirals.CORAL
        }

        /**
         * The brain of an admiral at a free play level, or of a campaign admiral when [campaignAi] (the variant's AI
         * config) is given: its skill is `campaignAi.toSkill(profile, tier)`, the calibrated one.
         */
        fun forLevel(admiralId: String, level: OpponentLevel, attemptSeed: Long, campaignAi: AiConfigDto? = null): AdmiralOpponent {
            val profile = profileOf(admiralId, attemptSeed)
            val tier = tierOf(level)
            return AdmiralOpponent(profile, tier, skill = campaignAi?.toSkill(profile, tier) ?: AiSkill.derived(profile, tier))
        }

        fun tierOf(level: OpponentLevel): AiTier = when (level) {
            OpponentLevel.EASY -> AiTier.EASY
            OpponentLevel.MEDIUM -> AiTier.MEDIUM
            OpponentLevel.HARD -> AiTier.HARD
            OpponentLevel.RELENTLESS -> AiTier.RELENTLESS
        }

        const val SHADE_ID = "shade"
    }
}
