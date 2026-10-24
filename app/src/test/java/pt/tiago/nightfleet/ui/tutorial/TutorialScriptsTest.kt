package pt.tiago.nightfleet.ui.tutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TutorialScriptsTest {

    private val actionIds = setOf(
        "fired", "sunk", "ship_placed", "ship_rotated", "auto_pressed",
        "ready_pressed", "hint_pressed", "formation_saved", "result_shown",
    )

    private val steps: List<Pair<TutorialScript, TutorialStep>>
        get() = TutorialScripts.missions.flatMap { script -> script.steps.map { script to it } }

    @Test
    fun thereAreTenGuidedMissionsWithUniqueIds() {
        val scripts = TutorialScripts.missions
        assertEquals(10, scripts.size)
        assertEquals((1..10).map { "mission_$it" }, scripts.map { it.id })
    }

    @Test
    fun everyMissionHasThreeToSixSteps() {
        for (script in TutorialScripts.missions) {
            assertTrue("${script.id} has ${script.steps.size} steps", script.steps.size in 3..6)
        }
    }

    @Test
    fun stepIdsAreUniqueInsideEachScript() {
        for (script in TutorialScripts.missions) {
            assertEquals(script.id, script.steps.size, script.steps.map { it.id }.distinct().size)
        }
    }

    @Test
    fun everyAnchorIsOneOfTheAllowedIds() {
        for ((script, step) in steps) {
            assertTrue("${script.id}/${step.id} anchor ${step.anchor.id}", TutorialAnchor.isAllowed(step.anchor.id))
        }
    }

    @Test
    fun everyTextKeyNamesTheStepItBelongsTo() {
        val pattern = Regex("tutorial_m(\\d+)_s(\\d+)")
        for ((script, step) in steps) {
            val match = checkNotNull(pattern.matchEntire(step.textKey)) { "bad key ${step.textKey}" }
            val mission = script.id.removePrefix("mission_")
            assertEquals(mission, match.groupValues[1])
            assertEquals(step.id.removePrefix("s"), match.groupValues[2])
        }
    }

    @Test
    fun everyActionIdIsOneTheScreensEmit() {
        for ((script, step) in steps) {
            val rule = step.advance
            if (rule is AdvanceRule.OnAction) {
                assertTrue("${script.id}/${step.id} waits for ${rule.actionId}", rule.actionId in actionIds)
            }
        }
    }

    @Test
    fun everyActionIdOfTheContractIsUsedByAScript() {
        val used = steps.mapNotNull { (it.second.advance as? AdvanceRule.OnAction)?.actionId }.toSet()
        assertEquals(actionIds, used)
    }

    @Test
    fun timedStepsAreNotUsedByTheGuidedMissions() {
        assertFalse(steps.any { it.second.advance is AdvanceRule.Auto })
    }

    @Test
    fun missionOneFiresOnTheD4SquareAndWaitsForFired() {
        val firing = TutorialScripts.forMission(1)!!.steps[1]
        assertEquals("cell:D4", firing.anchor.id)
        assertEquals(GestureKind.TAP, firing.gesture)
        assertEquals(AdvanceRule.OnAction("fired"), firing.advance)
    }

    @Test
    fun missionThreeDragsAShipAndEndsWithReady() {
        val script = TutorialScripts.forMission(3)!!
        assertEquals(GestureKind.DRAG, script.steps.first().gesture)
        assertEquals(AdvanceRule.OnAction("ready_pressed"), script.steps.last().advance)
    }

    @Test
    fun missionTenEndsOnTheResultOfADuel() {
        val last = TutorialScripts.forMission(10)!!.steps.last()
        assertEquals(TutorialAnchor.STARS, last.anchor)
        assertEquals(AdvanceRule.OnAction("result_shown"), last.advance)
    }

    @Test
    fun missionsOutsideTheGuidedRangeHaveNoScript() {
        assertNull(TutorialScripts.forMission(0))
        assertNull(TutorialScripts.forMission(11))
        assertEquals("mission_7", TutorialScripts.forMission(7)?.id)
    }

    @Test
    fun scriptRejectsAnEmptyOrDuplicatedStepList() {
        val step = TutorialStep("s1", "k", TutorialAnchor.STARS, GestureKind.NONE, AdvanceRule.OnTapAnywhere)
        assertThrows { TutorialScript("empty", emptyList()) }
        assertThrows { TutorialScript("dup", listOf(step, step)) }
    }

    @Test
    fun anchorsAcceptOnlyTheFixedIdsAndBoardSquares() {
        assertTrue(TutorialAnchor.isAllowed("enemy_board"))
        assertTrue(TutorialAnchor.isAllowed("formations_button"))
        assertTrue(TutorialAnchor.isAllowed("cell:B7"))
        assertTrue(TutorialAnchor.isAllowed("cell:L12"))
        assertFalse(TutorialAnchor.isAllowed("cell:M1"))
        assertFalse(TutorialAnchor.isAllowed("cell:A0"))
        assertFalse(TutorialAnchor.isAllowed("cell:A13"))
        assertFalse(TutorialAnchor.isAllowed("enemy"))
        assertThrows { TutorialAnchor("bogus") }
    }

    @Test
    fun cellAnchorsAreBuiltFromColumnAndRow() {
        assertEquals("cell:D4", TutorialAnchor.cell('D', 4).id)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("Expected IllegalArgumentException")
    }
}
