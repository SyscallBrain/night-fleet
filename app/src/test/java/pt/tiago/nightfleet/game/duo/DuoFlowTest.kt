package pt.tiago.nightfleet.game.duo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig

class DuoFlowTest {

    private val names = listOf("Ana", "Bruno")
    private val config = FreePlayConfig()

    private fun fleet(seed: Long): List<Placement> {
        val rules = config.let { pt.tiago.nightfleet.game.freeplay.FreePlayBuilder.baseRules(it, emptyList()) }
        return (RandomPlacer.place(rules.boardFor(0), rules.fleetFor(0), SplitMix64(seed)) as PlacementResult.Success).placements
    }

    /** A duel with both fleets placed, the first curtain up for the seat that starts (game 0: seat 0). */
    private fun placedGame(gameIndex: Int = 0): DuoGame {
        var game = DuoFlow.newGame(names, config, gameIndex, DuoScore(), emptyList(), attemptSeed = 99L)
        val first = fleet(3L)
        val second = fleet(4L)
        game = DuoFlow.reveal(game)
        game = DuoFlow.placed(game, 0, first)
        game = DuoFlow.reveal(game)
        game = DuoFlow.placed(game, 1, second)
        return game
    }

    private fun turnGame(gameIndex: Int = 0): DuoGame = DuoFlow.reveal(placedGame(gameIndex))

    @Test
    fun theFirstCurtainComesBeforeTheFirstSeatPlaces() {
        val game = DuoFlow.newGame(names, config, 0, DuoScore(), emptyList(), 1L)
        assertEquals(DuoPhase.Curtain(0, CurtainReason.PLACE), game.phase)
        assertTrue(DuoFlow.view(game, 0) is DuoView.Curtain)
    }

    @Test
    fun theCurtainHidesEverythingUntilTheSeatShowsItsSea() {
        val game = DuoFlow.newGame(names, config, 0, DuoScore(), emptyList(), 1L)
        // A curtain view carries names and the reason only: no observation of any sea.
        val view = DuoFlow.view(game, 1) as DuoView.Curtain
        assertEquals("Ana", view.name)
        assertEquals(CurtainReason.PLACE, view.reason)
        assertEquals(DuoPhase.Placing(0), DuoFlow.reveal(game).phase)
    }

    @Test
    fun theSecondSeatIsBehindItsOwnCurtainAfterTheFirstFleet() {
        var game = DuoFlow.reveal(DuoFlow.newGame(names, config, 0, DuoScore(), emptyList(), 1L))
        game = DuoFlow.placed(game, 0, fleet(3L))
        assertEquals(DuoPhase.Curtain(1, CurtainReason.PLACE), game.phase)
        assertNull(game.match)
    }

    @Test
    fun aFullDuelStartsWithTheCurtainOfTheStarter() {
        val game = placedGame()
        assertNotNull(game.match)
        assertEquals(DuoPhase.Curtain(0, CurtainReason.TURN), game.phase)
        assertEquals(0, game.match!!.state.current)
    }

    @Test
    fun theStarterAlternatesFromGameToGame() {
        assertEquals(0, placedGame(gameIndex = 0).match!!.state.current)
        assertEquals(1, placedGame(gameIndex = 1).match!!.state.current)
        assertEquals(1, DuoFlow.nextGame(placedGame(0), 5L, emptyList()).firstSeat)
    }

    @Test
    fun aShotLeavesTheResultOnScreenThenTheCurtainForTheOtherSeat() {
        var game = turnGame()
        assertEquals(DuoPhase.Turn(0), game.phase)
        game = DuoFlow.act(game, Action.Fire(Cell(0, 9)))
        assertEquals(DuoPhase.Result(0), game.phase)
        val shown = DuoFlow.view(game, 0) as DuoView.Turn
        assertTrue(shown.resultShowing)
        game = DuoFlow.afterResult(game)
        assertEquals(DuoPhase.Curtain(1, CurtainReason.TURN), game.phase)
    }

    @Test
    fun theSeatSeesItsOwnFleetAndNeverTheOtherFleet() {
        var game = turnGame()
        game = DuoFlow.act(game, Action.Fire(Cell(0, 9)))
        game = DuoFlow.afterResult(game)
        game = DuoFlow.reveal(game)
        // Seat 1 plays now: it sees its own fleet and nothing of seat 0's fleet.
        val view = DuoFlow.view(game, 1) as DuoView.Turn
        assertEquals(1, view.observation.viewer)
        assertEquals(game.placements[1]!!, view.observation.ownSea.ships.map { it.placement })
        val knownShipSquares = view.observation.enemySea.count { it == KnownCell.SUNK || it == KnownCell.HIT }
        assertEquals(0, knownShipSquares)
        assertTrue(view.observation.enemySea.all { it == KnownCell.UNKNOWN || it == KnownCell.WATER })
    }

