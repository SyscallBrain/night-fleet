package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.game.battle.WeaponFixtures.act

class SetupSaveTest {

    private val setup = MatchSetup(
        rules = WeaponFixtures.rules(
            weapons = mapOf(Weapon.SONAR to WeaponConfig(1, 2)),
            shotClockSeconds = 8,
        ),
        attemptSeed = BattleFixtures.SEED,
        placements = listOf(BattleFixtures.fixedPlacements(), BattleFixtures.fixedPlacements()),
        names = listOf("Ana", "Bruno"),
        opponentId = "coral",
    )

    private fun playedMatch(): BattleMatch {
        val fresh = BattleMatch((setup.create() as pt.tiago.nightfleet.core.engine.SetupResult.Created).state)
        return fresh
            .act(Action.UseWeapon(Weapon.SONAR, pt.tiago.nightfleet.core.weapons.WeaponTarget.CellTarget(Cell(4, 1))))
            .act(Action.Fire(Cell(0, 8)))
            .act(Action.Fire(Cell(0, 9)))
    }

    @Test
    fun theSetupSurvivesAJsonRoundTrip() {
        val decoded = SetupSave.decodeSetup(SetupSave.encodeSetup(setup))
        assertEquals(setup, decoded)
    }

    @Test
    fun aGarbledSetupIsNotDecoded() {
        assertEquals(null, SetupSave.decodeSetup("{not json"))
    }

    @Test
    fun aSavedBattleRestoresToTheSameState() {
        val match = playedMatch()
        val saved = SetupSave.toSaved(MatchKind.FREE_PLAY, SetupSave.freeMatchId(setup.attemptSeed), setup, match, nowMillis = 1L, difficulty = Difficulty.MEDIUM)
        assertEquals("fp-", saved.matchId.take(3))
        assertEquals(setup.opponentId, saved.admiralId)
        val restored = SetupSave.restore(saved) as RestoreResult.Restored
        assertEquals(match.state, restored.match.state)
        assertEquals(3, restored.match.actions.size)
    }

    @Test
    fun aTamperedHashDiscardsTheSave() {
        val saved = SetupSave.toSaved(MatchKind.FREE_PLAY, "fp-x", setup, playedMatch(), nowMillis = 1L)
        val tampered = saved.copy(expectedHash = saved.expectedHash + 1)
        assertEquals(RestoreResult.Discarded("HASH_MISMATCH"), SetupSave.restore(tampered))
    }

    @Test
    fun aSaveWithoutASetupIsLeftToTheQuickBattle() {
        val saved = SetupSave.toSaved(MatchKind.FREE_PLAY, "fp-x", setup, playedMatch(), nowMillis = 1L).copy(setup = null)
        assertEquals(RestoreResult.Discarded("NO_SETUP"), SetupSave.restore(saved))
    }

    @Test
    fun aMatchIdIsStableForASeed() {
        assertTrue(SetupSave.freeMatchId(7L).startsWith("fp-"))
        assertEquals(SetupSave.freeMatchId(7L), SetupSave.freeMatchId(7L))
        assertTrue(SetupSave.freeMatchId(7L) != BattleSave.matchIdOf(7L))
    }
}
