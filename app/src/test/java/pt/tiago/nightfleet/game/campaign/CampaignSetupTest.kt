package pt.tiago.nightfleet.game.campaign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.ConvoyDto
import pt.tiago.nightfleet.core.content.Difficulty as ContentDifficulty
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.core.content.PlacementDto
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.toSkill
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.battle.BattleFixtures
import pt.tiago.nightfleet.game.battle.MatchSetup

class CampaignSetupTest {

    private fun variant(
        kind: MissionKind,
        seed: Long,
        first: FirstPlayer = FirstPlayer.PLAYER,
        weapons: Map<String, pt.tiago.nightfleet.core.content.WeaponConfigDto> = emptyMap(),
        shotBudget: Int? = null,
        convoy: ConvoyDto? = null,
        puzzleId: String? = null,
        rules: RulesDto = RulesDto(),
    ) = VariantDto(
        levelSeed = seed,
        rules = rules,
        ai = AiConfigDto(tier = ContentDifficulty.MEDIUM, blunder = 200),
        weapons = weapons,
        layoutPool = if (puzzleId == null) "pool" else null,
        puzzleId = puzzleId,
        firstPlayer = first,
        stars = StarsDto(two = 58, three = 46),
        shotBudget = shotBudget,
        convoy = convoy,
    )

    private fun mission(kind: MissionKind, variant: VariantDto) = MissionDto(
        id = "s01m010",
        sea = 1,
        ordinal = 10,
        type = kind,
        family = "open-classic",
        tags = listOf(MissionTag.SONAR),
        tutorial = null,
        variants = mapOf(ContentDifficulty.MEDIUM to variant),
    )

    /** Four certified layouts, each a legal CLASSIC fleet. */
    private val pool = LayoutPoolDto(
        id = "pool",
        fleet = "CLASSIC",
        terrain = null,
        terrainHash = null,
        layouts = (1L..4L).map { seed -> BattleFixtures.randomPlacements(seed * 13).map { it.toDto() } },
    )

    private fun Placement.toDto() = PlacementDto(ship = shipClass, row = origin.row, col = origin.col, rotation = rotationIndex)

    private fun playerFleet() = BattleFixtures.randomPlacements(99)