    @Test
    fun aSeatOnlySeesItsOwnFleetWhileItIsOnItsTurn() {
        val game = turnGame()
        val view = DuoFlow.view(game, 0) as DuoView.Turn
        assertEquals(game.placements[0]!!, view.observation.ownSea.ships.map { it.placement })
        assertFalse(view.observation.ownSea.ships.map { it.placement }.containsAll(game.placements[1]!!))
    }

    @Test
    fun aShotOutOfTurnIsRefusedAndChangesNothing() {
        val game = DuoFlow.newGame(names, config, 0, DuoScore(), emptyList(), 1L)
        val refused = DuoFlow.act(game, Action.Fire(Cell(0, 0)))
        assertEquals(DuoFlow.REFUSAL_NOT_YOUR_TURN, refused.refusal)
        assertEquals(game.phase, refused.phase)
    }

    @Test
    fun anIllegalShotKeepsTheSeatOnItsTurn() {
        // Seat 0 fires at (0, 9), seat 1 fires at (0, 8), then seat 0 fires at (0, 9) again: refused.
        var game = turnGame()
        game = DuoFlow.act(game, Action.Fire(Cell(0, 9)))
        game = DuoFlow.reveal(DuoFlow.afterResult(game))
        game = DuoFlow.act(game, Action.Fire(Cell(0, 8)))
        game = DuoFlow.reveal(DuoFlow.afterResult(game))
        assertEquals(DuoPhase.Turn(0), game.phase)
        val refused = DuoFlow.act(game, Action.Fire(Cell(0, 9)))
        assertEquals("ALREADY_FIRED", refused.refusal)
        assertEquals(game.phase, refused.phase)
        assertEquals(game.match!!.state, refused.match!!.state)
    }

    @Test
    fun aSecondFleetThatCannotBeSetUpStaysOnItsPlacement() {
        var game = DuoFlow.reveal(DuoFlow.newGame(names, config, 0, DuoScore(), emptyList(), 1L))
        game = DuoFlow.placed(game, 0, fleet(3L))
        game = DuoFlow.reveal(game)
        // One ship short of the fleet: the setup is refused, so seat 1 places again.
        val broken = fleet(4L).dropLast(1)
        game = DuoFlow.placed(game, 1, broken)
        assertEquals(DuoPhase.Placing(1), game.phase)
        assertEquals(DuoFlow.REFUSAL_SETUP, game.refusal)
        assertNull(game.match)
    }

    @Test
    fun theSessionScoreCountsWinsAndDraws() {
        assertEquals(DuoScore(wins = listOf(1, 0), draws = 0), DuoScore().add(0))
        assertEquals(DuoScore(wins = listOf(0, 2), draws = 1), DuoScore(wins = listOf(0, 1), draws = 1).add(1))
        assertEquals(DuoScore(wins = listOf(0, 0), draws = 1), DuoScore().add(null))
    }

    @Test
    fun theScoreCarriesOverToTheNextGame() {
        val score = DuoScore(wins = listOf(2, 1), draws = 3)
        val next = DuoFlow.newGame(names, config, gameIndex = 4, session = score, terrain = emptyList(), attemptSeed = 2L)
        assertEquals(score, next.session)
        assertEquals(names, next.names)
        assertEquals(DuoPhase.Curtain(0, CurtainReason.PLACE), next.phase)
    }

    @Test
    fun aSavedDuelComesBackBehindTheCurtainOfTheSeatToAct() {
        var game = turnGame()
        game = DuoFlow.act(game, Action.Fire(Cell(0, 9)))
        game = DuoFlow.afterResult(game)
        val restored = DuoFlow.restored(
            names = names,
            config = config,
            gameIndex = 0,
            session = DuoScore(),
            terrain = emptyList(),
            attemptSeed = game.attemptSeed,
            setup = game.setup!!,
            match = game.match!!,
        )
        assertEquals(DuoPhase.Curtain(1, CurtainReason.TURN), restored.phase)
        assertEquals(game.placements, restored.placements)
    }

}
