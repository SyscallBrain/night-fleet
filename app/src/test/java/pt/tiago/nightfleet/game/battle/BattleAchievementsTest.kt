package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.game.battle.WeaponFixtures.act
import pt.tiago.nightfleet.game.campaign.BattleResultRules
import pt.tiago.nightfleet.meta.AchievementEvaluator
import java.time.LocalDate

/**
 * Each battle-detail achievement is unlocked by the event the battle screen builds: the details replayed from the
 * actions, with the facts of the battle, on top of the summary event. One test per achievement.
 */
class BattleAchievementsTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 23)
    private val facts = BattleFacts(boardSize = 10, relentless = false, huntHalfBudget = false)

    private fun unlocks(id: String, result: LogResult, details: BattleDetails, facts: BattleFacts = this.facts): Boolean {
        val summary = QuickBattle.Summary(
            won = result == LogResult.WIN,
            reason = EndReason.FLEET_SUNK,
            shots = 20,
            hits = 10,
            precision = 50,
            stars = 1,
            enemyShipsSunk = 5,
            enemyShipsTotal = 5,
            ownShipsSunk = 0,
            longestHitStreak = 1,
            sunkByClass = emptyMap(),
        )
        val event = BattleResultRules.gameEvent(MatchKind.CAMPAIGN, Difficulty.MEDIUM, summary, convoy = null)
            .withDetails(details, facts)
        return id in AchievementEvaluator.evaluate(Profile(), event, today).newlyUnlocked
    }

    @Test
    fun torpedoSinkUnlocksTorpedoSink() {
        assertTrue(unlocks("torpedo_sink", LogResult.LOSS, BattleDetails(torpedoSinks = 1)))
        assertFalse(unlocks("torpedo_sink", LogResult.LOSS, BattleDetails(salvoSinks = 1)))
    }

    @Test
    fun salvoSinkUnlocksSalvoSink() {
        assertTrue(unlocks("salvo_sink", LogResult.LOSS, BattleDetails(salvoSinks = 1)))
        assertFalse(unlocks("salvo_sink", LogResult.LOSS, BattleDetails(torpedoSinks = 1)))
    }

    @Test
    fun sonarSharpNeedsThreeReadingsThatFoundShips() {
        assertTrue(unlocks("sonar_sharp", LogResult.LOSS, BattleDetails(sonarHits = 3)))
        assertFalse(unlocks("sonar_sharp", LogResult.LOSS, BattleDetails(sonarHits = 2)))
    }

    @Test
    fun crossThreeNeedsThreeHitsInOneBarrage() {
        assertTrue(unlocks("cross_three", LogResult.LOSS, BattleDetails(crossHits = 3)))
        assertFalse(unlocks("cross_three", LogResult.LOSS, BattleDetails(crossHits = 2)))
    }

    @Test
    fun decoyWinNeedsTheWinAndAFooledAdmiral() {
        assertTrue(unlocks("decoy_win", LogResult.WIN, BattleDetails(decoyFooled = true)))
        assertFalse(unlocks("decoy_win", LogResult.LOSS, BattleDetails(decoyFooled = true)))
        assertFalse(unlocks("decoy_win", LogResult.WIN, BattleDetails()))
    }

    @Test
    fun submergedFoundUnlocksOnAContact() {
        assertTrue(unlocks("submerged_found", LogResult.LOSS, BattleDetails(submergedFound = true)))
        assertFalse(unlocks("submerged_found", LogResult.LOSS, BattleDetails()))
    }

    @Test
    fun repairWinNeedsTheWinAndARepair() {
        assertTrue(unlocks("repair_win", LogResult.WIN, BattleDetails(repaired = true)))
        assertFalse(unlocks("repair_win", LogResult.LOSS, BattleDetails(repaired = true)))
    }

    @Test
    fun moveEscapeUnlocksWhenTheAdmiralMissesAMovedShip() {
        assertTrue(unlocks("move_escape", LogResult.LOSS, BattleDetails(movedAndEscaped = true)))
        assertFalse(unlocks("move_escape", LogResult.LOSS, BattleDetails()))
    }

    @Test
    fun mineWinNeedsTheWinAndAMineThatMissedTheShip() {
        assertTrue(unlocks("mine_win", LogResult.WIN, BattleDetails(minesHit = true)))
        assertFalse(unlocks("mine_win", LogResult.LOSS, BattleDetails(minesHit = true)))
    }

    @Test
    fun noMissFiveNeedsAWinWithFewerThanFiveMissesInARow() {
        assertTrue(unlocks("no_miss_five", LogResult.WIN, BattleDetails(missStreakMax = 4)))
        assertFalse(unlocks("no_miss_five", LogResult.WIN, BattleDetails(missStreakMax = 5)))
        assertFalse(unlocks("no_miss_five", LogResult.LOSS, BattleDetails(missStreakMax = 0)))
    }

    @Test
    fun huntHalfBudgetNeedsAWinWithTheHuntFlag() {
        assertTrue(unlocks("hunt_half_budget", LogResult.WIN, BattleDetails(), facts.copy(huntHalfBudget = true)))
        assertFalse(unlocks("hunt_half_budget", LogResult.WIN, BattleDetails(), facts))
    }

    @Test
    fun relentlessWinNeedsTheWinAgainstTheRelentlessAdmiral() {
        assertTrue(unlocks("relentless_win", LogResult.WIN, BattleDetails(), facts.copy(relentless = true)))
        assertFalse(unlocks("relentless_win", LogResult.WIN, BattleDetails(), facts))
    }

    @Test
    fun aReplayedTorpedoSinkUnlocksTheAchievement() {
        // End to end: the scripted torpedo battle of BattleDetailsTest, through the same replay the screen uses.
        val start = WeaponFixtures.match(
            WeaponFixtures.rules(weapons = mapOf(Weapon.TORPEDO to WeaponConfig(initial = 1, max = 1))),
        )
        var m = start.act(Action.Fire(WeaponFixtures.cell(8, 0)))
            .act(Action.Fire(WeaponFixtures.cell(9, 0)))
        m = m.act(Action.UseWeapon(
            Weapon.TORPEDO,
            WeaponTarget.LineTarget(isRow = true, index = 8, fromStart = true),
        ))
        m = m.act(Action.Fire(WeaponFixtures.cell(9, 1)))
        val details = BattleDetails.of(start.state, m.actions)
        assertTrue(unlocks("torpedo_sink", LogResult.LOSS, details))
    }
}
