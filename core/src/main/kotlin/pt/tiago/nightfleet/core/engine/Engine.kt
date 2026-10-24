package pt.tiago.nightfleet.core.engine

import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.Lines
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** Result of one [Engine.apply]: the new state and the events it produced, in order. */
data class Outcome(val state: BattleState, val events: List<Event>)

/**
 * Pure rules of the battle. [apply] never throws: an
 * invalid action returns the unchanged state with an [Event.IllegalAction].
 *
 * Every action first validates against the untouched state, then works on a [Turn] copy. A refused
 * action therefore discards its partial work and returns the original state.
 */
object Engine {
    const val REASON_BATTLE_OVER = "BATTLE_OVER"
    const val REASON_OUT_OF_BOUNDS = "OUT_OF_BOUNDS"
    const val REASON_NOT_A_TARGET = "NOT_A_TARGET"
    const val REASON_ALREADY_FIRED = "ALREADY_FIRED"
    const val REASON_ENGINE_VERSION = "ENGINE_VERSION_MISMATCH"
    const val REASON_SALVO_OFF = "SALVO_NOT_ENABLED"
    const val REASON_SALVO_SIZE = "SALVO_SIZE"
    const val REASON_SALVO_DUPLICATE = "SALVO_DUPLICATE"
    const val REASON_NO_TARGET = "NO_TARGET"
    const val REASON_BAD_TARGET = "BAD_TARGET"
    const val REASON_WEAPON_OFF = "WEAPON_NOT_AVAILABLE"
    const val REASON_NO_CHARGE = "NO_CHARGE"
    const val REASON_REPAIR_USED = "REPAIR_ALREADY_USED"
    const val REASON_NOT_REPAIRABLE = "NOT_REPAIRABLE"
    const val REASON_MOVE_OFF = "MOVE_NOT_ENABLED"
    const val REASON_MOVE_USED = "MOVE_ALREADY_USED"
    const val REASON_BAD_SHIP = "BAD_SHIP"
    const val REASON_BAD_DELTA = "BAD_DELTA"
    const val REASON_SHIP_DAMAGED = "SHIP_DAMAGED"
    const val REASON_MOVE_BLOCKED = "MOVE_BLOCKED"

    fun apply(state: BattleState, action: Action): Outcome {
        if (state.result != null) return reject(state, REASON_BATTLE_OVER)
        if (state.engineVersion != ENGINE_VERSION) return reject(state, REASON_ENGINE_VERSION)
        return when (action) {
            is Action.Fire -> fire(state, action.cell)
            is Action.Salvo -> salvo(state, action.cells)
            is Action.UseWeapon -> useWeapon(state, action.weapon, action.target)
            is Action.MoveShip -> moveShip(state, action.shipId, action.delta)
            is Action.Repair -> useWeapon(state, Weapon.REPAIR, WeaponTarget.CellTarget(action.cell))
            Action.Timeout -> timeout(state)
        }
    }

    private fun reject(state: BattleState, reason: String): Outcome =
        Outcome(state, listOf(Event.IllegalAction(reason)))

    /** Working copy of one action: the sides as they become, the events, and the facts the turn end needs. */
    private class Turn(val state: BattleState) {
        val actor = state.current
        val enemy = 1 - actor
        val sides: MutableList<SideState> = state.sides.toMutableList()
        val events = ArrayList<Event>()

        /** Turns the actor's opponent must skip because of mines. */
        var skips = 0
        var trueHit = false
        var sinks = 0

        /** Shots counted for stars by this action. */
        var shots = 0

        val enemyBoard: Board get() = sides[enemy].board
        val ownBoard: Board get() = sides[actor].board
        val own: SideState get() = sides[actor]
    }

    private enum class Shot { MISS, HIT, SUNK, CONTACT, MINE, DECOY }

    // ---- actions -------------------------------------------------------------------------------

