package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import pt.tiago.nightfleet.game.battle.WeaponFixtures.act

/**
 * The battle details of scripted battles on the fixed layout (ships on rows 0, 2, 4, 6 and 8 from column 0). Each test
 * plays the few turns that set one detail and checks the detail alone.
 */
class BattleDetailsTest {

    private fun cell(row: Int, col: Int) = Cell(row, col)

    /** Both sides shoot once: the player at [mine], the admiral at [theirs]. Returns the match after both shots. */
    private fun round(match: BattleMatch, mine: Cell, theirs: Cell): BattleMatch =
        match.act(Action.Fire(mine)).act(Action.Fire(theirs))

    /** Replays the match's own actions from its start state, as the view model does at the end of a battle. */
    private fun detailsOf(start: BattleMatch, played: BattleMatch) = BattleDetails.of(start.state, played.actions)

    @Test
    fun missStreakCountsTheLongestRunOfPlayerMisses() {
        val start = WeaponFixtures.match(WeaponFixtures.rules())
        var m = start
        for (k in 0 until 5) m = round(m, cell(9, 9 - k), cell(9, k))
        assertEquals(5, detailsOf(start, m).missStreakMax)
    }

    @Test
    fun aHitBreaksTheMissStreak() {
        val start = WeaponFixtures.match(WeaponFixtures.rules())
        var m = start
        for (k in 0 until 3) m = round(m, cell(9, 9 - k), cell(9, k))
        m = round(m, cell(0, 0), cell(9, 3))
        for (k in 0 until 4) m = round(m, cell(9, 6 - k), cell(9, 4 + k))
        assertEquals(4, detailsOf(start, m).missStreakMax)
    }

