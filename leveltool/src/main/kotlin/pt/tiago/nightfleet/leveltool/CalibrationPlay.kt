package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.ai.AdmiralAI
import pt.tiago.nightfleet.core.ai.AdmiralBrain
import pt.tiago.nightfleet.core.ai.AdmiralPlacer
import pt.tiago.nightfleet.core.ai.AdmiralProfile
import pt.tiago.nightfleet.core.ai.AiSkill
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.ai.AiView
import pt.tiago.nightfleet.core.ai.HumanModel
import pt.tiago.nightfleet.core.ai.OfficerHint
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.toSkill
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.engine.shotsForStars
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.mission.BossSeries
import pt.tiago.nightfleet.core.mission.DuelOutcome
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.mission.SeriesStatus
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon

/**
 * The simulated players of calibration played through [Engine.apply]. The admirals are the real
 * [AdmiralBrain] with the effective [AiSkill] of the variant ([toSkill]), so a calibrated variant plays the
 * same way in the app. Every battle is a pure function of its inputs: no shared mutable state, so threads
 * cannot change a result.
 */

/** Stream offset of the HARD reference games, so they never reuse a game seed of the sample. */
internal const val REFERENCE_STREAM = 1L shl 32

/** Safety net against a battle that never ends (the engine has a turn limit, so this never fires). */
private const val MAX_STEPS = 100_000

/** Decoys of the admiral: one Isco per battle, as in the campaign. */
private const val ADMIRAL_DECOYS = 1

/** Simulated human of a difficulty. */
internal fun humanFor(difficulty: Difficulty): HumanModel = when (difficulty) {
    Difficulty.EASY -> HumanModel.EASY_PLAYER
    Difficulty.MEDIUM -> HumanModel.MEDIUM_PLAYER
    Difficulty.HARD -> HumanModel.HARD_PLAYER
}

/** Who starts a game, from the variant rule. */
internal fun firstPlayerOf(rule: FirstPlayer, seed: Long): Int = when (rule) {
    FirstPlayer.PLAYER -> 0
    FirstPlayer.ADMIRAL -> 1
    FirstPlayer.ALTERNATE -> SeedStreams.firstPlayer(seed)
}

/** One side of a battle as the leveltool plays it: who decides, with which profile, tier and effective skill. */
internal class Seat(val ai: AdmiralAI, val profile: AdmiralProfile, val tier: AiTier, val skill: AiSkill)

/** A finished battle: who won, the player's shots, every action in order and the final state hash. */
internal class BattleRecord(
    val winner: Int?,
    val reason: EndReason,
    val shots: Int,
    val actions: List<Action>,
    val finalHash: Long,
)

/**
 * Plays one battle to its end. [seats] has one entry per side: side 0 is the simulated human, side 1 the
 * admiral. A `null` placement is a fleet drawn by the engine's RandomPlacer (BALANCED) from the LAYOUT
 * stream of [seed]. With the salvo rule each turn is a salvo of `min(afloat, unknown)` squares decided by
 * [pt.tiago.nightfleet.core.ai.AdmiralAI.decideSalvo]; otherwise one intent is played.
 */
internal fun playBattle(
    rules: RuleSet,
    mission: MissionType,
    placements: List<List<Placement>?>,
    firstPlayer: Int,
    seed: Long,
    budget: Int?,
    seats: List<Seat>,
    decoys: List<List<Cell>> = listOf(emptyList(), emptyList()),
    trace: ((before: BattleState, action: Action, after: BattleState, fellBack: Boolean) -> Unit)? = null,
): BattleRecord {
    val setup = BattleSetup.create(rules, mission, placements, firstPlayer, seed, budget, decoys)
    var state = when (setup) {
        is SetupResult.Created -> setup.state
        is SetupResult.Rejected -> throw IllegalArgumentException("battle rejected: ${setup.reasons.joinToString()}")
    }
    val rngs = List(2) { SeedStreams.rng(seed, SeedStreams.Stream.AI, it) }
    val actions = ArrayList<Action>()
    var steps = 0
    while (state.result == null) {
        check(++steps <= MAX_STEPS) { "battle did not end after $MAX_STEPS steps" }
        val actor = state.current
        val seat = seats[actor]
        val view = AiView.from(Observation.of(state, actor))
        val proposed = proposal(state, view, seat, rngs[actor])
        var action = proposed
        var outcome = Engine.apply(state, action)
        if (outcome.events.any { it is Event.IllegalAction }) {
            // The rules refuse the intent (a blocked move, for instance): the admiral fires at its best unknown square
            // instead, as the app does. Only a refusal of that fallback is an error.
            action = fallbackOf(view)
            outcome = Engine.apply(state, action)
            outcome.events.filterIsInstance<Event.IllegalAction>().firstOrNull()?.let {
                throw IllegalStateException("admiral proposed an illegal action: ${it.reason} for $proposed")
            }
        }
        actions += action
        trace?.invoke(state, action, outcome.state, action !== proposed)
        state = outcome.state
    }
    val result: BattleResult = checkNotNull(state.result)
    return BattleRecord(
        winner = result.winner,
        reason = result.reason,
        shots = shotsForStars(state, 0),
        actions = actions,
        finalHash = StateHasher.hash(state),
    )
}

