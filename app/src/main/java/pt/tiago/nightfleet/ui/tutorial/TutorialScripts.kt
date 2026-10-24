package pt.tiago.nightfleet.ui.tutorial

import pt.tiago.nightfleet.ui.tutorial.AdvanceRule.OnAction
import pt.tiago.nightfleet.ui.tutorial.AdvanceRule.OnTapAnywhere
import pt.tiago.nightfleet.ui.tutorial.GestureKind.DRAG
import pt.tiago.nightfleet.ui.tutorial.GestureKind.NONE
import pt.tiago.nightfleet.ui.tutorial.GestureKind.TAP

/** One step as written in a script; the step id and the text key follow from its position. */
private class StepSpec(
    val anchor: TutorialAnchor,
    val gesture: GestureKind = NONE,
    val advance: AdvanceRule = OnTapAnywhere,
)

private fun mission(number: Int, vararg specs: StepSpec): TutorialScript = TutorialScript(
    id = "mission_$number",
    steps = specs.mapIndexed { index, spec ->
        val position = index + 1
        TutorialStep(
            id = "s$position",
            textKey = "tutorial_m${number}_s$position",
            anchor = spec.anchor,
            gesture = spec.gesture,
            advance = spec.advance,
        )
    },
)

/**
 * The guided missions 1 to 10 of sea 1. Action ids are the ones the battle and
 * placement screens emit through `TutorialController.onAction`: fired, sunk, ship_placed, ship_rotated,
 * auto_pressed, ready_pressed, hint_pressed, formation_saved, result_shown.
 */
object TutorialScripts {

    val missions: List<TutorialScript> = listOf(
        // 1. Fire: water and hit.
        mission(
            1,
            StepSpec(TutorialAnchor.ENEMY_BOARD),
            StepSpec(TutorialAnchor.cell('D', 4), TAP, OnAction("fired")),
            StepSpec(TutorialAnchor.ENEMY_BOARD),
        ),
        // 2. Sinking and automatic water.
        mission(
            2,
            StepSpec(TutorialAnchor.ENEMY_BOARD),
            StepSpec(TutorialAnchor.ENEMY_BOARD, NONE, OnAction("sunk")),
            StepSpec(TutorialAnchor.ENEMY_BOARD),
        ),
        // 3. Placement: drag, rotate, Auto, Ready.
        mission(
            3,
            StepSpec(TutorialAnchor.OWN_BOARD, DRAG, OnAction("ship_placed")),
            StepSpec(TutorialAnchor.OWN_BOARD, TAP, OnAction("ship_rotated")),
            StepSpec(TutorialAnchor.AUTO_BUTTON, TAP, OnAction("auto_pressed")),
            StepSpec(TutorialAnchor.READY_BUTTON, TAP, OnAction("ready_pressed")),
        ),
        // 4. Checkerboard hunting, and why.
        mission(
            4,
            StepSpec(TutorialAnchor.ENEMY_BOARD),
            StepSpec(TutorialAnchor.ENEMY_BOARD),
            StepSpec(TutorialAnchor.ENEMY_BOARD),
        ),
        // 5. The admiral's turn and your own sea.
        mission(
            5,
            StepSpec(TutorialAnchor.OWN_BOARD),
            StepSpec(TutorialAnchor.OWN_BOARD),
            StepSpec(TutorialAnchor.OWN_BOARD),
        ),
        // 6. Stars and precision.
        mission(
            6,
            StepSpec(TutorialAnchor.STARS),
            StepSpec(TutorialAnchor.STARS),
            StepSpec(TutorialAnchor.STARS),
        ),
        // 7. The officer's hint, which caps the reward at two stars.
        mission(
            7,
            StepSpec(TutorialAnchor.HINT_BUTTON, TAP, OnAction("hint_pressed")),
            StepSpec(TutorialAnchor.HINT_BUTTON),
            StepSpec(TutorialAnchor.HINT_BUTTON),
        ),
        // 8. Target practice: the first hunt and its shot budget.
        mission(
            8,
            StepSpec(TutorialAnchor.SHOTS_COUNTER),
            StepSpec(TutorialAnchor.SHOTS_COUNTER),
            StepSpec(TutorialAnchor.SHOTS_COUNTER),
        ),
        // 9. Saved formations.
        mission(
            9,
            StepSpec(TutorialAnchor.FORMATIONS_BUTTON),
            StepSpec(TutorialAnchor.FORMATIONS_BUTTON, TAP, OnAction("formation_saved")),
            StepSpec(TutorialAnchor.FORMATIONS_BUTTON),
        ),
        // 10. Before the boss: the best of three.
        mission(
            10,
            StepSpec(TutorialAnchor.ENEMY_BOARD),
            StepSpec(TutorialAnchor.ENEMY_BOARD),
            StepSpec(TutorialAnchor.STARS, NONE, OnAction("result_shown")),
        ),
    )

    /** The script of mission [number] (1 to 10), or null outside the guided missions. */
    fun forMission(number: Int): TutorialScript? = missions.getOrNull(number - 1)
}
