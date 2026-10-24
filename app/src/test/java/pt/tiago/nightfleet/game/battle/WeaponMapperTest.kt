package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.game.battle.WeaponFixtures.act
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.ReconMark
import pt.tiago.nightfleet.ui.board.SonarMark

/** The picture of the seas after weapons, built from real engine states. */
class WeaponMapperTest {

    private fun viewOf(match: BattleMatch) = Observation.of(match.state, BattleMatch.PLAYER)

    @Test
    fun aSonarReadingBecomesARingWithItsCountOnTheEnemySea() {
        val rules = WeaponFixtures.rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(1, 2)))
        // Centre (4, 1): the cruiser lies on (4, 0), (4, 1) and (4, 2), all unhit: three squares.
        val match = WeaponFixtures.match(rules).act(
            pt.tiago.nightfleet.core.engine.Action.UseWeapon(
                Weapon.SONAR,
                pt.tiago.nightfleet.core.weapons.WeaponTarget.CellTarget(Cell(4, 1)),
            ),
        )
        val obs = viewOf(match)
        val model = ObservationMapper.withReadings(
            ObservationMapper.enemySea(obs, emptySet(), lastShot = null, selected = null),
            obs,
        )
        assertEquals(listOf(SonarMark(centerIndex = 41, count = 3, fresh = true)), model.sonarReadings)
    }

    @Test
    fun aReconReadingBecomesAMarginMarkForTheLine() {
        val rules = WeaponFixtures.rules(weapons = mapOf(Weapon.RECON to WeaponConfig(1, 1)))
        val match = WeaponFixtures.match(rules).act(
            pt.tiago.nightfleet.core.engine.Action.UseWeapon(
                Weapon.RECON,
                pt.tiago.nightfleet.core.weapons.WeaponTarget.LineTarget(isRow = true, index = 4),
            ),
        )
        val obs = viewOf(match)
        val model = ObservationMapper.withReadings(ObservationMapper.enemySea(obs, emptySet(), null, null), obs)
        assertEquals(listOf(ReconMark(isRow = true, index = 4, present = true)), model.recon)
    }

    @Test
    fun aSonarRingIsNotFreshAfterTwoOwnTurns() {
        val rules = WeaponFixtures.rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(1, 2)))
        var match = WeaponFixtures.match(rules).act(
            pt.tiago.nightfleet.core.engine.Action.UseWeapon(
                Weapon.SONAR,
                pt.tiago.nightfleet.core.weapons.WeaponTarget.CellTarget(Cell(4, 1)),
            ),
        )
        // The admiral answers after every player turn: three more turns of the player pass in between.
        for (row in 0..2) {
            match = match.act(pt.tiago.nightfleet.core.engine.Action.Fire(Cell(row, 8)))
            match = match.act(pt.tiago.nightfleet.core.engine.Action.Fire(Cell(row, 9)))
        }
        val obs = viewOf(match)
        val mark = ObservationMapper.withReadings(ObservationMapper.enemySea(obs, emptySet(), null, null), obs).sonarReadings.single()
        assertEquals(false, mark.fresh)
    }

    @Test
    fun aMissFadesOnTheTurnBeforeItGoes() {
        val fog = FogRule(missFadeTurns = 4)
        val rules = WeaponFixtures.rules(fog = fog)
        // Player fires at column 9; the admiral answers at column 8. Four player turns, the first miss is about to fade.
        var match = WeaponFixtures.match(rules)
        for (row in 0..3) {
            match = match.act(pt.tiago.nightfleet.core.engine.Action.Fire(Cell(row, 9)))
            match = match.act(pt.tiago.nightfleet.core.engine.Action.Fire(Cell(row, 8)))
        }
        val fading = ObservationMapper.fadingMisses(match.state, BattleMatch.PLAYER)
        assertEquals(setOf(9), fading.keys)
        assertEquals(ObservationMapper.FADING_ALPHA, fading.getValue(9))
        // The fading miss is still water on the map: it fades at the end of the next turn.
        val model = ObservationMapper.enemySea(viewOf(match), emptySet(), null, null)
        assertEquals(CellVisual.WATER, model.cells[9])
    }

    @Test
    fun withoutFogNothingFades() {
        val match = WeaponFixtures.match(WeaponFixtures.rules())
        assertTrue(ObservationMapper.fadingMisses(match.state, BattleMatch.PLAYER).isEmpty())
    }

    @Test
    fun aPlayersOwnMinesShowOnTheOwnSea() {
        val rules = WeaponFixtures.rules(mines = listOf(listOf(59), emptyList()))
        val match = WeaponFixtures.match(rules)
        val own = ObservationMapper.ownSea(viewOf(match), lastShot = null)
        assertEquals(CellVisual.MINE, own.cells[59])
    }

    @Test
    fun theEnemysMinesStayHiddenOnTheEnemySea() {
        val rules = WeaponFixtures.rules(mines = listOf(emptyList(), listOf(59)))
        val match = WeaponFixtures.match(rules)
        val enemy = ObservationMapper.enemySea(viewOf(match), emptySet(), null, null)
        assertEquals(CellVisual.UNKNOWN, enemy.cells[59])
    }

    @Test
    fun aMineHitShowsOnTheSeaThatWasShot() {
        val rules = WeaponFixtures.rules(mines = listOf(emptyList(), listOf(59)))
        val match = WeaponFixtures.match(rules).act(pt.tiago.nightfleet.core.engine.Action.Fire(Cell(5, 9)))
        // The player's shot at (5, 9) is a miss: the mine is at index 59 = (5, 9).
        val enemy = ObservationMapper.enemySea(viewOf(match), emptySet(), null, null)
        assertEquals(CellVisual.MINE, enemy.cells[59])
    }
}
