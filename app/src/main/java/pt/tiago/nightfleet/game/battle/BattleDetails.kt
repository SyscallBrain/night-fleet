package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.meta.GameEvent

/**
 * The details of one finished battle that the achievements read. Derived by replaying the
 * accepted actions from the start state: the engine says what each shot did, the actions say which weapon made it.
 * Pure: the same start state and actions always give the same details.
 */
data class BattleDetails(
    /** Longest run of the player's shots that missed the water, in play order. */
    val missStreakMax: Int = 0,
    val torpedoSinks: Int = 0,
    val salvoSinks: Int = 0,
    /** Sonar readings of the player that found at least one ship square. */
    val sonarHits: Int = 0,
    /** The most hits (and sinking shots) of one cross barrage of the player. */
    val crossHits: Int = 0,
    /** The admiral shot a decoy (Isco) of the player's sea. */
    val decoyFooled: Boolean = false,
    /** The player shot a submerged submarine. */
    val submergedFound: Boolean = false,
    /** The player repaired one of its squares. The achievement also needs the win. */
    val repaired: Boolean = false,
    /** The player moved a ship and the admiral later fired at a square it had left. */
    val movedAndEscaped: Boolean = false,
    /** The player's shot set off a mine. The achievement also needs the win. */
    val minesHit: Boolean = false,
    /** Accepted weapon uses of the player, by [Weapon] name (the keys of the statistics). */
    val weaponsUsed: Map<String, Int> = emptyMap(),
) {
    companion object {
        /** Replays [actions] from [start]. Refused actions are skipped (none are stored, so none is expected). */
        fun of(start: BattleState, actions: List<Action>): BattleDetails {
            var state = start
            var streak = 0
            var longest = 0
            var torpedo = 0
            var salvo = 0
            var sonar = 0
            var cross = 0
            var decoy = false
            var submerged = false
            var repaired = false
            var mine = false
            var moved = false
            var movedFrom: List<Cell> = emptyList()
            val weapons = LinkedHashMap<String, Int>()
            for (action in actions) {
                val actor = state.current
                val before = state
                if (action is Action.MoveShip && actor == BattleMatch.PLAYER) {
                    movedFrom = state.sides[BattleMatch.PLAYER].ships.getOrNull(action.shipId)?.placement?.cells().orEmpty()
                }
                val outcome = Engine.apply(state, action)
                if (outcome.events.any { it is Event.IllegalAction }) continue
                val playerTurn = actor == BattleMatch.PLAYER
                val oldRecords = before.sides[BattleMatch.PLAYER].incoming.entries.size
                var playerHitsInAction = 0
                var playerSinksInAction = 0
                for (event in outcome.events) {
                    when (event) {
                        is Event.Miss -> if (event.actor == BattleMatch.PLAYER) {
                            streak++
                            longest = maxOf(longest, streak)
                        } else if (event.cell in movedFrom) {
                            moved = true
                        }
                        is Event.Hit -> if (event.actor == BattleMatch.PLAYER) {
                            streak = 0
                            playerHitsInAction++
                        }
                        is Event.Sunk -> if (event.actor == BattleMatch.PLAYER) {
                            streak = 0
                            playerHitsInAction++
                            playerSinksInAction++
                        }
                        is Event.MineHit -> if (event.actor == BattleMatch.PLAYER) {
                            streak = 0
                            mine = true
                        }
                        is Event.SubmergedContact -> if (event.actor == BattleMatch.PLAYER) {
                            streak = 0
                            submerged = true
                        }
                        is Event.SonarReading -> if (event.actor == BattleMatch.PLAYER && event.count > 0) sonar++
                        is Event.EnemyRepaired -> if (event.actor == BattleMatch.PLAYER) repaired = true
                        else -> Unit
                    }
                }
                if (playerTurn) {
                    when (action) {
                        is Action.Salvo -> salvo += playerSinksInAction
                        is Action.UseWeapon -> {
                            weapons[action.weapon.name] = (weapons[action.weapon.name] ?: 0) + 1
                            if (action.weapon == Weapon.TORPEDO) torpedo += playerSinksInAction
                            if (action.weapon == Weapon.CROSS_SHELL) cross = maxOf(cross, playerHitsInAction)
                        }
                        else -> Unit
                    }
                }
                // A decoy shot by the admiral is recorded as DecoyHit on the player's sea.
                val newRecords = outcome.state.sides[BattleMatch.PLAYER].incoming.entries.drop(oldRecords)
                if (newRecords.any { it.attacker != BattleMatch.PLAYER && it.result == ShotResult.DecoyHit }) decoy = true
                state = outcome.state
            }
            return BattleDetails(
                missStreakMax = longest,
                torpedoSinks = torpedo,
                salvoSinks = salvo,
                sonarHits = sonar,
                crossHits = cross,
                decoyFooled = decoy,
                submergedFound = submerged,
                repaired = repaired,
                movedAndEscaped = moved,
                minesHit = mine,
                weaponsUsed = weapons,
            )
        }
    }
}

/** What the battle screen knows and the replay does not: the board size and the mode of the battle. */
data class BattleFacts(
    val boardSize: Int,
    /** A free play game against the Relentless admiral. */
    val relentless: Boolean,
    /** A campaign hunt won with at most half of its shot budget. */
    val huntHalfBudget: Boolean,
)

/** Copies the battle details and facts onto the meta event of the battle. */
fun GameEvent.withDetails(details: BattleDetails, facts: BattleFacts): GameEvent = copy(
    boardSize = facts.boardSize,
    relentless = facts.relentless,
    huntHalfBudget = facts.huntHalfBudget,
    missStreakMax = details.missStreakMax,
    torpedoSinks = details.torpedoSinks,
    salvoSinks = details.salvoSinks,
    sonarHits = details.sonarHits,
    crossHits = details.crossHits,
    decoyFooled = details.decoyFooled,
    submergedFound = details.submergedFound,
    repairedAndWon = details.repaired,
    movedAndEscaped = details.movedAndEscaped,
    survivedMine = details.minesHit,
)