    private fun fire(state: BattleState, cell: Cell): Outcome {
        val actor = state.current
        val board = state.sides[1 - actor].board
        if (!board.inBounds(cell)) return reject(state, REASON_OUT_OF_BOUNDS)
        val index = board.index(cell)
        if (board.terrainAt(index) != Terrain.WATER) return reject(state, REASON_NOT_A_TARGET)
        if (isFired(state, actor, state.sides[1 - actor], index)) return reject(state, REASON_ALREADY_FIRED)
        val t = Turn(state)
        t.shots = 1
        if (shoot(t, index, torpedo = false) == Shot.MINE) t.skips += 1
        return finish(t)
    }

    private fun salvo(state: BattleState, cells: List<Cell>): Outcome {
        if (!state.rules.salvo) return reject(state, REASON_SALVO_OFF)
        val actor = state.current
        val enemySide = state.sides[1 - actor]
        val board = enemySide.board
        val free = (0 until board.size).count { board.terrainAt(it) == Terrain.WATER && !isFired(state, actor, enemySide, it) }
        val afloat = state.sides[actor].ships.count { !it.sunk }
        if (cells.size != minOf(afloat, free)) return reject(state, REASON_SALVO_SIZE)
        if (cells.toSet().size != cells.size) return reject(state, REASON_SALVO_DUPLICATE)
        val indices = ArrayList<Int>(cells.size)
        for (cell in cells) {
            if (!board.inBounds(cell)) return reject(state, REASON_OUT_OF_BOUNDS)
            val index = board.index(cell)
            if (board.terrainAt(index) != Terrain.WATER) return reject(state, REASON_NOT_A_TARGET)
            if (isFired(state, actor, enemySide, index)) return reject(state, REASON_ALREADY_FIRED)
            indices += index
        }
        val t = Turn(state)
        t.shots = indices.size
        for (index in indices) if (shoot(t, index, torpedo = false) == Shot.MINE) t.skips += 1
        return finish(t)
    }

    private fun useWeapon(state: BattleState, weapon: Weapon, target: WeaponTarget): Outcome {
        val config = state.rules.weapons[weapon]
        if (config == null || weapon == Weapon.DECOY) return reject(state, REASON_WEAPON_OFF)
        val actor = state.current
        val charges = state.sides[actor].charges[weapon] ?: 0
        if (charges <= 0) return reject(state, REASON_NO_CHARGE)
        val t = Turn(state)
        t.sides[actor] = t.own.copy(charges = t.own.charges + (weapon to charges - 1))
        val problem = when (weapon) {
            Weapon.SONAR -> sonar(t, target)
            Weapon.TORPEDO -> torpedo(t, target)
            Weapon.RECON -> recon(t, target)
            Weapon.CROSS_SHELL -> cross(t, target)
            Weapon.REPAIR -> repair(t, target)
            Weapon.DECOY -> REASON_WEAPON_OFF
        }
        if (problem != null) return reject(state, problem)
        return finish(t)
    }

    private fun timeout(state: BattleState): Outcome {
        val t = Turn(state)
        t.events += Event.TurnSkipped(t.actor)
        return finish(t)
    }

    private fun moveShip(state: BattleState, shipId: Int, delta: Int): Outcome {
        if (!state.rules.movingShips) return reject(state, REASON_MOVE_OFF)
        val actor = state.current
        val own = state.sides[actor]
        if (own.moveUsed) return reject(state, REASON_MOVE_USED)
        if (delta != 1 && delta != -1) return reject(state, REASON_BAD_DELTA)
        if (shipId !in own.ships.indices) return reject(state, REASON_BAD_SHIP)
        val ship = own.ships[shipId]
        if (ship.sunk || ship.hits.isNotEmpty()) return reject(state, REASON_SHIP_DAMAGED)
        val (dRow, dCol) = axisOf(ship.placement)
        val origin = ship.placement.origin
        val moved = ship.placement.copy(origin = Cell(origin.row + dRow * delta, origin.col + dCol * delta))
        val problem = moveProblem(state, actor, shipId, moved)
        if (problem != null) return reject(state, problem)

        val t = Turn(state)
        val ships = own.ships.toMutableList()
        ships[shipId] = ship.copy(placement = moved)
        t.sides[actor] = own.copy(ships = ships, moveUsed = true, movedAtTurn = state.turn)
        t.events += Event.EnemyShipMoved(actor, 1 - actor)
        return finish(t)
    }

