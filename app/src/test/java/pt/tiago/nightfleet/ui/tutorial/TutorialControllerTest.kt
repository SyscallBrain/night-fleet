package pt.tiago.nightfleet.ui.tutorial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorialControllerTest {

    private fun tapStep(id: String) = TutorialStep(id, "key_$id", TutorialAnchor.ENEMY_BOARD, GestureKind.NONE, AdvanceRule.OnTapAnywhere)

    private fun actionStep(id: String, action: String) =
        TutorialStep(id, "key_$id", TutorialAnchor.ENEMY_BOARD, GestureKind.TAP, AdvanceRule.OnAction(action))

    private fun autoStep(id: String) =
        TutorialStep(id, "key_$id", TutorialAnchor.STARS, GestureKind.NONE, AdvanceRule.Auto(millis = 1200L))

    private val sample = TutorialScript(
        id = "sample",
        steps = listOf(tapStep("a"), actionStep("b", "fired"), autoStep("c")),
    )

    @Test
    fun startShowsTheFirstStep() {
        val controller = TutorialController()
        assertTrue(controller.start(sample))
        assertEquals(sample, controller.state.script)
        assertEquals(0, controller.state.stepIndex)
        assertEquals("a", controller.state.step?.id)
    }

    @Test
    fun tapMovesOnFromATapAnywhereStep() {
        val controller = TutorialController()
        controller.start(sample)
        controller.onTap()
        assertEquals(1, controller.state.stepIndex)
    }

    @Test
    fun tapDoesNotMoveOnFromAnActionStep() {
        val controller = TutorialController()
        controller.start(sample)
        controller.onTap()
        controller.onTap()
        assertEquals(1, controller.state.stepIndex)
    }

    @Test
    fun matchingActionMovesOnAndOtherActionsDoNot() {
        val controller = TutorialController()
        controller.start(sample)
        controller.onTap()
        controller.onAction("sunk")
        assertEquals(1, controller.state.stepIndex)
        controller.onAction("fired")
        assertEquals(2, controller.state.stepIndex)
    }

    @Test
    fun actionOnATapStepIsIgnored() {
        val controller = TutorialController()
        controller.start(sample)
        controller.onAction("fired")
        assertEquals(0, controller.state.stepIndex)
    }

    @Test
    fun timedStepMovesOnWhenItsTimerFires() {
        val controller = TutorialController()
        controller.start(sample)
        controller.onTap()
        controller.onAction("fired")
        assertEquals("c", controller.state.step?.id)
        controller.onTap()
        assertNull(controller.state.script)
    }

    @Test
    fun finishingTheLastStepCompletesTheScriptAndCallsBack() {
        val finished = mutableListOf<String>()
        val controller = TutorialController(onCompleted = { finished += it })
        controller.start(sample)
        controller.onTap()
        controller.onAction("fired")
        controller.onTap()
        assertNull(controller.state.script)
        assertTrue(controller.isCompleted("sample"))
        assertEquals(listOf("sample"), finished)
    }

    @Test
    fun skipCompletesTheScriptAndClearsTheState() {
        val finished = mutableListOf<String>()
        val controller = TutorialController(onCompleted = { finished += it })
        controller.start(sample)
        controller.skip()
        assertNull(controller.state.script)
        assertTrue(controller.isCompleted("sample"))
        assertEquals(listOf("sample"), finished)
    }

    @Test
    fun skipWithNothingRunningDoesNothing() {
        var calls = 0
        val controller = TutorialController(onCompleted = { calls++ })
        controller.skip()
        assertEquals(0, calls)
        assertTrue(controller.completedScriptIds.isEmpty())
    }

    @Test
    fun completedScriptDoesNotStartAgainUnlessReplayed() {
        val controller = TutorialController(completed = setOf("sample"))
        assertFalse(controller.start(sample))
        assertNull(controller.state.script)
        assertTrue(controller.start(sample, replay = true))
        assertEquals(0, controller.state.stepIndex)
    }

    @Test
    fun completedSetIsKeptAcrossScripts() {
        val other = TutorialScript("other", listOf(tapStep("x")))
        val controller = TutorialController()
        controller.start(other)
        controller.onTap()
        assertEquals(setOf("other"), controller.completedScriptIds)
        assertFalse(controller.start(other))
        assertTrue(controller.start(sample))
    }

    @Test
    fun missionOneRunsEndToEndWithItsActions() {
        val controller = TutorialController()
        controller.start(TutorialScripts.forMission(1)!!)
        controller.onTap()
        controller.onAction("fired")
        controller.onTap()
        assertTrue(controller.isCompleted("mission_1"))
    }

    @Test
    fun theTutorialStateIsStableWhenNothingIsRunning() {
        val controller = TutorialController()
        assertNull(controller.state.step)
        controller.onTap()
        controller.onAction("fired")
        assertEquals(TutorialState(), controller.state)
    }
}