/**
 * What [seat] proposes for [view]: a salvo of `min(afloat, unknown)` squares with the rule Salva, otherwise one
 * intent. Without any unknown square the seat passes (the engine has nothing else to accept).
 */
private fun proposal(state: BattleState, view: AiView, seat: Seat, rng: SplitMix64): Action {
    val unknown = view.firableCells().size
    if (unknown == 0) return Action.Timeout
    if (!state.rules.salvo) return seat.ai.decide(view, seat.profile, seat.tier, rng, seat.skill).toAction()
    val afloat = state.sides[state.current].ships.count { !it.sunk }
    val shots = minOf(afloat, unknown)
    if (shots == 0) return Action.Timeout
    val cells = seat.ai.decideSalvo(view, seat.profile, seat.tier, rng, shots, seat.skill)
    return Action.Salvo(cells.map { view.cell(it) })
}

/** The admiral's fallback when its intent is refused: its best unknown square by density, or a pass. */
private fun fallbackOf(view: AiView): Action =
    OfficerHint.topCells(view, 1).firstOrNull()?.let { Action.Fire(view.cell(it)) } ?: Action.Timeout

/**
 * The admiral's decoys on its own sea: one per battle, placed by [AdmiralPlacer] with the admiral's
 * style, when the rules have the decoy weapon. Otherwise none. Deterministic from [seed] and the layout.
 */
internal fun admiralDecoys(rules: RuleSet, enemy: List<Placement>, profile: AdmiralProfile, seed: Long): List<List<Cell>> {
    if (Weapon.DECOY !in rules.weapons.keys) return listOf(emptyList(), emptyList())
    val rng = SeedStreams.rng(seed, SeedStreams.Stream.LAYOUT, 10 + 1)
    val cells = AdmiralPlacer.placeDecoys(rules.boardFor(1), enemy, ADMIRAL_DECOYS, profile, rng, rules.shipsMayTouch)
    return listOf(emptyList(), cells)
}

/** The witness of a winning game: what `verify-certificates` replays. */
internal class Witness(
    val seed: Long,
    val firstPlayer: Int,
    val enemyLayout: Int,
    val decoys: List<Cell>,
    val actions: List<Action>,
    val finalHash: Long,
)

/** One simulated game (a duel, a hunt, or a whole boss series). [samples] are the shots of its won duels. */
internal class GameRecord(val won: Boolean, val samples: List<Int>, val witness: Witness?)

/** A measured batch: [wins] of [games], the winning shot samples and the first winning witness. */
internal class Batch(val games: Int, val wins: Int, val samples: List<Int>, val witness: Witness?) {
    val winPermille: Int get() = permille(wins, games)
}

internal fun runBatch(games: Int, gameAt: (Int) -> GameRecord): Batch {
    var wins = 0
    val samples = ArrayList<Int>()
    var witness: Witness? = null
    for (index in 0 until games) {
        val game = gameAt(index)
        if (game.won) {
            wins++
            samples += game.samples
            if (witness == null) witness = game.witness
        }
    }
    return Batch(games, wins, samples, witness)
}

/**
 * Everything one variant needs to be simulated. Built on the main thread, then used read-only by a
 * worker, so a worker never touches the content files. [ai] is the admiral of the variant as content;
 * the tuning search overrides its knobs per measurement ([skillOf], [rulesFor]).
 */
