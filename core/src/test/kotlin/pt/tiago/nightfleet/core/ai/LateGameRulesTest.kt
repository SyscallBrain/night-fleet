package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.M2
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/**
 * The late-game rules of the calibration sea: fog, diving submarines and repaired squares. The
 * engine lets a shot land on a contact or a repaired square again, and on a miss that fog has faded, so the
 * admirals and the simulated human must see those squares as they are.
 */
class LateGameRulesTest {

    private val neutral = Admirals.NEUTRAL

    private fun idx(view: AiView, label: String): Int = view.index(Cell.parse(label)!!)

    @Test
    fun `contact and repaired squares can be fired at, spent squares cannot`() {
        val view = SyntheticViews.blank(
            4, 4,
            kinds = mapOf(
                "A1" to KnownCell.CONTACT,
                "B2" to KnownCell.REPAIRED,
                "C3" to KnownCell.HIT,
                "D4" to KnownCell.WATER,
                "A2" to KnownCell.SUNK,
            ),
        )
        assertTrue(view.firable(idx(view, "A1")))
        assertTrue(view.firable(idx(view, "B2")))
        assertFalse(view.firable(idx(view, "C3")))
        assertFalse(view.firable(idx(view, "D4")))
        assertFalse(view.firable(idx(view, "A2")))
        assertEquals(11 + 2, view.firableCells().size)
    }

    @Test
    fun `the density gives a contact square the weight of a submarine that must cover it`() {
        val rules = SyntheticViews.rules(6, 6, listOf(ShipClass.SUBMARINE), diving = true)
        val view = SyntheticViews.blank(6, 6, classes = listOf(ShipClass.SUBMARINE), kinds = mapOf("C3" to KnownCell.CONTACT), rules = rules)
        val score = Density.forKnowledge(Knowledge(view)).score
        val contact = idx(view, "C3")
        assertTrue(score[contact] > 0L)
        assertTrue(score[contact] > score[idx(view, "A1")])
    }

    @Test
    fun `the human fires at a contact square when no unknown square is left`() {
        val kinds = (0 until 16).associate { i -> "${'A' + i % 4}${i / 4 + 1}" to KnownCell.WATER } + ("B2" to KnownCell.CONTACT)
        val view = SyntheticViews.blank(4, 4, kinds = kinds)
        val player = HumanModel(HumanSkill("test", parityDeviationPermille = 0, unnecessaryTargetPermille = 0,
            weaponDelayTurns = 0, weaponPermille = 0, misplacedWeaponPermille = 0))
        val intent = player.decide(view, neutral, AiTier.HARD, SplitMix64(7), AiSkill(0))
        assertEquals(AiIntent.Fire(Cell.parse("B2")!!), intent)
    }

    @Test
    fun `a miss faded by fog stays water for the admirals, and is never fired at again`() {
        val fog = FogRule(missFadeTurns = 2)
        var state: BattleState = M2.start(M2.rules(fog = fog), listOf(M2.dest("G8")), listOf(M2.dest("A4")))
        state = M2.fire(state, "C3").state
        val square = M2.idx("C3")
        // Pass until the viewer's miss has faded from the observation.
        var guard = 0
        while (Observation.of(state, 0).enemySea[square] == KnownCell.WATER && guard++ < 10) state = M2.pass(state).state

        val observation = Observation.of(state, 0)
        assertEquals(KnownCell.UNKNOWN, observation.enemySea[square])
        assertTrue(observation.enemyWaterTurns.containsKey(square))
        val view = AiView.from(observation)
        assertEquals(KnownCell.WATER, view.kind(square))
        // The engine still accepts a shot there (and a salvo counts it), but the admirals do not pick it.
        assertTrue(view.firable(square))
        assertTrue(view.firableCells().contains(square))
        assertFalse(view.fresh(square))
        assertFalse(view.freshCells().contains(square))
    }

    @Test
    fun `the human wins a majority against the weakest admiral under fog, diving and repair`() {
        val rules = RuleSet(
            width = 10,
            height = 10,
            fleet = Fleet.CLASSIC,
            fog = FogRule(missFadeTurns = 4),
            diving = DivingRule(),
            weapons = mapOf(Weapon.REPAIR to WeaponConfig(initial = 1, max = 1)),
        )
        // The weakest admiral: it blunders every shot and never uses a weapon.
        val weakest = object : AdmiralAI {
            override fun decide(view: AiView, profile: AdmiralProfile, tier: AiTier, rng: SplitMix64, skill: AiSkill): AiIntent =
                AdmiralBrain.decide(view, profile, tier, rng, AiSkill(blunderPermille = 1000, admiralWeapons = emptySet()))
        }
        val profile = Admirals.ALL[0]
        val outcomes = (1L..20L).map { seed ->
            BattleRunner.duel(
                rules, seed,
                listOf(
                    BattleRunner.Side(HumanModel.HARD_PLAYER, profile, AiTier.HARD),
                    BattleRunner.Side(weakest, profile, AiTier.HARD),
                ),
            )
        }
        assertTrue("draws at the turn limit: ${outcomes.count { it.reason == EndReason.TURN_LIMIT }}",
            outcomes.none { it.reason == EndReason.TURN_LIMIT })
        val wins = outcomes.count { it.winner == 0 }
        assertTrue("the human won $wins of 20", wins > 10)
    }
}