    // ---- weapons -------------------------------------------------------------------------------

    /** Sonar: counts unhit ship squares (submerged and decoys included) in the 3 by 3 around the centre. */
    private fun sonar(t: Turn, target: WeaponTarget): String? {
        val cell = (target as? WeaponTarget.CellTarget)?.cell ?: return REASON_BAD_TARGET
        val board = t.enemyBoard
        if (!board.inBounds(cell)) return REASON_OUT_OF_BOUNDS
        val center = board.index(cell)
        val unhit = unhitSquares(t.state, t.sides[t.enemy], surfaceOnly = false)
        val count = Lines.sonarArea(board, center).count { unhit[it] }
        t.shots = 1
        val own = t.own
        t.sides[t.actor] = own.copy(sonarReadings = own.sonarReadings + SonarReadingState(center, count, t.state.turn))
        t.events += Event.SonarReading(t.actor, t.enemy, cell, count)
        return null
    }

    /** Torpedo: travels along a row or column from its margin. */
    private fun torpedo(t: Turn, target: WeaponTarget): String? {
        val line = target as? WeaponTarget.LineTarget ?: return REASON_BAD_TARGET
        val board = t.enemyBoard
        if (!Lines.lineExists(board, line.isRow, line.index)) return REASON_OUT_OF_BOUNDS
        t.shots = 1
        t.events += Event.TorpedoLaunched(t.actor, t.enemy, line.isRow, line.index, line.fromStart)
        val trail = ArrayList<Cell>()
        for (index in Lines.line(board, line.isRow, line.index, line.fromStart)) {
            val cell = board.cell(index)
            val defender = t.sides[t.enemy]
            if (board.terrainAt(index) == Terrain.ISLAND) {
                t.events += Event.TorpedoBlocked(t.actor, t.enemy, cell)
                break
            }
            if (board.terrainAt(index) == Terrain.REEF) {
                t.events += Event.TorpedoStep(t.actor, t.enemy, cell)
                continue
            }
            val fired = isFired(t.state, t.actor, defender, index)
            val mineLive = board.isMine(index) && !fired
            val decoyLive = defender.decoys.any { it.cell == index && it.hitBy == null }
            val shipLive = shipIndexAt(board, defender.ships, index).let { it >= 0 && index !in defender.ships[it].hits }
            if (mineLive || decoyLive || shipLive) {
                shoot(t, index, torpedo = true)
                break
            }
            t.events += Event.TorpedoStep(t.actor, t.enemy, cell)
            if (!fired) {
                record(t, t.enemy, index, ShotResult.Trail)
                trail += cell
            }
        }
        if (trail.isNotEmpty()) t.events += Event.TorpedoTrail(t.actor, t.enemy, trail)
        return null
    }

    /** Recon: whether an unhit surface ship square (decoys included) lies in a whole row or column. */
    private fun recon(t: Turn, target: WeaponTarget): String? {
        val line = target as? WeaponTarget.LineTarget ?: return REASON_BAD_TARGET
        val board = t.enemyBoard
        if (!Lines.lineExists(board, line.isRow, line.index)) return REASON_OUT_OF_BOUNDS
        val surface = unhitSquares(t.state, t.sides[t.enemy], surfaceOnly = true)
        val present = Lines.line(board, line.isRow, line.index, fromStart = true).any { surface[it] }
        t.shots = 1
        val own = t.own
        t.sides[t.actor] = own.copy(
            reconReadings = own.reconReadings + ReconReadingState(line.isRow, line.index, present, t.state.turn),
        )
        t.events += Event.ReconReading(t.actor, t.enemy, line.isRow, line.index, present)
        return null
    }

