package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.ui.board.CellVisual

class ObservationMapperTest {

    @Test
    fun theEnemySeaShowsNothingBeforeTheFirstShot() {
        val match = BattleFixtures.newMatch()
        val model = ObservationMapper.enemySea(Observation.of(match.state, 0), emptySet(), null, null)
        assertTrue(model.cells.all { it == CellVisual.UNKNOWN })
        assertTrue(model.ships.isEmpty())
    }

    @Test
    fun hiddenEnemyShipsNeverAppearBeforeTheEnd() {
        val match = playTurnsOnly(BattleFixtures.newMatch(), shots = 6)
        val model = ObservationMapper.enemySea(Observation.of(match.state, 0), match.autoWaterOnEnemy, match.lastPlayerShot, null)
        val revealed = ObservationMapper.revealEnemy(model, match.state, 0)
        assertFalse(match.finished)
        assertTrue(revealed.cells.none { it == CellVisual.REVEALED_SHIP })
        assertTrue(revealed.ships.none { it.revealed })
    }

    @Test
    fun aMissIsWaterAndNotAutoWater() {
        val match = BattleFixtures.newMatch()
        val enemySquares = (0 until 100).filter { i -> (0..4).any { s -> i in BattleFixtures.enemySquares(match, s) } }.toSet()
        val free = (0 until 100).first { it !in enemySquares }
        val after = match.playerFire(free).match
        val model = ObservationMapper.enemySea(Observation.of(after.state, 0), after.autoWaterOnEnemy, after.lastPlayerShot, null)
        assertEquals(CellVisual.WATER, model.cells[free])
        assertEquals(free, model.lastShot)
    }

    @Test
    fun sinkingAShipDrawsItsOutlineAndTheAutoWater() {
        var match = BattleFixtures.newMatch()
        val destroyer = BattleFixtures.enemySquares(match, 4)
        for (index in destroyer) {
            match = match.playerFire(index).match
            if (!match.finished && match.state.current == 1) match = match.apply(Action.Timeout).match
        }
        assertTrue("the player sank the destroyer", match.state.sides[1].ships[4].sunk)
        assertTrue("automatic water was recorded", match.autoWaterOnEnemy.isNotEmpty())

        val model = ObservationMapper.enemySea(Observation.of(match.state, 0), match.autoWaterOnEnemy, match.lastPlayerShot, null)
        destroyer.forEach { assertEquals(CellVisual.SUNK, model.cells[it]) }
        assertTrue(model.ships.any { it.sunk && it.cells.sorted() == destroyer.sorted() })
        match.autoWaterOnEnemy.forEach { assertEquals(CellVisual.AUTO_WATER, model.cells[it]) }
    }

    @Test
    fun ownShipsAreDrawnAsWholeHullsAndHitsAreMarked() {
        val match = playTurnsOnly(BattleFixtures.newMatch(), shots = 4)
        val model = ObservationMapper.ownSea(Observation.of(match.state, 0), match.lastEnemyShot)
        assertEquals(5, model.ships.size)
        assertEquals(17, model.cells.count { it == CellVisual.OWN_SHIP || it == CellVisual.OWN_SHIP_HIT })
        val hitCells = match.state.sides[0].ships.flatMap { it.hits }
        hitCells.forEach { assertEquals(CellVisual.OWN_SHIP_HIT, model.cells[it]) }
    }

    @Test
    fun revealShowsTheSurvivorsOnlyAfterTheBattle() {
        var match = BattleFixtures.newMatch()
        var rng = SplitMix64(17)
        while (!match.finished) {
            val side = match.state.current
            val action = ParityBrain.decide(Observation.of(match.state, side), rng)
            match = match.apply(action).match
            rng = SplitMix64(rng.nextLong())
        }
        val model = ObservationMapper.enemySea(Observation.of(match.state, 0), match.autoWaterOnEnemy, match.lastPlayerShot, null)
        val revealed = ObservationMapper.revealEnemy(model, match.state, 0)
        val survivors = match.state.sides[1].ships.filter { !it.sunk }
        assertEquals(survivors.size, revealed.ships.count { it.revealed })
        survivors.forEach { ship ->
            ship.placement.cells().map { BattleFixtures.index(it) }.forEach { index ->
                assertTrue(revealed.cells[index] == CellVisual.REVEALED_SHIP || revealed.cells[index] == CellVisual.HIT)
            }
        }
    }

    @Test
    fun finalMapHasOneCharacterPerSquare() {
        val model = ObservationMapper.enemySea(Observation.of(BattleFixtures.newMatch().state, 0), emptySet(), null, null)
        assertEquals(100, ObservationMapper.finalMapCode(model).length)
        assertEquals(ShipClass.CARRIER.size, 5)
    }

    /** Plays [shots] turns of player shots (the admiral passes). Stops early if the battle ends. */
    private fun playTurnsOnly(match: BattleMatch, shots: Int): BattleMatch {
        var m = match
        var fired = 0
        var index = 0
        while (fired < shots && !m.finished) {
            if (m.state.current == 0) {
                while (index < 100 && m.state.sides[1].incoming.entries.any { it.attacker == 0 && it.cell == index }) index++
                m = m.playerFire(index).match
                fired++
            } else {
                m = m.apply(Action.Timeout).match
            }
        }
        return m
    }
}
