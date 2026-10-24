package pt.tiago.nightfleet.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.data.Difficulty

class RouteStoresTest {

    private class Probe : ViewModel() {
        var cleared = false
        override fun onCleared() {
            cleared = true
        }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
    }

    private fun RouteStores.probe(stack: NavStack, key: String): Probe =
        ViewModelProvider.create(storeFor(stack.entryKeys().last()), factory)[key, Probe::class.java]

    @Test
    fun anotherDifficultyOfTheSameMissionGetsAFreshBattle() {
        // Mission 2 on Medium, then back to the sea map and mission 2 on Hard: both use the key "campaign-battle-1".
        val stores = RouteStores()
        val sea = NavStack().push(Route.SeaMap(1, Difficulty.MEDIUM))
        val medium = sea.push(Route.Mission(2, Difficulty.MEDIUM))
        val finished = stores.probe(medium, "campaign-battle-1")

        stores.retainOnly(sea.entryKeys().toSet())
        assertTrue(finished.cleared)

        val hard = sea.push(Route.Mission(2, Difficulty.HARD))
        stores.retainOnly(hard.entryKeys().toSet())
        assertNotSame(finished, stores.probe(hard, "campaign-battle-1"))
    }

    @Test
    fun playingTheSameMissionAgainAfterLeavingStartsFresh() {
        val stores = RouteStores()
        val sea = NavStack().push(Route.SeaMap(1, Difficulty.HARD))
        val mission = sea.push(Route.Mission(2, Difficulty.HARD))
        val first = stores.probe(mission, "campaign-battle-1")
        stores.retainOnly(sea.entryKeys().toSet())
        stores.retainOnly(mission.entryKeys().toSet())
        assertNotSame(first, stores.probe(mission, "campaign-battle-1"))
    }

    @Test
    fun aScreenKeepsItsViewModelsWhileItStaysOnTheStack() {
        val stores = RouteStores()
        val mission = NavStack().push(Route.Mission(5, Difficulty.EASY))
        val battle = stores.probe(mission, "campaign-battle-1")
        val withSettings = mission.push(Route.Settings)
        stores.retainOnly(withSettings.entryKeys().toSet())
        stores.retainOnly(withSettings.pop().entryKeys().toSet())
        assertSame(battle, stores.probe(mission, "campaign-battle-1"))
        assertEquals(false, battle.cleared)
    }

    @Test
    fun clearAllClearsEveryStore() {
        val stores = RouteStores()
        val probe = stores.probe(NavStack().push(Route.Duel), "duo")
        stores.clearAll()
        assertTrue(probe.cleared)
        assertTrue(stores.keys.isEmpty())
    }
}