    /** Cross shell: the centre and its four orthogonal neighbours, invalid and already fired squares skipped. */
    private fun cross(t: Turn, target: WeaponTarget): String? {
        val cell = (target as? WeaponTarget.CellTarget)?.cell ?: return REASON_BAD_TARGET
        val board = t.enemyBoard
        if (!board.inBounds(cell)) return REASON_OUT_OF_BOUNDS
        val defender = t.sides[t.enemy]
        val squares = Lines.crossArea(board, board.index(cell)).filter {
            board.terrainAt(it) == Terrain.WATER && !isFired(t.state, t.actor, defender, it)
        }
        if (squares.isEmpty()) return REASON_NO_TARGET
        t.shots = squares.size
        var mineHit = false
        for (index in squares) if (shoot(t, index, torpedo = false) == Shot.MINE) mineHit = true
        if (mineHit) t.skips += 1
        return null
    }

    /** Repair: the square becomes intact again. The opponent learns that it is a ship square again. */
    private fun repair(t: Turn, target: WeaponTarget): String? {
        val cell = (target as? WeaponTarget.CellTarget)?.cell ?: return REASON_BAD_TARGET
        val own = t.own
        val board = own.board
        if (!board.inBounds(cell)) return REASON_OUT_OF_BOUNDS
        if (own.repairUsed) return REASON_REPAIR_USED
        val index = board.index(cell)
        val shipIndex = shipIndexAt(board, own.ships, index)
        if (shipIndex < 0) return REASON_NOT_REPAIRABLE
        val ship = own.ships[shipIndex]
        if (ship.sunk || index !in ship.hits) return REASON_NOT_REPAIRABLE
        val hitter = own.incoming.entries.lastOrNull { it.cell == index && it.result == ShotResult.Hit }?.attacker
            ?: return REASON_NOT_REPAIRABLE
        val ships = own.ships.toMutableList()
        ships[shipIndex] = ship.copy(hits = ship.hits - index)
        t.sides[t.actor] = own.copy(ships = ships, repairUsed = true)
        record(t, t.actor, index, ShotResult.Repaired, attacker = hitter)
        t.events += Event.EnemyRepaired(t.actor, t.enemy, cell)
        return null
    }

    // ---- one shot ------------------------------------------------------------------------------

    /**
     * Resolves one shot of the actor at [index] on the enemy sea (steps 1 to 4). The
     * caller counts the shot and handles a mine's skip. A torpedo hits submerged submarines.
     */
    private fun shoot(t: Turn, index: Int, torpedo: Boolean): Shot {
        val board = t.enemyBoard
        val cell = board.cell(index)
        if (board.isMine(index)) {
            record(t, t.enemy, index, ShotResult.MineHit)
            t.events += Event.MineHit(t.actor, t.enemy, cell)
            return Shot.MINE
        }
        val defender = t.sides[t.enemy]
        if (defender.decoys.any { it.cell == index }) {
            t.sides[t.enemy] = defender.copy(
                decoys = defender.decoys.map {
                    if (it.cell == index) it.copy(hitBy = t.actor, hitTurn = t.state.turn) else it
                },
            )
            record(t, t.enemy, index, ShotResult.DecoyHit)
            t.events += Event.Hit(t.actor, t.enemy, cell)
            t.trueHit = true
            return Shot.DECOY
        }
        val shipIndex = shipIndexAt(board, defender.ships, index)
        if (shipIndex < 0) {
            record(t, t.enemy, index, ShotResult.Miss)
            t.events += Event.Miss(t.actor, t.enemy, cell)
            return Shot.MISS
        }
        val ship = defender.ships[shipIndex]
        if (!torpedo && isSubmerged(t.state, ship)) {
            record(t, t.enemy, index, ShotResult.Contact)
            t.events += Event.SubmergedContact(t.actor, t.enemy, cell)
            return Shot.CONTACT
        }

        val squares = squaresOf(ship, board)
        val hits = (ship.hits + index).sorted()
        val sunk = hits.size == squares.size
        val ships = defender.ships.toMutableList()
        ships[shipIndex] = ship.copy(hits = hits, sunk = sunk)
        t.sides[t.enemy] = defender.copy(ships = ships)
        t.trueHit = true
        t.events += Event.Hit(t.actor, t.enemy, cell)
        if (!sunk) {
            record(t, t.enemy, index, ShotResult.Hit)
            return Shot.HIT
        }
        val shape = squares.sorted()
        record(t, t.enemy, index, ShotResult.Sunk(ship.shipClass, shape))
        t.events += Event.Sunk(t.actor, t.enemy, ship.shipClass, shape.map { board.cell(it) })
        t.sinks++
        val water = autoWaterAround(t, shape)
        if (water.isNotEmpty()) {
            water.forEach { record(t, t.enemy, it, ShotResult.AutoWater) }
            t.events += Event.AutoWater(t.actor, t.enemy, water.map { board.cell(it) })
        }
        return Shot.SUNK
    }