internal class Battlefield(
    val kind: MissionKind,
    val difficulty: Difficulty,
    val rules: RuleSet,
    val firstPlayer: FirstPlayer,
    val layouts: List<List<Placement>>,
    val admiral: AdmiralProfile,
    val levelSeed: Long,
    val ai: AiConfigDto,
) {
    /** The admiral's tier: the content's, which may be escalated above the difficulty. */
    private val tier: AiTier get() = AiTier.valueOf(ai.tier.name)

    /** The same battlefield with the admiral at [to] (tier escalation). */
    fun escalatedTo(to: Difficulty): Battlefield = Battlefield(kind, difficulty, rules, firstPlayer, layouts, admiral, levelSeed, ai.copy(tier = to))
    private val human = humanFor(difficulty)

    /** The effective skill of the admiral with the knobs of one measurement. */
    fun skillOf(knobs: Knobs): AiSkill = ai.copy(
        blunder = knobs.blunder,
        followUpMiss = knobs.followUp,
        weaponDelay = knobs.weaponDelay,
        admiralWeapons = if (knobs.weaponsOff) emptyList() else ai.admiralWeapons,
    ).toSkill(admiral, tier)

    /** The knobs at the start of the tuning search: the variant's own values, the profile's delay when it has none. */
    fun startKnobs(): Knobs = Knobs(
        followUp = ai.followUpMiss,
        blunder = ai.blunder,
        weaponDelay = ai.weaponDelay ?: AiSkill.derived(admiral, tier).weaponDelayTurns,
        surviveRounds = rules.convoy?.surviveRounds,
        minFreighters = rules.convoy?.minFreighters,
    )

    /** The rules with the survive rounds and freighters of [knobs] (CONVOY only). */
    fun rulesFor(knobs: Knobs): RuleSet {
        val convoy = rules.convoy ?: return rules
        return rules.copy(
            convoy = convoy.copy(
                surviveRounds = knobs.surviveRounds ?: convoy.surviveRounds,
                minFreighters = knobs.minFreighters ?: convoy.minFreighters,
            ),
        )
    }

    internal fun duelSeats(skill: AiSkill): List<Seat> = listOf(
        Seat(human, admiral, tier, AiSkill(blunderPermille = 0)),
        Seat(AdmiralBrain, admiral, tier, skill),
    )

    /** DUEL and CONVOY game number [index] against the admiral with [knobs]. */
    fun duelGame(index: Int, knobs: Knobs): GameRecord {
        val seed = SplitMix64.split(levelSeed, index.toLong())
        val layout = index % layouts.size
        val rules = rulesFor(knobs)
        val enemy = layouts[layout]
        val decoys = admiralDecoys(rules, enemy, admiral, seed)
        val record = playBattle(
            rules, engineType(), listOf(null, enemy), firstPlayerOf(firstPlayer, seed), seed, null,
            duelSeats(skillOf(knobs)), decoys,
        )
        return gameOf(record, seed, layout, decoys[1])
    }

    /** BOSS series number [index]: best of three duels, each duel on its own setup seed. */
    fun seriesGame(index: Int, knobs: Knobs): GameRecord {
        var series = BossSeries(SplitMix64.split(levelSeed, index.toLong()))
        val samples = ArrayList<Int>()
        var witness: Witness? = null
        val rules = rulesFor(knobs)
        while (series.status == SeriesStatus.IN_PROGRESS) {
            val duel = series.duelIndex
            val seed = series.nextSetupSeed()
            val layout = (index + duel) % layouts.size
            val enemy = layouts[layout]
            val decoys = admiralDecoys(rules, enemy, admiral, seed)
            val record = playBattle(
                rules, MissionType.DUEL, listOf(null, enemy), series.startingPlayer, seed, null,
                duelSeats(skillOf(knobs)), decoys,
            )
            if (record.winner == 0) {
                samples += record.shots
                if (witness == null) witness = Witness(seed, series.startingPlayer, layout, decoys[1], record.actions, record.finalHash)
            }
            series = series.record(DuelOutcome.of(BattleResult(record.winner, record.reason)))
        }
        return GameRecord(series.status == SeriesStatus.WON, if (series.status == SeriesStatus.WON) samples else emptyList(), witness)
    }

    /** HUNT game number [index] of the simulated human with [budget] shots. The admiral never fires. */
    fun huntGame(index: Int, budget: Int): GameRecord {
        val seed = SplitMix64.split(levelSeed, index.toLong())
        val layout = index % layouts.size
        val shooter = Seat(human, admiral, tier, AiSkill(blunderPermille = 0))
        val record = playBattle(rules, MissionType.HUNT, listOf(null, layouts[layout]), 0, seed, budget, listOf(shooter, shooter))
        return gameOf(record, seed, layout, emptyList())
    }

    /** Shots the HARD admiral needs to sink the fleet of every layout, one game per layout (no blunder). */
    fun hardReference(): List<Int> {
        val fullBoard = rules.width * rules.height
        val reference = Seat(AdmiralBrain, admiral, AiTier.HARD, AiSkill.derived(admiral, AiTier.HARD).copy(blunderPermille = 0))
        return layouts.indices.map { layout ->
            val seed = SplitMix64.split(levelSeed, REFERENCE_STREAM + layout)
            // HUNT: side 0 is the only shooter, so the HARD admiral takes its place.
            val record = playBattle(rules, MissionType.HUNT, listOf(null, layouts[layout]), 0, seed, fullBoard, listOf(reference, reference))
            // A layout the HARD admiral cannot finish in the full board never fits any budget.
            if (record.winner == 0) record.shots else fullBoard + 1
        }
    }

    private fun gameOf(record: BattleRecord, seed: Long, layout: Int, decoys: List<Cell>): GameRecord {
        val won = record.winner == 0
        val witness = if (won) {
            Witness(seed, firstPlayerOf(firstPlayer, seed), layout, decoys, record.actions, record.finalHash)
        } else {
            null
        }
        return GameRecord(won, if (won) listOf(record.shots) else emptyList(), witness)
    }

    private fun engineType(): MissionType = when (kind) {
        MissionKind.CONVOY -> MissionType.CONVOY
        else -> MissionType.DUEL
    }
}
