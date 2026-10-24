package pt.tiago.nightfleet.core.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.M2
import pt.tiago.nightfleet.core.engine.M2.cell
import pt.tiago.nightfleet.core.engine.M2.dest
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet

/**
 * The no-leak guarantee for every hidden element that M2 adds: enemy mines, decoys and
 * submerged submarines. Two battles that differ only in those hidden elements must give the same
 * observation to the other side, for as long as the viewer only shoots squares that are water in
 * both battles.
 */
class NonLeakVariantsTest {

    private val mix = Fleet("MIX", listOf(ShipClass.DESTROYER, ShipClass.SUBMARINE))

    private fun rules(withTerrain: Boolean = false) = RuleSet(
        width = 8,
        height = 8,
        fleet = mix,
        diving = DivingRule(surfaceRounds = 2, submergedRounds = 2),
        fog = FogRule(missFadeTurns = 3),
        salvo = true,
        terrain = if (withTerrain) M2.terrain(Terrain.ISLAND, "H8") else emptyList(),
        weapons = M2.SONAR_ONCE + M2.TORPEDO_ONCE + M2.RECON_ONCE,
        mines = listOf(emptyList(), emptyList()),
    )

    /**
     * Two battles with the same own sea. Battle A: enemy destroyer A1-B1, submarine A4-C4, mine C7,
     * decoy D2. Battle B: enemy destroyer E1-F1, submarine A6-C6, mine F5, decoy D3.
     */
    private fun pair(): Pair<BattleState, BattleState> {
        val own = listOf(dest("G8"), M2.sub("B8"))
        val a = M2.start(
            rules().copy(mines = listOf(emptyList(), listOf(M2.idx("C7")))),
            own,
            listOf(dest("A1"), M2.sub("A4")),
            decoys1 = listOf(cell("D2")),
        )
        val b = M2.start(
            rules().copy(mines = listOf(emptyList(), listOf(M2.idx("F5")))),
            own,
            listOf(dest("E1"), M2.sub("A6")),
            decoys1 = listOf(cell("D3")),
        )
        return a to b
    }

    /** Squares that are water and touch no hidden element of either battle. */
    private fun safeSquares(a: BattleState, b: BattleState): List<Int> {
        val board = a.sides[1].board
        val hidden = HashSet<Int>()
        for (state in listOf(a, b)) {
            state.sides[1].ships.forEach { ship -> ship.placement.cells().forEach { hidden += board.index(it) } }
            state.sides[1].decoys.forEach { hidden += it.cell }
            hidden += state.rules.mines[1]
        }
        return (0 until board.size).filter { it !in hidden && board.terrainAt(it) == Terrain.WATER }
    }

    @Test
    fun `hidden mines, decoys and submerged submarines never change the observation of the other side`() {
        for (seed in 0 until 8L) {
            val rng = SplitMix64(seed + 17)
            val (a0, b0) = pair()
            var a = a0
            var b = b0
            val safe = safeSquares(a, b).shuffled(java.util.Random(seed))
            var k = 0
            repeat(24) {
                assertEquals(Observation.of(a, 0), Observation.of(b, 0))
                if (a.result != null) return@repeat
                val action: Action = if (rng.nextInt(4) == 0 || k >= safe.size) {
                    Action.Timeout
                } else {
                    Action.Fire(a.sides[1].board.cell(safe[k++]))
                }
                a = Engine.apply(a, action).state
                b = Engine.apply(b, action).state
            }
            assertEquals(Observation.of(a, 0), Observation.of(b, 0))
        }
    }

    @Test
    fun `the enemy's hidden fleets differ between the two battles`() {
        val (a, b) = pair()
        assertNotEquals(a.sides[1].ships, b.sides[1].ships)
        assertNotEquals(a.rules.mines, b.rules.mines)
    }

    @Test
    fun `a decoy is unknown to the shooter until it is hit`() {
        val (a, _) = pair()
        assertEquals(KnownCell.UNKNOWN, Observation.of(a, 0).enemySea[M2.idx("D2")])
    }

    @Test
    fun `a hidden mine is unknown to the shooter and redacted in the rules`() {
        val (a, _) = pair()
        val obs = Observation.of(a, 0)
        assertEquals(KnownCell.UNKNOWN, obs.enemySea[M2.idx("C7")])
        assertTrue(obs.publicState.rules.mines[1].isEmpty())
    }

    @Test
    fun `a submerged submarine shows no coordinates to the shooter`() {
        val (a, b) = pair()
        val submerged = M2.passRounds(a, 2)
        val submergedB = M2.passRounds(b, 2)
        assertEquals(Observation.of(submerged, 0).enemySea, Observation.of(submergedB, 0).enemySea)
        assertEquals(KnownCell.UNKNOWN, Observation.of(submerged, 0).enemySea[M2.idx("A4")])
    }

    @Test
    fun `the remaining enemy classes do not depend on hidden placement`() {
        val (a, b) = pair()
        assertEquals(Observation.of(a, 0).enemyRemaining, Observation.of(b, 0).enemyRemaining)
    }
}
