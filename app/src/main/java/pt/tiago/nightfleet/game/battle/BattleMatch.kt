package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.engine.shotsForStars
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64

/**
 * A quick battle as the app sees it: the engine state plus what the screen needs to remember that
 * the engine does not keep (the auto-water the player has seen, the last shots, every applied action
 * for the save file). Immutable: every change returns a new match.
 */
data class BattleMatch(
    val state: BattleState,
    /** Every accepted action, in play order. Replaying them from the setup gives [state] back. */
    val actions: List<Action> = emptyList(),
    /** Squares of the enemy sea marked as automatic water after the player's sinks. Row-major indices. */
    val autoWaterOnEnemy: Set<Int> = emptySet(),
    /** Row-major index of the player's last shot on the enemy sea. */
    val lastPlayerShot: Int? = null,
    /** Row-major index of the admiral's last shot on the player's sea. */
    val lastEnemyShot: Int? = null,
    /** Officer's hints used in this battle. Using one caps the stars at two. */
    val hintsUsed: Int = 0,
) {
    val width: Int get() = state.rules.width

    val height: Int get() = state.rules.height

    val finished: Boolean get() = state.result != null

    /** True while the player may shoot. */
    val playerTurn: Boolean get() = !finished && state.current == PLAYER

    /** Applies [action] through the engine. A refused action leaves the match as it was. */
    fun apply(action: Action): Step {
        val outcome = Engine.apply(state, action)
        if (outcome.events.any { it is Event.IllegalAction }) {
            return Step(this, outcome.events, accepted = false)
        }
        var autoWater = autoWaterOnEnemy
        var lastPlayer = lastPlayerShot
        var lastEnemy = lastEnemyShot
        for (event in outcome.events) {
            when (event) {
                is Event.AutoWater -> if (event.actor == PLAYER) {
                    autoWater = autoWater + event.cells.map { it.row * width + it.col }
                }
                is Event.Miss -> if (event.actor == PLAYER) {
                    lastPlayer = event.cell.row * width + event.cell.col
                } else {
                    lastEnemy = event.cell.row * width + event.cell.col
                }
                is Event.Hit -> if (event.actor == PLAYER) {
                    lastPlayer = event.cell.row * width + event.cell.col
                } else {
                    lastEnemy = event.cell.row * width + event.cell.col
                }
                else -> Unit
            }
        }
        val next = copy(state = outcome.state, actions = actions + action, autoWaterOnEnemy = autoWater, lastPlayerShot = lastPlayer, lastEnemyShot = lastEnemy)
        return Step(next, outcome.events, accepted = true)
    }

    /** The player shoots row-major square [index] of the enemy sea. */
    fun playerFire(index: Int): Step = apply(Action.Fire(cellOf(index)))

    /** Shots the player has fired so far, for the star count. */
    val playerShots: Int get() = shotsForStars(state, PLAYER)

    fun cellOf(index: Int) = pt.tiago.nightfleet.core.grid.Cell(index / width, index % width)

    data class Step(val match: BattleMatch, val events: List<Event>, val accepted: Boolean)

    companion object {
        const val PLAYER = 0
        const val ADMIRAL = 1

        /**
         * Creates the battle: the player's [placements] are validated by the engine, the admiral's fleet
         * is hidden and drawn from the LAYOUT stream of [attemptSeed]. The player starts.
         */
        fun create(placements: List<Placement>, attemptSeed: Long): SetupResult = BattleSetup.create(
            rules = QuickBattle.rules(),
            mission = MissionType.DUEL,
            placements = listOf(placements, null),
            firstPlayer = PLAYER,
            attemptSeed = attemptSeed,
        )

        /** The first state of a battle built from [setup]: free play and the duel. */
        fun fromSetup(setup: MatchSetup): SetupResult = setup.create()

        /** The brain's view of the battle. It is the only input the admiral gets. */
        fun admiralObservation(match: BattleMatch): Observation = Observation.of(match.state, ADMIRAL)

        /** The admiral's random stream for the turn it is playing: derived, so a restored battle draws the same numbers. */
        fun admiralRng(attemptSeed: Long, turn: Int): SplitMix64 =
            SeedStreams.rng(attemptSeed, SeedStreams.Stream.AI, turn)
    }
}