    @Test
    fun aTorpedoThatSinksCountsAsATorpedoSink() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(weapons = mapOf(Weapon.TORPEDO to WeaponConfig(initial = 1, max = 1))))
        var m = round(start, cell(8, 0), cell(9, 0))
        m = m.act(Action.UseWeapon(Weapon.TORPEDO, WeaponTarget.LineTarget(isRow = true, index = 8, fromStart = true)))
        m = m.act(Action.Fire(cell(9, 1)))
        val d = detailsOf(start, m)
        assertEquals(1, d.torpedoSinks)
        assertEquals(0, d.salvoSinks)
    }

    @Test
    fun aSalvoThatSinksCountsAsASalvoSink() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(salvo = true))
        var m = start.act(Action.Salvo(listOf(cell(8, 0), cell(9, 5), cell(9, 6), cell(9, 7), cell(9, 8))))
        m = m.act(Action.Salvo(listOf(cell(9, 0), cell(9, 1), cell(9, 2), cell(9, 3), cell(9, 4))))
        m = m.act(Action.Salvo(listOf(cell(8, 1), cell(9, 9), cell(7, 9), cell(7, 8), cell(7, 7))))
        val d = detailsOf(start, m)
        assertEquals(1, d.salvoSinks)
        assertEquals(0, d.torpedoSinks)
    }

    @Test
    fun onlySonarReadingsThatFindShipsCount() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(initial = 4, max = 4))))
        var m = start
        for ((k, centre) in listOf(cell(1, 1), cell(9, 9), cell(3, 1), cell(5, 1)).withIndex()) {
            m = m.act(Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(centre)))
            m = m.act(Action.Fire(cell(9, k)))
        }
        assertEquals(3, detailsOf(start, m).sonarHits)
    }

    @Test
    fun thePlayersWeaponUsesAreCountedByName() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(initial = 4, max = 4))))
        var m = start
        for ((k, centre) in listOf(cell(1, 1), cell(9, 9)).withIndex()) {
            m = m.act(Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(centre)))
            m = m.act(Action.Fire(cell(9, k)))
        }
        assertEquals(mapOf("SONAR" to 2), detailsOf(start, m).weaponsUsed)
    }

    @Test
    fun aCrossBarrageCountsItsHits() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(weapons = mapOf(Weapon.CROSS_SHELL to WeaponConfig(initial = 1, max = 1))))
        val m = round(start, cell(9, 9), cell(9, 0)).act(Action.UseWeapon(Weapon.CROSS_SHELL, WeaponTarget.CellTarget(cell(0, 1))))
        // The cross on (0, 1) lands on (0, 1), (0, 0) and (0, 2): three ships' squares of the enemy carrier and battleship row.
        assertEquals(3, detailsOf(start, m).crossHits)
    }

    @Test
    fun theAdmiralShootingAPlayerDecoyCountsAsDecoyFooled() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(), decoys = listOf(listOf(cell(9, 9)), emptyList()))
        val m = round(start, cell(9, 0), cell(9, 9))
        assertTrue(detailsOf(start, m).decoyFooled)
    }

    @Test
    fun aShotAtASubmergedSubmarineCountsAsFound() {
        val start = WeaponFixtures.match(RuleSet(diving = DivingRule(surfaceRounds = 2, submergedRounds = 2)))
        var m = start
        m = round(m, cell(9, 9), cell(9, 0))
        m = round(m, cell(9, 8), cell(9, 1))
        m = round(m, cell(6, 0), cell(9, 2))
        assertTrue(detailsOf(start, m).submergedFound)
    }

    @Test
    fun repairingAHitSquareCountsAsRepaired() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(weapons = mapOf(Weapon.REPAIR to WeaponConfig(initial = 1, max = 1))))
        var m = round(start, cell(9, 9), cell(0, 0))
        m = m.act(Action.Repair(cell(0, 0)))
        m = m.act(Action.Fire(cell(9, 8)))
        assertTrue(detailsOf(start, m).repaired)
    }

    @Test
    fun theAdmiralFiringAtAMovedShipsOldSquareCountsAsMovedAndEscaped() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(movingShips = true))
        val destroyer = start.state.sides[0].ships.indexOfFirst { it.shipClass == ShipClass.DESTROYER }
        var m = start.act(Action.MoveShip(destroyer, 1))
        m = m.act(Action.Fire(cell(8, 0)))
        assertTrue(detailsOf(start, m).movedAndEscaped)
    }

    @Test
    fun aShotThatSetsOffAMineCountsAsMinesHit() {
        val start = WeaponFixtures.match(WeaponFixtures.rules(mines = listOf(emptyList(), listOf(99))))
        var m = start.act(Action.Fire(cell(9, 9)))
        m = m.act(Action.Fire(cell(9, 0)))
        m = m.act(Action.Fire(cell(9, 1)))
        m = m.act(Action.Fire(cell(9, 8)))
        assertTrue(detailsOf(start, m).minesHit)
    }

    @Test
    fun aPlainGameHasNoDetails() {
        val start = WeaponFixtures.match(WeaponFixtures.rules())
        val m = round(start, cell(9, 9), cell(9, 0))
        val d = detailsOf(start, m)
        assertEquals(BattleDetails(missStreakMax = 1), d)
        assertFalse(d.decoyFooled || d.submergedFound || d.repaired || d.movedAndEscaped || d.minesHit)
    }

    @Test
    fun theDetailsAreTheSameWhenReplayedAgain() {
        val start = WeaponFixtures.match(WeaponFixtures.rules())
        var m = start
        for (k in 0 until 3) m = round(m, cell(9, 9 - k), cell(9, k))
        assertEquals(detailsOf(start, m), detailsOf(start, m))
    }

    @Test
    fun withDetailsCopiesTheDetailsAndTheFacts() {
        val details = BattleDetails(missStreakMax = 4, torpedoSinks = 1, sonarHits = 3, movedAndEscaped = true, minesHit = true)
        val event = pt.tiago.nightfleet.meta.GameEvent(
            kind = pt.tiago.nightfleet.data.MatchKind.CAMPAIGN,
            result = pt.tiago.nightfleet.data.LogResult.WIN,
        ).withDetails(details, BattleFacts(boardSize = 8, relentless = true, huntHalfBudget = true))
        assertEquals(8, event.boardSize)
        assertTrue(event.relentless)
        assertTrue(event.huntHalfBudget)
        assertEquals(4, event.missStreakMax)
        assertEquals(1, event.torpedoSinks)
        assertEquals(3, event.sonarHits)
        assertTrue(event.movedAndEscaped)
        assertTrue(event.survivedMine)
    }
}
