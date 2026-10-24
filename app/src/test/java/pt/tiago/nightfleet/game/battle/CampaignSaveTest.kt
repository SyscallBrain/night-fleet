package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import pt.tiago.nightfleet.data.BossSeries
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.ProfileCodec
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.game.battle.WeaponFixtures.act

/** A campaign battle save: mission, difficulty and boss series travel with the replayable match. */
class CampaignSaveTest {

    private val setup = MatchSetup(
        rules = WeaponFixtures.rules(
            weapons = mapOf(Weapon.SONAR to WeaponConfig(1, 2)),
            shotClockSeconds = 8,
        ),
        attemptSeed = BattleFixtures.SEED,
        placements = listOf(BattleFixtures.fixedPlacements(), BattleFixtures.fixedPlacements()),
        opponentId = "coral",
    )

    private fun playedMatch(): BattleMatch {
        val fresh = BattleMatch((setup.create() as SetupResult.Created).state)
        return fresh
            .act(Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(Cell(4, 1))))
            .act(Action.Fire(Cell(0, 8)))
    }

    private fun campaignSave(series: BossSeries? = BossSeries(wins = 1, losses = 0, shotsPerDuel = listOf(31))): SavedMatch =
        SetupSave.toSaved(
            kind = MatchKind.CAMPAIGN,
            matchId = "fp-campaign",
            setup = setup,
            match = playedMatch(),
            nowMillis = 1L,
            difficulty = Difficulty.HARD,
            missionId = "s01m050",
            bossSeries = series,
        )

    @Test
    fun aCampaignSaveKeepsItsMissionDifficultyAndBossSeriesThroughJson() {
        val saved = campaignSave()
        val text = ProfileCodec.documentJson.encodeToString(SavedMatch.serializer(), saved)
        val decoded = ProfileCodec.documentJson.decodeFromString(SavedMatch.serializer(), text)
        assertEquals("s01m050", decoded.missionId)
        assertEquals(Difficulty.HARD, decoded.difficulty)
        assertEquals(BossSeries(wins = 1, losses = 0, shotsPerDuel = listOf(31)), decoded.bossSeries)
        assertEquals(MatchKind.CAMPAIGN, decoded.kind)
    }

    @Test
    fun aCampaignBattleReplaysToTheSameStateAfterARoundTrip() {
        val match = playedMatch()
        val saved = SetupSave.toSaved(MatchKind.CAMPAIGN, "fp-x", setup, match, 1L, Difficulty.EASY, missionId = "s01m002")
        val text = ProfileCodec.documentJson.encodeToString(SavedMatch.serializer(), saved)
        val decoded = ProfileCodec.documentJson.decodeFromString(SavedMatch.serializer(), text)
        val restored = SetupSave.restore(decoded) as RestoreResult.Restored
        assertEquals(match.state, restored.match.state)
        assertEquals(2, restored.match.actions.size)
        assertTrue(SetupSave.decodeSetup(decoded.setup.orEmpty()) != null)
    }

    @Test
    fun aTamperedCampaignHashIsDiscardedNotResumed() {
        val saved = campaignSave()
        val tampered = saved.copy(expectedHash = saved.expectedHash xor 1L)
        assertEquals(RestoreResult.Discarded("HASH_MISMATCH"), SetupSave.restore(tampered))
    }

    @Test
    fun aTamperedActionListIsDiscardedToo() {
        val saved = campaignSave()
        val tampered = saved.copy(actions = saved.actions.dropLast(1))
        assertTrue(SetupSave.restore(tampered) is RestoreResult.Discarded)
    }
}