    /** Water to mark after a ship sinks: the water neighbours of its squares that no ship occupies and the actor does not know. */
    private fun autoWaterAround(t: Turn, shape: List<Int>): List<Int> {
        val board = t.enemyBoard
        val defender = t.sides[t.enemy]
        val occupied = BooleanArray(board.size)
        for (ship in defender.ships) for (index in squaresOf(ship, board)) occupied[index] = true
        val mark = BooleanArray(board.size)
        for (index in shape) for (neighbour in board.neighbours8(index)) {
            if (board.terrainAt(neighbour) == Terrain.WATER && !occupied[neighbour] &&
                defender.incoming.latest(t.actor, neighbour) == null
            ) {
                mark[neighbour] = true
            }
        }
        return (0 until board.size).filter { mark[it] }
    }

    private fun record(t: Turn, owner: Int, index: Int, result: ShotResult, attacker: Int = t.actor) {
        val side = t.sides[owner]
        val ownTurn = if (attacker == t.actor) t.state.sides[t.actor].turnsTaken else 0
        t.sides[owner] = side.copy(
            incoming = side.incoming.append(ShotRecord(t.state.turn, attacker, index, result, ownTurn)),
        )
    }

    // ---- end of a turn -------------------------------------------------------------------------

    /**
     * Shared end of a turn: hit streak, charges, decoy reveals, fog notices, the end
     * of the battle, the turn pass with skips, and the submarine phase changes.
     */
    private fun finish(t: Turn): Outcome {
        val state = t.state
        val actor = t.actor
        val streak = if (t.trueHit) t.own.hitStreak + 1 else 0
        val turnsTaken = t.own.turnsTaken + 1
        t.sides[actor] = t.own.copy(hitStreak = streak, turnsTaken = turnsTaken, starShots = t.own.starShots + t.shots)
        earnCharges(t, streak, turnsTaken)
        revealDecoys(t)
        announceFaded(t)

        val alternates = state.mission.alternates
        var round = state.round
        if (!alternates || actor == 1) round++
        val turn = state.turn + 1

        val result = battleResult(t, turn, round)
        if (result != null) {
            t.events += Event.BattleEnded(result)
            return Outcome(state.copy(sides = t.sides, turn = turn, round = round, result = result), t.events)
        }

        val skips = state.pendingSkips.toMutableList()
        skips[actor] += t.skips
        var next = nextSide(alternates, actor)
        while (skips[next] > 0) {
            skips[next]--
            t.events += Event.TurnSkipped(next)
            if (alternates && next == 1) round++
            next = nextSide(alternates, next)
        }

        val before = phaseOf(state.rules.diving, state.round)
        val after = phaseOf(state.rules.diving, round)
        if (after != null && after != before) {
            for (side in 0..1) {
                if (t.sides[side].ships.none { !it.sunk && it.shipClass.submersible }) continue
                t.events += if (after == SubmarinePhase.SUBMERGED) Event.SubmarinesDived(side) else Event.SubmarinesSurfaced(side)
            }
        }
        t.events += Event.TurnStarted(next, round)
        return Outcome(
            state.copy(
                sides = t.sides,
                turn = turn,
                round = round,
                current = next,
                pendingSkips = skips,
            ),
            t.events,
        )
    }

