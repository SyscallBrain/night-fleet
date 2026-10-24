package pt.tiago.nightfleet.game.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.PatrolDefaults
import pt.tiago.nightfleet.core.content.PatrolDefaults.stageFor
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.battle.SetupSave

class PatrolSeaTest {
    private val config = PatrolDefaults.config()

    @Test
    fun theDefaultPatrolHasTwentyStagesThatGrowFromEightToTwelve() {
        assertEquals((1..20).toList(), config.stages.map { it.ordinal })
        assertEquals(List(4) { 8 } + List(8) { 10 } + List(8) { 12 }, config.stages.map { it.width })
        assertTrue(config.stages.all { it.width == it.height })
        assertEquals(config.stages.map { it.mines }.sorted(), config.stages.map { it.mines })
    }

    @Test
    fun islandsAndMinesStartAtTheFifthSea() {
        assertNull(config.stages[3].terrain)
        assertEquals(0, config.stages[3].mines)
        assertEquals(PatrolDefaults.ISLANDS, config.stages[4].terrain)
        assertEquals(1, config.stages[4].mines)
    }

    @Test
    fun seasPastTheLastStageRepeatItWithOneMoreMine() {
        assertEquals(config.stages[19], config.stageFor(20))
        assertEquals(4, config.stageFor(21).mines)
        assertEquals(12, config.stageFor(21).width)
        assertEquals(8, config.stageFor(25).mines)
        assertThrows(IllegalArgumentException::class.java) { config.stageFor(0) }
    }

    @Test
    fun aPatrolSeaIsAHuntWhoseBudgetIsTheReserve() {
        val run = PatrolRun.start(Difficulty.MEDIUM, runSeed = 3L)
        val setup = PatrolSea.setup(run, config)
        assertNotNull(setup)
        assertEquals(MissionType.HUNT, setup?.mission)
        assertEquals(run.reserve, setup?.shotBudget)
        assertEquals(8, setup?.rules?.width)
        assertTrue(setup?.rules?.mines?.all { it.isEmpty() } == true)
        assertTrue(setup?.create() is SetupResult.Created)
    }

    @Test
    fun theFifthSeaHasIslandsAndMinesOnlyOnTheEnemySea() {
        val run = PatrolRun(runSeed = 11L, difficulty = Difficulty.MEDIUM, sea = 5, reserve = 50, seasCleared = 4)
        val setup = PatrolSea.setup(run, config)
        assertNotNull(setup)
        assertTrue(setup?.rules?.terrain?.isNotEmpty() == true)
        assertTrue(setup?.rules?.mines?.get(PatrolSea.PLAYER)?.isEmpty() == true)
        assertEquals(1, setup?.rules?.mines?.get(PatrolSea.ENEMY)?.size)
    }

    @Test
    fun theSameRunGivesTheSameSeas() {
        val run = PatrolRun(runSeed = 21L, difficulty = Difficulty.HARD, sea = 6, reserve = 30, seasCleared = 5)
        val first = PatrolSea.setup(run, config)
        val second = PatrolSea.setup(run, config)
        assertEquals(first, second)
        assertEquals(first?.placements, second?.placements)
    }

    @Test
    fun differentRunsGiveDifferentEnemyFleets() {
        val a = PatrolSea.setup(PatrolRun.start(Difficulty.EASY, runSeed = 1L), config)
        val b = PatrolSea.setup(PatrolRun.start(Difficulty.EASY, runSeed = 2L), config)
        assertNotEquals(a?.placements?.get(PatrolSea.ENEMY), b?.placements?.get(PatrolSea.ENEMY))
    }

    @Test
    fun theTwelveGridSeaHasFourMinesAfterTheTwentieth() {
        val run = PatrolRun(runSeed = 5L, difficulty = Difficulty.EASY, sea = 21, reserve = 30, seasCleared = 20)
        val setup = PatrolSea.setup(run, config)
        assertNotNull(setup)
        assertEquals(12, setup?.rules?.width)
        assertEquals(4, setup?.rules?.mines?.get(PatrolSea.ENEMY)?.size)
    }

    @Test
    fun aPatrolSetupSurvivesTheSaveFormat() {
        val run = PatrolRun(runSeed = 8L, difficulty = Difficulty.HARD, sea = 5, reserve = 33, seasCleared = 4)
        val setup = PatrolSea.setup(run, config)
        assertNotNull(setup)
        val decoded = SetupSave.decodeSetup(SetupSave.encodeSetup(setup!!))
        assertEquals(setup, decoded)
    }

    @Test
    fun theLevelOfAPatrolFollowsTheDifficulty() {
        assertEquals(
            listOf("EASY", "MEDIUM", "HARD"),
            Difficulty.entries.map { PatrolSea.levelOf(it).name },
        )
        assertFalse(PatrolSea.WEAPONS.isEmpty())
    }
}
