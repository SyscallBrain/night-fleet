package pt.tiago.nightfleet.ui.navigation

/**
 * The back stack as a pure value (no Compose, testable on the JVM). The bottom entry is always
 * [Route.Home]: popping the last screen never leaves the stack empty, and back from Home is left to
 * the system (the activity finishes).
 */
data class NavStack(val entries: List<Route> = listOf(Route.Home)) {
    init {
        require(entries.isNotEmpty()) { "The back stack is never empty" }
    }

    val current: Route get() = entries.last()

    /** True when back has somewhere to go inside the app. */
    val canGoBack: Boolean get() = entries.size > 1

    /** Opens [route] on top of the current screen. */
    fun push(route: Route): NavStack = NavStack(entries + route)

    /**
     * Replaces the current screen with [route] (for example the sheet becomes the battle after the
     * placement, so back does not return to the placement).
     */
    fun replaceTop(route: Route): NavStack = NavStack(entries.dropLast(1) + route)

    /** Goes back one screen. Home stays on the bottom and is never removed. */
    fun pop(): NavStack = if (canGoBack) NavStack(entries.dropLast(1)) else this

    /**
     * Goes back to the newest entry equal to [route], or pushes it when there is none. Used by the
     * "back to the sea map" links so the stack never grows with repeated screens.
     */
    fun popTo(route: Route): NavStack {
        val index = entries.indexOfLast { it == route }
        return if (index >= 0) NavStack(entries.take(index + 1)) else push(route)
    }

    /** Clears the stack and starts again from [route] (or from Home), as the top-level tabs do. */
    fun resetTo(route: Route = Route.Home): NavStack =
        if (route == Route.Home) NavStack() else NavStack(listOf(Route.Home, route))

    /**
     * One key per entry: its position and its route. An entry keeps its key while it stays on the stack, and a screen
     * that is replaced, popped or pushed again gets a new one (see [RouteStores]).
     */
    fun entryKeys(): List<String> = entries.mapIndexed { index, route -> "$index:${Route.encode(route)}" }

    /** Text form of the whole stack, for `rememberSaveable`. */
    fun encode(): List<String> = entries.map(Route::encode)

    companion object {
        /** Restores a saved stack. Unknown entries are dropped; an empty result starts from Home. */
        fun decode(saved: List<String>?): NavStack {
            val routes = saved.orEmpty().mapNotNull(Route::decode)
            if (routes.isEmpty()) return NavStack()
            return NavStack(if (routes.first() == Route.Home) routes else listOf(Route.Home) + routes)
        }
    }
}