    /** Charges earned by the actor after its turn, capped at the weapon's maximum. */
    private fun earnCharges(t: Turn, streak: Int, turnsTaken: Int) {
        for (weapon in Weapon.entries) {
            val config = t.state.rules.weapons[weapon] ?: continue
            var earned = 0
            for (rule in config.earn) {
                earned += when (rule.on) {
                    EarnOn.SINK -> t.sinks
                    EarnOn.STREAK -> if (streak > 0 && streak % rule.n == 0) 1 else 0
                    EarnOn.EVERY -> if (turnsTaken % rule.n == 0) 1 else 0
                }
            }
            repeat(earned) {
                val own = t.own
                val charge = own.charges[weapon] ?: 0
                if (charge < config.max) {
                    t.sides[t.actor] = own.copy(charges = own.charges + (weapon to charge + 1))
                    t.events += Event.ChargeEarned(t.actor, weapon)
                }
            }
        }
    }

    /** Decoys hit by the actor are revealed at the end of its next turn. */
    private fun revealDecoys(t: Turn) {
        val defender = t.sides[t.enemy]
        if (defender.decoys.none { it.hitBy == t.actor && !it.revealed }) return
        val board = t.enemyBoard
        val decoys = defender.decoys.map {
            val due = it.hitBy == t.actor && !it.revealed && (it.hitTurn ?: Int.MAX_VALUE) < t.state.turn
            if (due) {
                t.events += Event.DecoyRevealed(t.actor, t.enemy, board.cell(it.cell))
                it.copy(revealed = true)
            } else {
                it
            }
        }
        t.sides[t.enemy] = defender.copy(decoys = decoys)
    }

    /** Announces the misses of the actor that fade from its view with this turn. */
    private fun announceFaded(t: Turn) {
        val fog = t.state.rules.fog ?: return
        val turnsTaken = t.own.turnsTaken
        val latest = LinkedHashMap<Int, ShotRecord>()
        for (record in t.sides[t.enemy].incoming.entries) if (record.attacker == t.actor) latest[record.cell] = record
        val faded = latest.values
            .filter { isMissLike(it.result) && turnsTaken - it.ownTurn - 1 == fog.missFadeTurns }
            .map { it.cell }
            .sorted()
        if (faded.isNotEmpty()) t.events += Event.MissFaded(t.actor, faded.map { t.enemyBoard.cell(it) })
    }

    private fun battleResult(t: Turn, turn: Int, round: Int): BattleResult? {
        val state = t.state
        if (t.sides[t.enemy].ships.all { it.sunk }) return BattleResult(t.actor, EndReason.FLEET_SUNK)
        val convoy = state.rules.convoy
        if (state.mission == MissionType.CONVOY && convoy != null) {
            val freighters = t.sides[0].ships.count { !it.sunk && it.shipClass == ShipClass.FREIGHTER }
            if (freighters < convoy.minFreighters) return BattleResult(1, EndReason.CONVOY_LOST)
            if (round >= convoy.surviveRounds) return BattleResult(0, EndReason.CONVOY_SURVIVED)
        }
        val budget = state.shotBudget
        if (state.mission == MissionType.HUNT && budget != null && t.sides[0].starShots >= budget) {
            return BattleResult(null, EndReason.BUDGET_EXHAUSTED)
        }
        if (turn >= state.rules.maxTurns) return BattleResult(null, EndReason.TURN_LIMIT)
        return null
    }

