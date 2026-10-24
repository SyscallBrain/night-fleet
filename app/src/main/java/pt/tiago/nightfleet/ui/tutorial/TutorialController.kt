package pt.tiago.nightfleet.ui.tutorial

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the overlay shows: the running script and the index of its current step. [script] null = nothing. */
data class TutorialState(val script: TutorialScript? = null, val stepIndex: Int = 0) {
    val step: TutorialStep? get() = script?.steps?.getOrNull(stepIndex)
}

/**
 * The tutorial state machine. Pure Kotlin apart from the observable [state]: the JVM tests drive it directly.
 *
 * - [completed] holds the ids of the scripts already finished or skipped; [onCompleted] is called with the
 *   script id when a script ends, so the caller can persist it. A skipped script counts as completed.
 * - The screens call [onAction] with the action ids of [AdvanceRule.OnAction] steps, [onTap] for taps on the
 *   overlay (and for the timer of [AdvanceRule.Auto] steps), and [skip] when the player leaves the tutorial.
 * - Only the step that is showing can advance; an action that does not match the current step is ignored.
 */
class TutorialController(
    completed: Set<String> = emptySet(),
    private val onCompleted: (String) -> Unit = {},
) {
    private val done: MutableSet<String> = completed.toMutableSet()

    var state: TutorialState by mutableStateOf(TutorialState())
        private set

    /** Ids of the scripts finished or skipped so far. */
    val completedScriptIds: Set<String> get() = done.toSet()

    fun isCompleted(scriptId: String): Boolean = scriptId in done

    /**
     * Starts [script] at its first step. Returns false, and does nothing, when it is already completed unless
     * [replay] is true.
     */
    fun start(script: TutorialScript, replay: Boolean = false): Boolean {
        if (!replay && script.id in done) return false
        state = TutorialState(script, 0)
        return true
    }

    /** A tap on the overlay: moves on from a tap-anywhere or timed step. Taps never move an action step. */
    fun onTap() {
        when (state.step?.advance) {
            is AdvanceRule.OnTapAnywhere, is AdvanceRule.Auto -> advance()
            is AdvanceRule.OnAction, null -> Unit
        }
    }

    /** The screen reports that [actionId] happened. Moves on when the current step waits for exactly that action. */
    fun onAction(actionId: String) {
        val rule = state.step?.advance as? AdvanceRule.OnAction ?: return
        if (rule.actionId == actionId) advance()
    }

    /** The player leaves the tutorial. The script is recorded as completed. */
    fun skip() {
        val script = state.script ?: return
        finish(script.id)
    }

    private fun advance() {
        val current = state
        val script = current.script ?: return
        if (current.stepIndex + 1 < script.steps.size) {
            state = current.copy(stepIndex = current.stepIndex + 1)
        } else {
            finish(script.id)
        }
    }

    private fun finish(scriptId: String) {
        done += scriptId
        state = TutorialState()
        onCompleted(scriptId)
    }
}
