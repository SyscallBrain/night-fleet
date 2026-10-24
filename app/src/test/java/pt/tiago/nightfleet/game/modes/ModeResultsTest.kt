package pt.tiago.nightfleet.game.modes

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import java.io.File

class ModeResultsTest {

    @Test
    fun patrolRecordOnlyGoesUp() {
        val before = Profile(patrolBest = mapOf(Difficulty.MEDIUM to 3))
        val lost = ModeStake.Patrol(Difficulty.MEDIUM, runSeed = 1L, sea = 2, seasClearedBefore = 1, reserve = 20)
        assertEquals(3, ModeResults.afterBattle(before, lost, won = false).patrolBest[Difficulty.MEDIUM])
        val cleared = ModeStake.Patrol(Difficulty.MEDIUM, runSeed = 1L, sea = 4, seasClearedBefore = 3, reserve = 20)
        assertEquals(4, ModeResults.afterBattle(before, cleared, won = true).patrolBest[Difficulty.MEDIUM])
    }

    @Test
    fun patrolRecordIsKeptPerDifficulty() {
        val profile = ModeResults.afterBattle(
            Profile(),
            ModeStake.Patrol(Difficulty.HARD, runSeed = 1L, sea = 2, seasClearedBefore = 1, reserve = 20),
            won = true,
        )
        assertEquals(2, profile.patrolBest[Difficulty.HARD])
        assertNull(profile.patrolBest[Difficulty.EASY])
    }

    @Test
    fun seasClearedCountsTheSeaOnlyWhenWon() {
        val stake = ModeStake.Patrol(Difficulty.EASY, runSeed = 1L, sea = 6, seasClearedBefore = 5, reserve = 40)
        assertEquals(6, ModeResults.seasClearedAfter(stake, won = true))
        assertEquals(5, ModeResults.seasClearedAfter(stake, won = false))
    }

    @Test
    fun tournamentRecordCountsWonDuels() {
        val stake = ModeStake.Tournament(Difficulty.EASY, seed = 1L, round = 3, rounds = 8, roundsWonBefore = 2)
        assertEquals(3, ModeResults.roundsWonAfter(stake, won = true))
        val profile = ModeResults.afterBattle(Profile(), stake, won = true)
        assertEquals(3, profile.tournamentBest[Difficulty.EASY])
        val lost = ModeResults.afterBattle(profile, stake.copy(roundsWonBefore = 2), won = false)
        assertEquals(3, lost.tournamentBest[Difficulty.EASY])
    }

    @Test
    fun theTournamentIsWonOnlyByTheLastDuel() {
        val last = ModeStake.Tournament(Difficulty.HARD, seed = 1L, round = 8, rounds = 8, roundsWonBefore = 7)
        assertTrue(ModeResults.wonTournament(last, won = true))
        assertFalse(ModeResults.wonTournament(last, won = false))
        val middle = last.copy(round = 5, roundsWonBefore = 4)
        assertFalse(ModeResults.wonTournament(middle, won = true))
    }

    @Test
    fun stakesCarryTheIdsOfTheirBattles() {
        val patrol = ModeStake.Patrol(Difficulty.EASY, runSeed = 9L, sea = 3, seasClearedBefore = 2, reserve = 10)
        assertEquals(PatrolRun.matchId(9L, 3), patrol.matchId)
        val duel = ModeStake.Tournament(Difficulty.EASY, seed = 9L, round = 3, rounds = 8, roundsWonBefore = 2)
        assertEquals(TournamentRun.matchId(9L, 2), duel.matchId)
    }
}

class ModeRunStoreTest {
    @get:org.junit.Rule
    val folder = TemporaryFolder()

    private lateinit var store: ModeRunStore

    @Before
    fun setUp() {
        store = ModeRunStore(folder.newFolder("progress"))
    }

    @After
    fun tearDown() {
        folder.root.deleteRecursively()
    }

    @Test
    fun aPatrolRunIsSavedLoadedAndCleared() = runBlocking {
        val run = PatrolRun(runSeed = 5L, difficulty = Difficulty.HARD, sea = 2, reserve = 17, seasCleared = 1)
        store.savePatrol(run)
        assertEquals(run, store.patrol())
        store.clearPatrol()
        assertNull(store.patrol())
    }

    @Test
    fun aTournamentRunIsSavedAndLoaded() = runBlocking {
        val ladder = listOf("iris", "haze", "coral", "frost", "iron", "atoll", "wolf", "sargasso")
        val run = TournamentRun.start(Difficulty.MEDIUM, seed = 3L, chosen = ladder).afterDuel(won = true)
        store.saveTournament(run)
        assertEquals(run, store.tournament())
        assertEquals(1, store.tournament()?.wins)
    }

    @Test
    fun aDamagedRunFileIsTreatedAsNoRun() = runBlocking {
        val dir = folder.root.resolve("progress")
        File(dir, "run-tournament.json").writeText("{ not json")
        File(dir, "run-tournament.bak.json").writeText("still not json")
        assertNull(store.tournament())
    }
}
