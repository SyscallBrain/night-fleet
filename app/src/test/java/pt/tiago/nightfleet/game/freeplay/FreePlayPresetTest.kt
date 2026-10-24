package pt.tiago.nightfleet.game.freeplay

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Play against" from the gallery opens free play with that admiral. */
class FreePlayPresetTest {

    private val base = FreePlayState()

    @Test
    fun theChosenAdmiralBecomesTheOpponent() {
        val other = FreePlayAdmirals.IDS.first { it != base.config.admiralId }
        val next = FreePlayPreset.withAdmiral(base, other)
        assertEquals(other, next.config.admiralId)
        // Only the opponent changes: the rest of the last setup stays.
        assertEquals(base.config.copy(admiralId = other), next.config)
    }

    @Test
    fun noAdmiralKeepsTheLastSetup() {
        assertEquals(base, FreePlayPreset.withAdmiral(base, null))
    }

    @Test
    fun anUnknownAdmiralKeepsTheLastSetup() {
        assertEquals(base, FreePlayPreset.withAdmiral(base, "no-such-admiral"))
    }
}