    private fun nextSide(alternates: Boolean, actor: Int): Int = if (alternates) 1 - actor else 0

    // ---- helpers -------------------------------------------------------------------------------

    private fun isMissLike(result: ShotResult): Boolean = result is ShotResult.Miss || result is ShotResult.Trail

    /** Whether a miss or a trail of [record] has faded from the attacker's view. */
    internal fun isFaded(fog: FogRule?, attackerTurnsTaken: Int, record: ShotRecord): Boolean =
        fog != null && isMissLike(record.result) && attackerTurnsTaken - record.ownTurn - 1 >= fog.missFadeTurns

    /**
     * Whether [attacker] knows [index] of [defender]'s sea and so cannot shoot it again. A faded miss
     * is unknown again. A contact and a repaired square stay shootable.
     */
    private fun isFired(state: BattleState, attacker: Int, defender: SideState, index: Int): Boolean {
        val record = defender.incoming.latest(attacker, index) ?: return false
        if (isFaded(state.rules.fog, state.sides[attacker].turnsTaken, record)) return false
        return when (record.result) {
            ShotResult.Contact, ShotResult.Repaired -> false
            else -> true
        }
    }

    private fun isSubmerged(state: BattleState, ship: ShipState): Boolean =
        ship.shipClass.submersible && phaseOf(state.rules.diving, state.round) == SubmarinePhase.SUBMERGED

    /** Squares of [defender] that are unhit ship squares (decoys included), for sonar or recon. */
    private fun unhitSquares(state: BattleState, defender: SideState, surfaceOnly: Boolean): BooleanArray {
        val board = defender.board
        val out = BooleanArray(board.size)
        for (ship in defender.ships) {
            if (surfaceOnly && isSubmerged(state, ship)) continue
            for (index in squaresOf(ship, board)) if (index !in ship.hits) out[index] = true
        }
        for (decoy in defender.decoys) if (decoy.hitBy == null) out[decoy.cell] = true
        return out
    }

    /** Index in [ships] of the ship covering [index], or -1. */
    private fun shipIndexAt(board: Board, ships: List<ShipState>, index: Int): Int =
        ships.indexOfFirst { index in squaresOf(it, board) }

    private fun squaresOf(ship: ShipState, board: Board): List<Int> =
        ship.placement.cells().map { board.index(it) }

    /** Unit step (row, column) along the axis of a ship: the long side, or the bar of the seaplane T. */
    private fun axisOf(placement: Placement): Pair<Int, Int> {
        val cells = placement.cells()
        if (cells.map { it.row }.toSet().size == 1) return 0 to 1
        if (cells.map { it.col }.toSet().size == 1) return 1 to 0
        return if (cells.groupBy { it.row }.values.any { it.size >= 3 }) 0 to 1 else 1 to 0
    }

    /** Why a ship cannot be moved to the placement [moved], or null when it can. */
    private fun moveProblem(state: BattleState, actor: Int, shipId: Int, moved: Placement): String? {
        val own = state.sides[actor]
        val board = own.board
        val target = moved.cells()
        if (target.any { !board.inBounds(it) }) return REASON_MOVE_BLOCKED
        val indices = target.map { board.index(it) }
        if (indices.any { board.terrainAt(it) != Terrain.WATER || board.isMine(it) }) return REASON_MOVE_BLOCKED
        val occupied = BooleanArray(board.size)
        own.ships.forEachIndexed { index, ship ->
            if (index != shipId) for (square in squaresOf(ship, board)) occupied[square] = true
        }
        for (decoy in own.decoys) occupied[decoy.cell] = true
        if (indices.any { occupied[it] }) return REASON_MOVE_BLOCKED
        if (!state.rules.shipsMayTouch) {
            for (index in indices) for (neighbour in board.neighbours8(index)) {
                if (occupied[neighbour] || board.isMine(neighbour)) return REASON_MOVE_BLOCKED
            }
        }
        return null
    }
}
