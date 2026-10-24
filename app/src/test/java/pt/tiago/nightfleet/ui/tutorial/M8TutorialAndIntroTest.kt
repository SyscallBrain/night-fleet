package pt.tiago.nightfleet.ui.tutorial

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.ProfileCodec
import pt.tiago.nightfleet.ui.board.BoardLayout
import pt.tiago.nightfleet.ui.intro.IntroCardTracker
import pt.tiago.nightfleet.ui.intro.MechanicIntro
import pt.tiago.nightfleet.ui.intro.MissionIntros
import pt.tiago.nightfleet.ui.intro.introCardsSeen
import pt.tiago.nightfleet.ui.intro.withIntroCardsSeen

class M8TutorialAndIntroTest {

    private val knownActions = setOf(
        "fired", "sunk", "ship_placed", "ship_rotated", "auto_pressed", "ready_pressed",
        "hint_pressed", "formation_saved", "result_shown",
    )

    private val board8 = BoardLayout(cols = 8, rows = 8, cellPx = 40, gapPx = 2, labelPx = 16, marginPx = 0, density = 2f)

    // ---- tutorial scripts ---------------------------------------------------------------------

    @Test
    fun thereAreTenGuidedMissionsOfSeaOne() {
        assertEquals(10, TutorialScripts.missions.size)
        assertEquals("mission_1", TutorialScripts.forMission(1)?.id)
        assertEquals("mission_10", TutorialScripts.forMission(10)?.id)
        assertNull(TutorialScripts.forMission(11))
        assertNull(TutorialScripts.forMission(0))
    }

    @Test
    fun everyActionOfTheScriptsIsAnActionTheScreensEmit() {
        val used = TutorialScripts.missions.flatMap { script ->
            script.steps.mapNotNull { (it.advance as? AdvanceRule.OnAction)?.actionId }
        }
        assertTrue(used.isNotEmpty())
        assertTrue(knownActions.containsAll(used))
    }

    @Test
    fun theSquareNamedInMissionOneExistsOnItsEightByEightBoard() {
        val d4 = TutorialAnchor.cell('D', 4)
        assertEquals("cell:D4", d4.id)
        val step = TutorialScripts.forMission(1)?.steps?.first { it.anchor == d4 }
        assertEquals("fired", (step?.advance as? AdvanceRule.OnAction)?.actionId)
        assertEquals(true, TutorialBounds.parseCell(d4.id)?.let { it.first < board8.cols && it.second < board8.rows })
    }

    @Test
    fun cellAnchorsAreValidOnlyInsideTheTwelveByTwelveGrid() {
        assertTrue(TutorialAnchor.isAllowed("cell:L12"))
        assertTrue(TutorialAnchor.isAllowed("cell:A1"))
        assertFalse(TutorialAnchor.isAllowed("cell:M1"))
        assertFalse(TutorialAnchor.isAllowed("cell:A13"))
        assertFalse(TutorialAnchor.isAllowed("cell:A0"))
    }

    @Test
    fun theBoundsOfSquareD4AreComputedFromTheBoardLayout() {
        // Column D is index 3, row 4 is index 3. The cell starts at label + 3 * pitch = 16 + 3 * 42 = 142 px.
        val bounds = TutorialBounds.cellBounds("cell:D4", board8, Offset(100f, 200f))
        assertEquals(242f, bounds?.left)
        assertEquals(342f, bounds?.top)
        assertEquals(282f, bounds?.right)
        assertEquals(382f, bounds?.bottom)
    }

    @Test
    fun aSquareOutsideTheBoardHasNoBounds() {
        assertNull(TutorialBounds.cellBounds("cell:L12", board8, Offset.Zero))
        assertNull(TutorialBounds.cellBounds("enemy_board", board8, Offset.Zero))
    }

    @Test
    fun aFiredActionAdvancesTheFirstMissionAndAnOtherActionDoesNot() {
        val controller = TutorialController()
        controller.start(TutorialScripts.forMission(1)!!)
        controller.onTap()
        assertEquals(1, controller.state.stepIndex)
        controller.onAction("sunk")
        assertEquals(1, controller.state.stepIndex)
        controller.onAction("fired")
        assertEquals(2, controller.state.stepIndex)
    }