    @Test
    fun duelPlanIsDeterministicForTheSameAttempt() {
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, seed = 5_000L))
        val a = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)
        val b = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)
        assertNotNull(a)
        assertEquals(a, b)
    }

    @Test
    fun eachAttemptGetsItsOwnSeed() {
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, seed = 5_000L))
        val first = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val second = CampaignSetup.plan(m, Difficulty.MEDIUM, 1, emptyMap(), pool)!!
        assertNotEquals(first.attemptSeed, second.attemptSeed)
    }

    @Test
    fun enemyLayoutComesFromThePool() {
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, seed = 5_000L))
        val plan = CampaignSetup.plan(m, Difficulty.MEDIUM, 3, emptyMap(), pool)!!
        assertTrue(plan.layoutIndex in pool.layouts.indices)
        assertEquals(pool.layouts[plan.layoutIndex].map { it.ship }, plan.enemy.map { it.shipClass })
    }

    @Test
    fun puzzlesAndMissingPoolsHaveNoBattle() {
        val puzzle = mission(MissionKind.PUZZLE, variant(MissionKind.PUZZLE, 1L, puzzleId = "p1"))
        assertNull(CampaignSetup.plan(puzzle, Difficulty.MEDIUM, 0, emptyMap(), pool))
        val duel = mission(MissionKind.DUEL, variant(MissionKind.DUEL, 1L))
        assertNull(CampaignSetup.plan(duel, Difficulty.MEDIUM, 0, emptyMap(), null))
    }

    @Test
    fun huntCarriesItsShotBudgetAndOtherTypesDoNot() {
        val hunt = mission(MissionKind.HUNT, variant(MissionKind.HUNT, 7L, shotBudget = 60))
        assertEquals(60, CampaignSetup.plan(hunt, Difficulty.MEDIUM, 0, emptyMap(), pool)!!.shotBudget)
        val duel = mission(MissionKind.DUEL, variant(MissionKind.DUEL, 7L, shotBudget = 60))
        assertNull(CampaignSetup.plan(duel, Difficulty.MEDIUM, 0, emptyMap(), pool)!!.shotBudget)
    }

    @Test
    fun huntSetupIsBuiltWithTheBudgetAndTheHuntType() {
        val hunt = mission(MissionKind.HUNT, variant(MissionKind.HUNT, 7L, shotBudget = 60))
        val plan = CampaignSetup.plan(hunt, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val setup = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        assertEquals(MissionType.HUNT, setup.mission)
        assertEquals(60, setup.shotBudget)
        assertTrue(setup.create() is SetupResult.Created)
    }

    @Test
    fun bossSetupUsesTheBossMissionType() {
        val boss = mission(MissionKind.BOSS, variant(MissionKind.BOSS, 11L))
        val plan = CampaignSetup.plan(boss, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val setup = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        assertEquals(MissionType.BOSS, setup.mission)
        assertEquals("iris", setup.opponentId)
    }

    @Test
    fun convoyPlanKeepsTheConvoyKind() {
        val freighters = RulesDto(extraShips = listOf(listOf(ShipClass.FREIGHTER, ShipClass.FREIGHTER), emptyList()))
        val convoy = mission(
            MissionKind.CONVOY,
            variant(MissionKind.CONVOY, 3L, rules = freighters, convoy = ConvoyDto(surviveRounds = 20, minFreighters = 2)),
        )
        val plan = CampaignSetup.plan(convoy, Difficulty.MEDIUM, 0, emptyMap(), pool)
        assertNotNull(plan)
        assertEquals(MissionKind.CONVOY, plan!!.mission)
        assertEquals(MissionType.CONVOY, CampaignSetup.run { plan.mission.toMissionType() })
    }

    @Test
    fun firstPlayerRuleIsResolved() {
        assertEquals(0, CampaignSetup.firstPlayer(FirstPlayer.PLAYER, 123L))
        assertEquals(1, CampaignSetup.firstPlayer(FirstPlayer.ADMIRAL, 123L))
        val alternate = CampaignSetup.firstPlayer(FirstPlayer.ALTERNATE, 123L)
        assertEquals(alternate, CampaignSetup.firstPlayer(FirstPlayer.ALTERNATE, 123L))
        assertTrue(alternate == 0 || alternate == 1)
    }

    @Test
    fun decoyWeaponPlacesOneDecoyPerSide() {
        val weapons = mapOf(
            Weapon.DECOY.name to pt.tiago.nightfleet.core.content.WeaponConfigDto(initial = 1, max = 1),
        )
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, 21L, weapons = weapons))
        val plan = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val setup = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        assertEquals(listOf(1, 1), setup.decoys.map { it.size })
    }

    @Test
    fun theBattleOfAVariantCarriesItsCalibratedAdmiralAi() {
        val ai = AiConfigDto(tier = ContentDifficulty.MEDIUM, blunder = 321, followUpMiss = 450, weaponDelay = 3)
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, 31L).copy(ai = ai))
        val plan = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val setup = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        assertEquals(ai, setup.admiralAi)
        // The admiral of the battle plays exactly the content skill, as the leveltool calibrated it.
        val brain = pt.tiago.nightfleet.game.battle.AdmiralOpponent.forLevel("iris", pt.tiago.nightfleet.game.freeplay.OpponentLevel.MEDIUM, plan.attemptSeed, setup.admiralAi)
        assertEquals(ai.toSkill(brain.profile, pt.tiago.nightfleet.core.ai.AiTier.MEDIUM), brain.skill)
    }

    @Test
    fun anEscalatedVariantBattleFieldsTheEscalatedAdmiralLevel() {
        val escalated = AiConfigDto(tier = ContentDifficulty.HARD, blunder = 200)
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, 41L).copy(ai = escalated))
        val plan = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val setup = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        assertEquals(pt.tiago.nightfleet.game.freeplay.OpponentLevel.HARD, setup.admiralLevel)
        assertEquals(escalated, setup.admiralAi)
    }

    @Test
    fun theAdmiralOfACampaignDecoyIsPlacedByItsStyle() {
        val weapons = mapOf(Weapon.DECOY.name to pt.tiago.nightfleet.core.content.WeaponConfigDto(initial = 1, max = 1))
        val m = mission(MissionKind.DUEL, variant(MissionKind.DUEL, 22L, weapons = weapons))
        val plan = CampaignSetup.plan(m, Difficulty.MEDIUM, 0, emptyMap(), pool)!!
        val first = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        val again = CampaignSetup.finish(plan, playerFleet(), "iris")!!
        // The admiral's decoy comes from the attempt seed and the style: the same attempt gives the same square.
        assertEquals(first.decoys[1], again.decoys[1])
        assertEquals(1, first.decoys[1].size)
    }

    @Test
    fun levelAndTierMapping() {
        assertEquals(pt.tiago.nightfleet.game.freeplay.OpponentLevel.HARD, CampaignSetup.levelOf(Difficulty.HARD))
        assertEquals(ContentDifficulty.EASY, CampaignSetup.difficultyOf(Difficulty.EASY))
    }
}
