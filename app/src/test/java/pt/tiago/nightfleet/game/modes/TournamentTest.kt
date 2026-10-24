package pt.tiago.nightfleet.game.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.Starter

class TournamentTest {

    private val allSeaOneTwelve = listOf("iris", "haze", "coral", "frost", "iron", "atoll", "wolf", "sargasso", "shade", "tempest", "leviathan", "nox")
    private val eightWeak = listOf("iris", "haze", "coral", "frost", "iron", "atoll", "wolf", "sargasso")

    private fun profileDefeating(vararg byDifficulty: Pair<Difficulty, Set<String>>): Profile =
        Profile(defeatedAdmirals = byDifficulty.toMap())

    @Test
    fun eligibleAdmiralsAreTheDefeatedOnesInAnyDifficultyWeakestFirst() {
        val profile = profileDefeating(
            Difficulty.EASY to setOf("nox", "iris"),
            Difficulty.HARD to setOf("coral", "not-an-admiral"),
        )
        assertEquals(listOf("iris", "coral", "nox"), TournamentRules.eligible(profile))
    }

    @Test
    fun aLadderNeedsEightAdmirals() {
        assertEquals(8, TournamentRules.LADDER_SIZE)
        assertEquals(3, TournamentRules.missing(5))
        assertEquals(0, TournamentRules.missing(9))
    }

    @Test
    fun theLadderOrdersChosenAdmiralsFromWeakestToStrongest() {
        assertEquals(
            listOf("iris", "wolf", "nox"),
            TournamentRules.ladderOf(listOf("nox", "iris", "iris", "wolf", "bogus")),
        )
    }

    @Test
    fun mechanicsAreIntroducedByTheSeaThatTeachesThem() {
        assertTrue(Mechanic.introducedBy(1).isEmpty())
        assertTrue(Mechanic.SONAR in Mechanic.introducedBy(4))
        assertFalse(Mechanic.MINES in Mechanic.introducedBy(4))
        assertEquals(Mechanic.entries.toList(), Mechanic.introducedBy(12))
    }

    @Test
    fun aLadderOfEarlyAdmiralsOnlyDrawsClassicDuels() {
        val ladder = eightWeak.take(1) + listOf("haze")
        assertEquals(listOf<Mechanic?>(null), TournamentRules.pool(listOf("iris", "haze")))
        assertTrue(TournamentRules.drawRules(3L, ladder).all { it == null })
    }

    @Test
    fun theDrawIsDeterministicAndOnlyUsesThePool() {
        val ladder = allSeaOneTwelve.take(8)
        val pool = TournamentRules.pool(ladder)
        val first = TournamentRules.drawRules(77L, ladder)
        assertEquals(first, TournamentRules.drawRules(77L, ladder))
        assertTrue(first.all { it in pool })
        assertEquals(8, first.size)
    }

    @Test
    fun theDrawChangesWithTheSeed() {
        val ladder = allSeaOneTwelve.take(8)
        assertNotEquals(TournamentRules.drawRules(1L, ladder), TournamentRules.drawRules(2L, ladder))
    }

    @Test
    fun aTournamentAdvancesOnWinsAndEndsOnTheFirstLoss() {
        val run = TournamentRun.start(Difficulty.MEDIUM, seed = 4L, chosen = eightWeak)
        val afterOneWin = run.afterDuel(won = true)
        assertEquals(1, afterOneWin.wins)
        assertEquals(1, afterOneWin.round)
        assertEquals(2, afterOneWin.currentRound)
        assertTrue(afterOneWin.afterDuel(won = false).ended)
    }

    @Test
    fun winningEveryDuelMakesTheChampion() {
        var run = TournamentRun.start(Difficulty.HARD, seed = 4L, chosen = eightWeak)
        repeat(TournamentRules.LADDER_SIZE) { run = run.afterDuel(won = true) }
        assertTrue(run.champion)
        assertNull(run.currentRound)
    }

    @Test
    fun aTournamentHasEightAdmiralsAndOneRulePerRound() {
        assertThrows(IllegalArgumentException::class.java) {
            TournamentRun(seed = 1L, difficulty = Difficulty.EASY, ladder = listOf("iris"), rules = listOf(""))
        }
    }

    @Test
    fun aDuelUsesTheAdmiralOfItsRoundAndHardStartsAtRandom() {
        val hard = TournamentRun.start(Difficulty.HARD, seed = 6L, chosen = eightWeak)
        val easy = TournamentRun.start(Difficulty.EASY, seed = 6L, chosen = eightWeak)
        assertEquals(eightWeak[2], TournamentRules.duelConfig(hard, 2).admiralId)
        assertEquals(Starter.RANDOM, TournamentRules.duelConfig(hard, 2).starter)
        assertEquals(Starter.PLAYER, TournamentRules.duelConfig(easy, 2).starter)
    }

    @Test
    fun aMechanicChangesOnlyItsOwnRule() {
        val base = FreePlayConfig()
        val mines = Mechanic.MINES.apply(base)
        assertEquals(Mechanic.MINES_PER_SIDE, mines.minesPerSide)
        assertFalse(mines.salvo)
        assertTrue(Weapon.SONAR in Mechanic.SONAR.apply(base).weapons)
        assertTrue(Mechanic.FOG.apply(base).fog)
    }

    @Test
    fun aDuelSetupBuildsFromItsRoundConfig() {
        val run = TournamentRun.start(Difficulty.MEDIUM, seed = 12L, chosen = eightWeak)
        val config = TournamentRules.duelConfig(run, 0)
        val seed = TournamentRules.duelSeed(run.seed, 0)
        val terrain = FreePlayBuilder.terrainFor(config, seed)
        val rules = FreePlayBuilder.baseRules(config, terrain)
        val placed = RandomPlacer.place(rules.boardFor(0), Fleet.CLASSIC, SeedStreams.rng(seed, SeedStreams.Stream.LAYOUT, 0))
        val placements = (placed as? PlacementResult.Success)?.placements
        assertNotNull(placements)
        val setup = FreePlayBuilder.finish(config, terrain, seed, placements!!)
        assertNotNull(setup)
        assertEquals(eightWeak[0], setup?.opponentId)
    }

    @Test
    fun theDuelIdsDifferPerRound() {
        assertNotEquals(TournamentRun.matchId(9L, 0), TournamentRun.matchId(9L, 1))
        assertTrue(TournamentRun.matchId(9L, 0).startsWith("tn-"))
    }
}