    @Test
    fun finishingAScriptRecordsItAsCompletedOnce() {
        val recorded = mutableListOf<String>()
        val controller = TutorialController(onCompleted = { recorded += it })
        val script = TutorialScripts.forMission(1)!!
        controller.start(script)
        controller.onTap()
        controller.onAction("fired")
        controller.onTap()
        assertEquals(listOf("mission_1"), recorded)
        assertTrue(controller.isCompleted("mission_1"))
        assertFalse(controller.start(script))
        assertTrue(controller.start(script, replay = true))
    }

    @Test
    fun aCompletedScriptFromTheProfileDoesNotStartAgain() {
        val controller = TutorialController(completed = setOf("mission_3"))
        assertFalse(controller.start(TutorialScripts.forMission(3)!!))
        assertNull(controller.state.script)
        assertTrue(controller.start(TutorialScripts.forMission(4)!!))
    }

    @Test
    fun skippingCountsAsCompleted() {
        val recorded = mutableListOf<String>()
        val controller = TutorialController(onCompleted = { recorded += it })
        controller.start(TutorialScripts.forMission(5)!!)
        controller.skip()
        assertEquals(listOf("mission_5"), recorded)
        assertNull(controller.state.script)
    }

    @Test
    fun theCompletedTutorialsSurviveAProfileSaveAndLoad() {
        val codec = ProfileCodec()
        val profile = Profile(completedTutorials = setOf("mission_1", "mission_2"))
        val loaded = codec.decode(codec.encode(profile))
        assertEquals(setOf("mission_1", "mission_2"), loaded?.completedTutorials)
        val controller = TutorialController(completed = loaded!!.completedTutorials)
        assertFalse(controller.start(TutorialScripts.forMission(2)!!))
    }

    @Test
    fun aProfileWithoutTheTutorialFieldLoadsWithNoneCompleted() {
        val old = """{"schemaVersion":1,"variants":{}}"""
        val loaded = ProfileCodec().decode(old)
        assertEquals(emptySet<String>(), loaded?.completedTutorials)
        assertEquals(emptySet<String>(), loaded?.seenIntroCards)
    }

    // ---- intro cards --------------------------------------------------------------------------

    @Test
    fun pendingCardsAreTheUnseenMechanicsOfTheMission() {
        val tracker = IntroCardTracker(seen = setOf(MechanicIntro.SONAR))
        val tags = MissionIntros.mechanicsOf(listOf(MissionTag.MINES, MissionTag.SONAR, MissionTag.MINES), MissionKind.DUEL)
        assertEquals(listOf(MechanicIntro.MINES), tracker.pendingFor(tags))
    }

    @Test
    fun aSeenCardIsStoredInTheProfileAndNotShownAgain() {
        val saved = mutableListOf<Set<MechanicIntro>>()
        val tracker = IntroCardTracker(onSeen = { saved += it })
        val tags = listOf(MechanicIntro.CONVOY, MechanicIntro.DECOY)
        assertEquals(listOf(MechanicIntro.DECOY, MechanicIntro.CONVOY).sortedBy { it.ordinal }, tracker.pendingFor(tags))
        tracker.markSeen(MechanicIntro.CONVOY)
        assertEquals(listOf(MechanicIntro.DECOY), tracker.pendingFor(tags))
        assertEquals(setOf(MechanicIntro.CONVOY), saved.last())
    }

    @Test
    fun theSeenCardsRoundTripThroughTheProfile() {
        val profile = Profile().withIntroCardsSeen(setOf(MechanicIntro.FOG, MechanicIntro.REPAIR))
        val codec = ProfileCodec()
        val loaded = codec.decode(codec.encode(profile))
        assertEquals(setOf(MechanicIntro.FOG, MechanicIntro.REPAIR), loaded?.introCardsSeen())
    }

    @Test
    fun aConvoyAndALighthousePuzzleBringTheirOwnCards() {
        assertEquals(listOf(MechanicIntro.CONVOY), MissionIntros.mechanicsOf(emptyList(), MissionKind.CONVOY))
        assertEquals(listOf(MechanicIntro.LIGHTHOUSE_PUZZLE), MissionIntros.mechanicsOf(emptyList(), MissionKind.PUZZLE))
        assertEquals(
            listOf(MechanicIntro.ISLANDS_REEFS),
            MissionIntros.mechanicsOf(listOf(MissionTag.ISLANDS, MissionTag.REEFS), MissionKind.DUEL),
        )
    }
}
