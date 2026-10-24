package pt.tiago.nightfleet.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.puzzle.PuzzleRequest
import java.time.LocalDate

/** The daily routes carry their date as text, so a restored stack opens the same challenge day. */
class DailyRouteTest {

    @Test
    fun theDailyHuntRouteRoundTripsItsDate() {
        val route = Route.DailyHunt(LocalDate.of(2026, 10, 22), Difficulty.HARD)
        assertEquals("dailyhunt/2026-10-22/HARD", Route.encode(route))
        assertEquals(route, Route.decode(Route.encode(route)))
    }

    @Test
    fun aDailyHuntWithABrokenDateIsNotRestored() {
        assertNull(Route.decode("dailyhunt/2026-13-40/HARD"))
        assertNull(Route.decode("dailyhunt/2026-10-22/IMPOSSIBLE"))
    }

    @Test
    fun theDailyAndCampaignPuzzleRequestsRoundTrip() {
        val daily = PuzzleRequest.Daily(LocalDate.of(2026, 10, 22), Difficulty.EASY)
        val campaign = PuzzleRequest.Campaign("s02m056", Difficulty.MEDIUM)
        assertEquals(daily, PuzzleRequest.decode(daily.encode()))
        assertEquals(campaign, PuzzleRequest.decode(campaign.encode()))
    }

    @Test
    fun aMalformedCampaignOrDailyRequestIsRefused() {
        assertNull(PuzzleRequest.decode("campaign:mission-1:EASY"))
        assertNull(PuzzleRequest.decode("daily:2026-2-2:EASY"))
        assertNull(PuzzleRequest.decode("daily:2026-10-22:NOPE"))
    }
}
