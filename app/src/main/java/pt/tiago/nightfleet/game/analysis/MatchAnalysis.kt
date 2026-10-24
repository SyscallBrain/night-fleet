package pt.tiago.nightfleet.game.analysis

import kotlinx.serialization.json.JsonObject
import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.ai.AdmiralProfile
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.ai.AiView
import pt.tiago.nightfleet.core.ai.ShotAnalysis
import pt.tiago.nightfleet.core.ai.ShotQuality
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.engine.hitsForPrecision
import pt.tiago.nightfleet.core.engine.shotsForStars
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.data.AnalysisSummary
import pt.tiago.nightfleet.data.WorstShot
import pt.tiago.nightfleet.game.battle.AdmiralOpponent
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.QuickBattle
import pt.tiago.nightfleet.game.replay.MatchReplay
import pt.tiago.nightfleet.game.replay.shotCells

/** One shot of the player, scored on the view just before it. */
data class ShotScore(
    /** The shot number in play order, from 1. */
    val number: Int,
    /** Row-major index of the enemy square. */
    val cell: Int,
    val quality: ShotQuality,
)

/** What the analysis and the replay screens need of a finished battle: its logbook id and its replay payload. */
data class AnalysisInput(
    val logId: String,
    val replay: JsonObject,
    /** The analysis already kept in the logbook entry, or null when the battle has not been analysed yet. */
    val stored: AnalysisSummary? = null,
)

/** The full analysis of a battle: every scored shot and the summary that goes to the logbook. */
data class MatchAnalysis(
    val shots: List<ShotScore>,
    val summary: AnalysisSummary,
)

/**
 * The post-battle analysis. For each shot of the player the HARD density of
 * the square is compared with the best square of that moment (ShotAnalysis); wasted shots have density zero. The
 * officer's hunt runs the HARD density on the same hidden fleet with a fixed seed.
 */
object MatchAnalyzer {

    /** How many worst shots the analysis reports. */
    const val WORST = 3

    /** The officer of the analysis: the HARD density without personality, no weapons (the hint's reading). */
    val OFFICER_PROFILE: AdmiralProfile = Admirals.NEUTRAL

    /** A shot of the player in play order: its number, its row-major square and the step it was fired on. */
    data class PlayerShot(val number: Int, val cell: Int, val step: Int)

    /** Every square the player shoots at, numbered in play order (a salvo gives one number per square). */
    fun playerShots(replay: MatchReplay): List<PlayerShot> {
        val width = replay.stateAt(0).rules.width
        val shots = ArrayList<PlayerShot>()
        for (step in 0 until replay.steps) {
            if (replay.stateAt(step).current != BattleMatch.PLAYER) continue
            for (cell in replay.actions[step].shotCells()) {
                shots += PlayerShot(shots.size + 1, cell.row * width + cell.col, step)
            }
        }
        return shots
    }

    fun analyze(replay: MatchReplay, withOfficer: Boolean = true): MatchAnalysis {
        val start = replay.stateAt(0)
        val scored = playerShots(replay).map { shot ->
            // Every square of a salvo is scored on the view before the salvo (the squares are not yet pending).
            val view = AiView.from(Observation.of(replay.stateAt(shot.step), BattleMatch.PLAYER))
            ShotScore(shot.number, shot.cell, ShotAnalysis.evaluate(view, shot.cell))
        }
        val last = replay.last
        val shots = shotsForStars(last, BattleMatch.PLAYER)
        val hits = hitsForPrecision(last, BattleMatch.PLAYER)
        val worst = scored
            .filter { it.quality.bestPermille > it.quality.probabilityPermille }
            .sortedWith(compareByDescending<ShotScore> { it.quality.bestPermille - it.quality.probabilityPermille }.thenBy { it.number })
            .take(WORST)
            .map { WorstShot(it.number, it.cell, it.quality.probabilityPermille, it.quality.bestCell, it.quality.bestPermille) }
        val summary = AnalysisSummary(
            shots = shots,
            wasted = scored.count { it.quality.wasted },
            wastedShots = scored.filter { it.quality.wasted }.map { it.number },
            precision = QuickBattle.precision(hits, shots),
            worst = worst,
            officerShots = if (withOfficer) officerShots(start) else null,
        )
        return MatchAnalysis(scored, summary)
    }

    /**
     * Shots the officer needs to sink the admiral's fleet of [start], on the same sea and with a fixed seed. The
     * officer shoots alone (a hunt): its own fleet is drawn from the seed, the admiral's is the real one. Null when
     * the hunt cannot be set up or does not sink the fleet within the sea's squares.
     */
    fun officerShots(start: BattleState): Int? {
        val rules = start.rules.copy(
            salvo = false,
            movingShips = false,
            mines = listOf(emptyList(), start.rules.mines.getOrElse(BattleMatch.ADMIRAL) { emptyList() }),
        )
        val seed = start.attemptSeed
        val budget = rules.width * rules.height
        val admiralFleet = start.sides[BattleMatch.ADMIRAL].ships.map { it.placement }
        val created = BattleSetup.create(
            rules = rules,
            mission = MissionType.HUNT,
            placements = listOf(null, admiralFleet),
            firstPlayer = BattleMatch.PLAYER,
            attemptSeed = seed,
            shotBudget = budget,
        )
        var state = (created as? SetupResult.Created)?.state ?: return null
        val officer = AdmiralOpponent(OFFICER_PROFILE, AiTier.HARD)
        val rng = SeedStreams.rng(seed, SeedStreams.Stream.AI, BattleMatch.PLAYER)
        var steps = 0
        while (state.result == null && steps++ <= budget) {
            val action = officer.decide(Observation.of(state, BattleMatch.PLAYER), rng)
            val outcome = Engine.apply(state, action)
            if (outcome.events.any { it is Event.IllegalAction }) return null
            state = outcome.state
        }
        return if (state.result?.winner == BattleMatch.PLAYER) shotsForStars(state, BattleMatch.PLAYER) else null
    }
}
