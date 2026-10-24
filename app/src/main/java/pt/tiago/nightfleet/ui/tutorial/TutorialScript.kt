package pt.tiago.nightfleet.ui.tutorial

private val FIXED_ANCHOR_IDS: Set<String> = setOf(
    "enemy_board",
    "own_board",
    "ready_button",
    "auto_button",
    "stars",
    "hint_button",
    "shots_counter",
    "formations_button",
)

private val CELL_ANCHOR = Regex("cell:[A-L](1[0-2]|[1-9])")

/**
 * A stable id of a screen element that a tutorial step points at. The screens register the element's bounds
 * under this id (`Modifier.tutorialAnchor(id)`, or [TutorialAnchorRegistry.put] for a square of the board).
 * Only the ids listed here are valid, so a typo fails in the JVM tests, not on the phone.
 */
@JvmInline
value class TutorialAnchor(val id: String) {
    init {
        require(isAllowed(id)) { "Unknown tutorial anchor: $id" }
    }

    companion object {
        val ENEMY_BOARD = TutorialAnchor("enemy_board")
        val OWN_BOARD = TutorialAnchor("own_board")
        val READY_BUTTON = TutorialAnchor("ready_button")
        val AUTO_BUTTON = TutorialAnchor("auto_button")
        val STARS = TutorialAnchor("stars")
        val HINT_BUTTON = TutorialAnchor("hint_button")
        val SHOTS_COUNTER = TutorialAnchor("shots_counter")
        val FORMATIONS_BUTTON = TutorialAnchor("formations_button")

        /** One square of the enemy sea: column A to L, row 1 to 12, for example `cell('D', 4)` = "cell:D4". */
        fun cell(column: Char, row: Int): TutorialAnchor = TutorialAnchor("cell:$column$row")

        /** True for the fixed ids and for `cell:<A-L><1-12>`. */
        fun isAllowed(id: String): Boolean = id in FIXED_ANCHOR_IDS || CELL_ANCHOR.matches(id)
    }
}

/** What the player has to do for the bubble to change: nothing special, a screen action, or time. */
enum class GestureKind {
    /** A hand taps the anchor. */
    TAP,

    /** A hand drags across the anchor. */
    DRAG,

    /** No hand; the text only. */
    NONE,
}

/** When a step ends. */
sealed interface AdvanceRule {
    /** A tap anywhere on the overlay (outside the spotlight of an action step) moves on. */
    data object OnTapAnywhere : AdvanceRule

    /** The screen emits [actionId] through `TutorialController.onAction`; taps on the spotlight reach the screen. */
    data class OnAction(val actionId: String) : AdvanceRule {
        init {
            require(actionId.isNotBlank()) { "actionId must not be blank" }
        }
    }

    /** The step moves on by itself after [millis]. */
    data class Auto(val millis: Long) : AdvanceRule {
        init {
            require(millis > 0L) { "millis must be positive" }
        }
    }
}

/**
 * One bubble of a coach-mark tutorial. [textKey] is the name of a string resource (en in values/, pt-PT in
 * values-pt/); the overlay resolves it, so the texts are never in Kotlin.
 */
data class TutorialStep(
    val id: String,
    val textKey: String,
    val anchor: TutorialAnchor,
    val gesture: GestureKind,
    val advance: AdvanceRule,
)

/** An ordered list of steps. [id] is stable: it is the key stored in the completed set. */
data class TutorialScript(val id: String, val steps: List<TutorialStep>) {
    init {
        require(id.isNotBlank()) { "script id must not be blank" }
        require(steps.isNotEmpty()) { "script $id has no steps" }
        require(steps.map { it.id }.distinct().size == steps.size) { "script $id has duplicate step ids" }
    }
}
