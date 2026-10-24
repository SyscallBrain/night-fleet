package pt.tiago.nightfleet.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroCardTrackerTest {

    @Test
    fun pendingCardsFollowTheMissionTagsInEnumOrder() {
        val tracker = IntroCardTracker()
        val pending = tracker.pendingFor(listOf(MechanicIntro.TORPEDO, MechanicIntro.MINES, MechanicIntro.SONAR))
        assertEquals(listOf(MechanicIntro.SONAR, MechanicIntro.MINES, MechanicIntro.TORPEDO), pending)
    }

    @Test
    fun seenCardsAreNotShownAgain() {
        val tracker = IntroCardTracker(seen = setOf(MechanicIntro.SONAR))
        assertEquals(listOf(MechanicIntro.MINES), tracker.pendingFor(listOf(MechanicIntro.SONAR, MechanicIntro.MINES)))
    }

    @Test
    fun aMissionWithOnlySeenMechanicsShowsNothing() {
        val tracker = IntroCardTracker(seen = MechanicIntro.entries.toSet())
        assertTrue(tracker.pendingFor(MechanicIntro.entries).isEmpty())
    }

    @Test
    fun repeatedTagsShowTheCardOnce() {
        val tracker = IntroCardTracker()
        val pending = tracker.pendingFor(listOf(MechanicIntro.FOG, MechanicIntro.FOG, MechanicIntro.FOG))
        assertEquals(listOf(MechanicIntro.FOG), pending)
    }

    @Test
    fun noTagsMeansNoCards() {
        assertTrue(IntroCardTracker().pendingFor(emptyList()).isEmpty())
    }

    @Test
    fun markingACardSeenPersistsTheWholeSetOnce() {
        val saved = mutableListOf<Set<MechanicIntro>>()
        val tracker = IntroCardTracker(onSeen = { saved += it })
        tracker.markSeen(MechanicIntro.CONVOY)
        tracker.markSeen(MechanicIntro.CONVOY)
        tracker.markSeen(MechanicIntro.DECOY)
        assertEquals(listOf(setOf(MechanicIntro.CONVOY), setOf(MechanicIntro.CONVOY, MechanicIntro.DECOY)), saved)
        assertTrue(tracker.pendingFor(listOf(MechanicIntro.CONVOY, MechanicIntro.DECOY)).isEmpty())
    }
}
