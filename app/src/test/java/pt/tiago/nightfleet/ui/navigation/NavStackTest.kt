package pt.tiago.nightfleet.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty

class NavStackTest {

    @Test
    fun startsAtHomeAndHomeIsNeverPopped() {
        val stack = NavStack()
        assertEquals(Route.Home, stack.current)
        assertFalse(stack.canGoBack)
        assertEquals(stack, stack.pop())
    }

    @Test
    fun pushAndPopFollowTheOrder() {
        val sea = Route.SeaMap(1, Difficulty.MEDIUM)
        val sheet = Route.MissionSheet(3, Difficulty.MEDIUM)
        val stack = NavStack().push(sea).push(sheet)
        assertEquals(sheet, stack.current)
        assertEquals(sea, stack.pop().current)
        assertEquals(Route.Home, stack.pop().pop().current)
    }

    @Test
    fun replaceTopDoesNotLeaveTheReplacedScreenOnTheStack() {
        val stack = NavStack().push(Route.MissionSheet(1, Difficulty.EASY)).replaceTop(Route.Mission(1, Difficulty.EASY))
        assertEquals(listOf(Route.Home, Route.Mission(1, Difficulty.EASY)), stack.entries)
    }

    @Test
    fun popToReturnsToTheNewestMatchingEntry() {
        val sea = Route.SeaMap(1, Difficulty.EASY)
        val stack = NavStack().push(sea).push(Route.Settings).push(sea).push(Route.About)
        assertEquals(listOf(Route.Home, sea, Route.Settings, sea), stack.popTo(sea).entries)
    }

    @Test
    fun popToPushesWhenThereIsNoMatch() {
        val stack = NavStack().popTo(Route.Logbook)
        assertEquals(listOf(Route.Home, Route.Logbook), stack.entries)
    }

    @Test
    fun resetToStartsAgainFromHome() {
        val stack = NavStack().push(Route.Daily).push(Route.Settings).resetTo(Route.Logbook)
        assertEquals(listOf(Route.Home, Route.Logbook), stack.entries)
        assertEquals(listOf(Route.Home), NavStack().push(Route.Daily).resetTo().entries)
    }

    @Test
    fun emptyStackIsRejected() {
        val rejected = runCatching { NavStack(emptyList()) }.isFailure
        assertTrue(rejected)
    }

    @Test
    fun stackSurvivesEncodingAndDecoding() {
        val stack = NavStack().push(Route.SeaMap(2, Difficulty.HARD)).push(Route.Mission(57, Difficulty.HARD))
        assertEquals(stack, NavStack.decode(stack.encode()))
    }

    @Test
    fun decodingDropsUnknownEntriesAndKeepsHomeAtTheBottom() {
        val decoded = NavStack.decode(listOf("nonsense", "sea/3/MEDIUM", "daily"))
        assertEquals(listOf(Route.Home, Route.SeaMap(3, Difficulty.MEDIUM), Route.Daily), decoded.entries)
        assertEquals(NavStack(), NavStack.decode(null))
        assertEquals(NavStack(), NavStack.decode(listOf("garbage")))
    }
}

class RouteCodecTest {

    @Test
    fun everyRouteRoundTrips() {
        val routes = listOf(
            Route.Home,
            Route.SeaMap(12, Difficulty.EASY),
            Route.MissionSheet(600, Difficulty.HARD),
            Route.Mission(42, Difficulty.MEDIUM),
            Route.Daily,
            Route.FreePlay(),
            Route.FreePlay("iris"),
            Route.Duel,
            Route.EndlessPuzzles,
            Route.Profile,
            Route.Gallery,
            Route.Achievements,
            Route.Statistics,
            Route.Logbook,
            Route.Analysis("abc123"),
            Route.Replay("abc123"),
            Route.Settings,
            Route.About,
            Route.BoardGallery,
            Route.Puzzle("new:8:HARD"),
        )
        routes.forEach { assertEquals(it, Route.decode(Route.encode(it))) }
    }

    @Test
    fun malformedTextIsNotARoute() {
        assertNull(Route.decode("sea/x/MEDIUM"))
        assertNull(Route.decode("sea/1/PURPLE"))
        assertNull(Route.decode("analysis/"))
        assertNull(Route.decode(""))
    }
}
