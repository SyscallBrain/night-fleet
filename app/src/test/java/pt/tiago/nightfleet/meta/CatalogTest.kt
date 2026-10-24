package pt.tiago.nightfleet.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.data.Rank

class CatalogTest {

    @Test
    fun achievementCatalogListsThirtySixDistinctAchievementsWithOneSecret() {
        val ids = Achievements.all.map { it.id }
        assertEquals(36, Achievements.all.size)
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        assertEquals(listOf("turn_limit_draw"), Achievements.all.filter { it.secret }.map { it.id })
        assertTrue(Achievements.all.all { it.target >= 1 })
    }

    @Test
    fun everyAchievementHasTitleAndDescriptionResources() {
        // The resource names follow ach_<id> and ach_<id>_desc, so a missing string breaks this test.
        val strings = Class.forName("pt.tiago.nightfleet.R\$string")
        for (def in Achievements.all) {
            assertNotNull("ach_${def.id}", strings.getField("ach_${def.id}").get(null))
            assertNotNull("ach_${def.id}_desc", strings.getField("ach_${def.id}_desc").get(null))
        }
    }

    @Test
    fun admiralsAreTwelveInSeaOrder() {
        assertEquals((1..12).toList(), Admirals.all.map { it.sea })
        assertEquals(12, Admirals.all.map { it.id }.toSet().size)
        assertEquals("coral", Admirals.byId("coral")?.id)
        assertEquals(null, Admirals.byId("nobody"))
    }

    @Test
    fun seasHaveFiftyMissionsEachAndBossesAtTheEnd() {
        assertEquals("s03m150", Seas.bossMissionId(3))
        assertEquals("s12m600", Seas.bossMissionId(12))
        assertEquals("s01m050", Seas.bossMissionId(1))
    }

    @Test
    fun missionIdsParseOnlyInsideTheCampaign() {
        assertEquals(MissionRef(3, 112), Seas.parseMissionId("s03m112"))
        assertEquals(null, Seas.parseMissionId("s13m001"))
        assertEquals(null, Seas.parseMissionId("s03m601"))
        assertEquals(null, Seas.parseMissionId("farol"))
        assertEquals(null, Seas.parseMissionId(null))
    }

    @Test
    fun shipsGalleryCoversTheEightEngineClassesOnce() {
        assertEquals(ShipClass.entries.toSet(), Ships.all.map { it.shipClass }.toSet())
        assertEquals(8, Ships.all.size)
    }

    @Test
    fun manualHasSeventeenMechanicCardsWithUniqueIds() {
        assertEquals(17, ManualCards.all.size)
        assertEquals(17, ManualCards.all.map { it.id }.toSet().size)
    }

    @Test
    fun everyRankHasAName() {
        for (rank in Rank.entries) {
            assertNotNull(rank.name, pt.tiago.nightfleet.ui.common.rankNameRes(rank))
        }
    }
}
