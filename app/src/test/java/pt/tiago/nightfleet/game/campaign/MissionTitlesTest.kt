package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag

class MissionTitlesTest {

    @Test
    fun theTypeAloneGivesATitleWithoutATag() {
        assertEquals(MissionTitle(MissionKind.DUEL, null), MissionTitles.of(MissionKind.DUEL, emptyList()))
    }

    @Test
    fun theTitleUsesTheMostTellingTag() {
        assertEquals(
            MissionTitle(MissionKind.HUNT, MissionTag.FOG),
            MissionTitles.of(MissionKind.HUNT, listOf(MissionTag.ISLANDS, MissionTag.FOG)),
        )
    }

    @Test
    fun theOrderOfTheTagsInTheMissionDoesNotMatter() {
        val a = MissionTitles.of(MissionKind.DUEL, listOf(MissionTag.REEFS, MissionTag.MINES))
        val b = MissionTitles.of(MissionKind.DUEL, listOf(MissionTag.MINES, MissionTag.REEFS))
        assertEquals(a, b)
        assertEquals(MissionTag.MINES, a.tag)
    }

    @Test
    fun everyTagHasAPriority() {
        MissionTag.entries.forEach { tag ->
            assertEquals("tag $tag is missing from PRIORITY", true, tag in MissionTitles.PRIORITY)
        }
        assertNull(MissionTitles.of(MissionKind.BOSS, emptyList()).tag)
    }
}
