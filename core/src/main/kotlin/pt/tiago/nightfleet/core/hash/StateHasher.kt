package pt.tiago.nightfleet.core.hash

import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.DecoyState
import pt.tiago.nightfleet.core.engine.ReconReadingState
import pt.tiago.nightfleet.core.engine.ShipState
import pt.tiago.nightfleet.core.engine.ShotLog
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.engine.SideState
import pt.tiago.nightfleet.core.engine.SonarReadingState
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/**
 * Stable 64-bit hash of a [BattleState] over every field, in a fixed order.
 * Certificates and saved matches use it. Any change to the encoding must bump [ENGINE_VERSION].
 */
object StateHasher {

    fun hash(state: BattleState): Long {
        val h = Accumulator()
        h.int(state.engineVersion)
        rules(h, state.rules)
        h.int(state.mission.ordinal)
        h.long(state.attemptSeed)
        h.nullableInt(state.shotBudget)
        h.int(state.sides.size)
        state.sides.forEach { side(h, it) }
        h.int(state.turn)
        h.int(state.round)
        h.int(state.current)
        h.intList(state.pendingSkips)
        h.long(state.rngState)
        state.result?.let {
            h.boolean(true)
            h.nullableInt(it.winner)
            h.int(it.reason.ordinal)
        } ?: h.boolean(false)
        return h.value
    }

    private fun rules(h: Accumulator, rules: RuleSet) {
        h.int(rules.width)
        h.int(rules.height)
        h.string(rules.fleet.name)
        h.int(rules.fleet.ships.size)
        rules.fleet.ships.forEach { h.int(it.ordinal) }
        h.int(rules.terrain.size)
        rules.terrain.forEach { h.int(it.ordinal) }
        h.boolean(rules.salvo)
        rules.diving?.let {
            h.boolean(true)
            h.int(it.surfaceRounds)
            h.int(it.submergedRounds)
        } ?: h.boolean(false)
        rules.fog?.let {
            h.boolean(true)
            h.int(it.missFadeTurns)
        } ?: h.boolean(false)
        h.boolean(rules.movingShips)
        h.int(rules.mines.size)
        rules.mines.forEach { h.intList(it) }
        h.nullableInt(rules.shotClockSeconds)
        h.boolean(rules.shipsMayTouch)
        h.int(rules.maxTurns)
        Weapon.entries.forEach { weapon ->
            val config: WeaponConfig? = rules.weapons[weapon]
            if (config == null) {
                h.boolean(false)
            } else {
                h.boolean(true)
                weaponConfig(h, config)
            }
        }
        h.int(rules.extraShips.size)
        rules.extraShips.forEach { list ->
            h.int(list.size)
            list.forEach { h.int(it.ordinal) }
        }
        rules.convoy?.let {
            h.boolean(true)
            h.int(it.surviveRounds)
            h.int(it.minFreighters)
        } ?: h.boolean(false)
    }

    private fun weaponConfig(h: Accumulator, config: WeaponConfig) {
        h.int(config.initial)
        h.int(config.max)
        h.int(config.earn.size)
        config.earn.forEach { earn(h, it) }
    }

    private fun earn(h: Accumulator, rule: EarnRule) {
        h.int(rule.on.ordinal)
        h.int(rule.n)
    }

    private fun side(h: Accumulator, side: SideState) {
        board(h, side.board)
        h.int(side.ships.size)
        side.ships.forEach { ship(h, it) }
        h.int(side.decoys.size)
        side.decoys.forEach { decoy(h, it) }
        log(h, side.incoming)
        Weapon.entries.forEach { h.int(side.charges[it] ?: 0) }
        h.boolean(side.moveUsed)
        h.boolean(side.repairUsed)
        h.int(side.hitStreak)
        h.int(side.turnsTaken)
        h.int(side.starShots)
        h.nullableInt(side.movedAtTurn)
        h.int(side.sonarReadings.size)
        side.sonarReadings.forEach { sonar(h, it) }
        h.int(side.reconReadings.size)
        side.reconReadings.forEach { recon(h, it) }
    }

    private fun board(h: Accumulator, board: Board) {
        h.int(board.width)
        h.int(board.height)
        board.terrain.forEach { h.int(it.ordinal) }
        h.intList(board.mines)
    }

    private fun ship(h: Accumulator, ship: ShipState) {
        placement(h, ship.placement)
        h.intList(ship.hits)
        h.boolean(ship.sunk)
    }

    private fun placement(h: Accumulator, placement: Placement) {
        h.int(placement.shipClass.ordinal)
        h.int(placement.origin.row)
        h.int(placement.origin.col)
        h.int(placement.rotationIndex)
    }

    private fun decoy(h: Accumulator, decoy: DecoyState) {
        h.int(decoy.cell)
        h.nullableInt(decoy.hitBy)
        h.nullableInt(decoy.hitTurn)
        h.boolean(decoy.revealed)
    }

    private fun sonar(h: Accumulator, reading: SonarReadingState) {
        h.int(reading.center)
        h.int(reading.count)
        h.int(reading.turn)
    }

    private fun recon(h: Accumulator, reading: ReconReadingState) {
        h.boolean(reading.isRow)
        h.int(reading.index)
        h.boolean(reading.present)
        h.int(reading.turn)
    }

    private fun log(h: Accumulator, log: ShotLog) {
        h.int(log.entries.size)
        for (record in log.entries) {
            h.int(record.turn)
            h.int(record.attacker)
            h.int(record.cell)
            h.int(record.ownTurn)
            when (val result = record.result) {
                ShotResult.Miss -> h.int(0)
                ShotResult.Hit -> h.int(1)
                is ShotResult.Sunk -> {
                    h.int(2)
                    h.int(result.shipClass.ordinal)
                    h.intList(result.cells)
                }
                ShotResult.AutoWater -> h.int(3)
                ShotResult.MineHit -> h.int(4)
                ShotResult.DecoyHit -> h.int(5)
                ShotResult.Contact -> h.int(6)
                ShotResult.Trail -> h.int(7)
                ShotResult.Repaired -> h.int(8)
            }
        }
    }

    /** Order-sensitive fold of 64-bit words through the SplitMix64 finaliser. */
    private class Accumulator {
        var value: Long = 0x4E49_4748_5442L
            private set

        fun long(v: Long) {
            value = SplitMix64.mix(value * 1_000_003L xor v)
        }

        fun int(v: Int) = long(v.toLong())

        fun boolean(v: Boolean) = int(if (v) 1 else 0)

        fun nullableInt(v: Int?) {
            if (v == null) {
                boolean(false)
            } else {
                boolean(true)
                int(v)
            }
        }

        fun intList(values: List<Int>) {
            int(values.size)
            values.forEach { int(it) }
        }

        fun string(text: String) {
            int(text.length)
            text.forEach { int(it.code) }
        }
    }
}
