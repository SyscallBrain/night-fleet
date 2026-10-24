package pt.tiago.nightfleet.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon

/** The explicit skill knobs of the admirals (follow-up misses, blunder, delay) and the richer HumanModel. */
class SkillAndHumanTest {

    private val neutral = Admirals.NEUTRAL

    private fun human(weaponPermille: Int, misplaced: Int = 0) = HumanModel(
        HumanSkill("test", parityDeviationPermille = 0, unnecessaryTargetPermille = 0,
            weaponDelayTurns = 0, weaponPermille = weaponPermille, misplacedWeaponPermille = misplaced),
    )

    private fun humanDecide(view: AiView, player: HumanModel, seed: Long): AiIntent =
        player.decide(view, neutral, AiTier.HARD, SplitMix64(seed), AiSkill(0))

    @Test
    fun `skill values are validated`() {
        for (bad in listOf({ AiSkill(blunderPermille = 1001) }, { AiSkill(0, followUpMissPermille = 901) }, { AiSkill(0, weaponDelayTurns = -1) })) {
            try {
                bad()
                throw AssertionError("expected a rejection")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `the derived skill is what the admirals used before`() {
        val p = Admirals.LEVIATHAN.copy(blunderBase = 400, weaponDelayTurns = 3)
        assertEquals(AiSkill(blunderPermille = 400, followUpMissPermille = 0, weaponDelayTurns = 3), AiSkill.derived(p, AiTier.EASY))
        assertEquals(AiSkill(blunderPermille = 100, followUpMissPermille = 0, weaponDelayTurns = 3), AiSkill.derived(p, AiTier.HARD))
    }

    @Test
    fun `the default skill decides exactly like the derived one`() {
        for (seed in 1L..20L) {
            val view = Scenarios.viewOfMover(Scenarios.midBattle(seed, shots = (seed * 2).toInt()))
            for (tier in AiTier.entries) {
                val profile = Admirals.ALL[seed.toInt() % 12]
                val byDefault = AdmiralBrain.decide(view, profile, tier, SplitMix64(seed))
                val explicit = AdmiralBrain.decide(view, profile, tier, SplitMix64(seed), AiSkill.derived(profile, tier))
                assertEquals("$tier seed $seed", byDefault, explicit)
            }
        }
    }

    @Test
    fun `a follow-up miss makes the admiral hunt past its open hit`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("D4" to KnownCell.HIT))
        val adjacent = setOf(Cell(2, 3), Cell(4, 3), Cell(3, 2), Cell(3, 4))
        val none = (1L..200L).count { seed ->
            fireCell(AdmiralBrain.decide(view, neutral, AiTier.HARD, SplitMix64(seed), AiSkill(0, followUpMissPermille = 0))) in adjacent
        }
        val missing = (1L..200L).count { seed ->
            fireCell(AdmiralBrain.decide(view, neutral, AiTier.HARD, SplitMix64(seed), AiSkill(0, followUpMissPermille = 900))) !in adjacent
        }
        assertEquals(200, none)
        assertTrue("only $missing hunted", missing > 100)
    }

    @Test
    fun `the skill delay holds back weapons even when the profile allows them`() {
        val view = SyntheticViews.blank(8, 8, turn = 2, charges = mapOf(Weapon.SONAR to 1))
        val p = neutral.copy(weaponsAllowed = setOf(Weapon.SONAR), weaponAggression = 1000)
        assertTrue(AdmiralBrain.decide(view, p, AiTier.HARD, SplitMix64(1L), AiSkill(0, weaponDelayTurns = 5)) is AiIntent.Fire)
        assertTrue(AdmiralBrain.decide(view, p, AiTier.HARD, SplitMix64(1L), AiSkill(0, weaponDelayTurns = 0)) is AiIntent.Sonar)
    }

    @Test
    fun `the skill blunder replaces the aim`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("A1" to KnownCell.HIT, "B1" to KnownCell.HIT))
        val cells = (1L..60L).map { seed ->
            fireCell(AdmiralBrain.decide(view, neutral, AiTier.HARD, SplitMix64(seed), AiSkill(1000)))
        }.toSet()
        assertTrue("only ${cells.size} squares", cells.size > 20)
    }

    private fun fireCell(intent: AiIntent): Cell {
        assertTrue("expected Fire, got $intent", intent is AiIntent.Fire)
        return (intent as AiIntent.Fire).cell
    }

    @Test
    fun `the human torpedoes the line with the most unknown squares`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.TORPEDO to 1))
        val intent = humanDecide(view, human(weaponPermille = 1000), 1L)
        assertTrue("was $intent", intent is AiIntent.Torpedo)
    }

    @Test
    fun `the human recons the unread line closest to half unknown`() {
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.RECON to 1))
        val intent = humanDecide(view, human(weaponPermille = 1000), 1L)
        assertTrue("was $intent", intent is AiIntent.Recon)
    }

    @Test
    fun `the human crosses an open hit when it can`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("D4" to KnownCell.HIT), charges = mapOf(Weapon.CROSS_SHELL to 1))
        val intent = humanDecide(view, human(weaponPermille = 1000), 1L)
        assertTrue("was $intent", intent is AiIntent.Cross)
    }

    @Test
    fun `the human repairs a ship one hit from sunk`() {
        val ship = SyntheticViews.ownShip(ShipClass.BATTLESHIP, row = 0, col = 0, hits = listOf(0, 1, 2))
        val view = SyntheticViews.blank(8, 8, charges = mapOf(Weapon.REPAIR to 1), ownShips = listOf(ship))
        assertEquals(AiIntent.Repair(Cell(0, 0)), humanDecide(view, human(weaponPermille = 1000), 1L))
    }

    @Test
    fun `a human with no weapon charged fires`() {
        val view = SyntheticViews.blank(8, 8)
        assertTrue(humanDecide(view, human(weaponPermille = 1000), 1L) is AiIntent.Fire)
    }

    @Test
    fun `a human salvo has distinct unknown squares`() {
        val view = SyntheticViews.blank(8, 8, kinds = mapOf("D4" to KnownCell.HIT))
        val salvo = HumanModel.MEDIUM_PLAYER.decideSalvo(view, neutral, AiTier.MEDIUM, SplitMix64(4L), 4, AiSkill(0))
        assertEquals(4, salvo.size)
        assertEquals(4, salvo.toSet().size)
        assertTrue(salvo.all { view.kind(it) == KnownCell.UNKNOWN })
    }

    @Test
    fun `a numeric skill plays a full battle`() {
        val player = HumanModel(HumanSkill("numeric", 150, 80, 2, 500, 200))
        val outcome = BattleRunner.hunt(RuleSet(), 17L, BattleRunner.Side(player, neutral, AiTier.HARD))
        assertTrue(outcome.shots[0] in 17..100)
    }
}
