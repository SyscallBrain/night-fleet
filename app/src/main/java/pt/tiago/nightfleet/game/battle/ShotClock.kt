package pt.tiago.nightfleet.game.battle

/**
 * The shot clock of one turn. Pure: the caller decides when it
 * runs (not on the admiral's turn, not paused, not in the background) and passes the elapsed time.
 * The clock is never saved as running: a restored battle starts every turn with a full clock.
 */
data class ShotClock(val totalMs: Long?, val remainingMs: Long?) {

    val enabled: Boolean get() = totalMs != null

    /** True once the turn ran out. Set exactly once, by [advance]. */
    val expired: Boolean get() = remainingMs == 0L

    /** The last [DANGER_MS] of the turn: the ring turns red and every whole second ticks. */
    val danger: Boolean get() = remainingMs != null && remainingMs in 1..DANGER_MS

    /** Share of the turn left, 0..1. Zero when the clock is off. */
    val fraction: Float
        get() {
            val total = totalMs ?: return 0f
            val left = remainingMs ?: return 0f
            if (total <= 0L) return 0f
            return (left.toFloat() / total).coerceIn(0f, 1f)
        }

    /**
     * Result of [advance]: the new clock, [tickSecond] when a new second of the danger zone starts
     * (3, 2 or 1 seconds left), and [expiredNow] on the step that reaches zero.
     */
    data class Advance(val clock: ShotClock, val tickSecond: Boolean, val expiredNow: Boolean)

    /** Moves the clock by [deltaMs] when [running]. A stopped or disabled clock stays as it is. */
    fun advance(deltaMs: Long, running: Boolean): Advance {
        val left = remainingMs ?: return Advance(this, tickSecond = false, expiredNow = false)
        if (!running || deltaMs <= 0L || left <= 0L) return Advance(this, tickSecond = false, expiredNow = false)
        val next = maxOf(0L, left - deltaMs)
        val before = secondsLeft(left)
        val shown = secondsLeft(next)
        // Every number of the danger zone ticks once: when it changes into 3, 2 or 1, and for the first step of a
        // turn that already starts inside the zone (a clock of 1 or 2 seconds).
        val startsInside = left == totalMs && before <= TICK_SECONDS
        val tick = shown in 1..TICK_SECONDS && (shown < before || startsInside)
        return Advance(copy(remainingMs = next), tickSecond = tick, expiredNow = next == 0L && left > 0L)
    }

    companion object {
        /** Length of the danger zone at the end of every turn. */
        const val DANGER_MS = 3_000L

        /** The danger zone ticks for 3, 2 and 1 seconds. */
        const val TICK_SECONDS = 3

        /** A clock for a turn of [seconds], or a disabled clock for null. */
        fun restart(seconds: Int?): ShotClock =
            if (seconds == null) ShotClock(null, null) else ShotClock(seconds * 1_000L, seconds * 1_000L)

        /** Whole seconds shown on the clock: 3000 ms shows 3, 2999 ms shows 3, 1 ms shows 1. */
        private fun secondsLeft(ms: Long): Long = (ms + 999L) / 1_000L
    }
}
