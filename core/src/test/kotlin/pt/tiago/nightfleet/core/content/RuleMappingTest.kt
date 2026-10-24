package pt.tiago.nightfleet.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.rules.ConvoyRule
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

class RuleMappingTest {

    private val islands = TerrainDto(
        id = "islands-8",
        rows = listOf("........", "........", "...#....", "........", "........", "........", "........", "....~..."),
    )

    @Test
    fun defaultRulesMapToDefaultRuleSet() {
        assertEquals(RuleSet(), RulesDto().toRuleSet())
    }

    @Test
    fun everyRuleSetFieldIsCarriedOver() {
        val rules = RulesDto(
            width = 8,
            height = 8,
            fleet = "TUTORIAL",
            terrain = "islands-8",
            salvo = true,
            diving = DivingDto(surfaceRounds = 3, submergedRounds = 1),
            fog = FogDto(missFadeTurns = 5),
            movingShips = true,
            mines = listOf(listOf("D1"), listOf("H8", "A2")),
            shotClockSeconds = 6,
            shipsMayTouch = true,
            maxTurns = 200,
        ).toRuleSet(mapOf("islands-8" to islands.toTerrains()))

        assertEquals(Fleet.TUTORIAL, rules.fleet)
        assertEquals(islands.toTerrains(), rules.terrain)
        assertTrue(rules.salvo)
        assertEquals(DivingRule(surfaceRounds = 3, submergedRounds = 1), rules.diving)
        assertEquals(FogRule(missFadeTurns = 5), rules.fog)
        assertTrue(rules.movingShips)
        assertEquals(6, rules.shotClockSeconds)
        assertTrue(rules.shipsMayTouch)
        assertEquals(200, rules.maxTurns)
        // Mine labels become row-major indices, sorted per side: D1 = 3, A2 = 8, H8 = 63.
        assertEquals(listOf(listOf(3), listOf(8, 63)), rules.mines)
        assertEquals(emptyList<String>(), rules.problems())
    }

    @Test
    fun terrainSymbolsMapToTerrainKinds() {
        val cells = islands.toTerrains()
        assertEquals(Terrain.ISLAND, cells[2 * 8 + 3])
        assertEquals(Terrain.REEF, cells[7 * 8 + 4])
        assertEquals(Terrain.WATER, cells[0])
        assertEquals(64, cells.size)
    }

    @Test
    fun raggedOrUnknownTerrainIsRejected() {
        expectFailure("lengths") { TerrainDto("t", listOf("...", "..")).toTerrains() }
        expectFailure("unknown symbol") { TerrainDto("t", listOf("..x")).toTerrains() }
        expectFailure("no rows") { TerrainDto("t", emptyList()).toTerrains() }
    }

    @Test
    fun terrainOfTheWrongSizeIsRejected() {
        expectFailure("does not match") {
            RulesDto(width = 10, height = 10, terrain = "islands-8").toRuleSet(mapOf("islands-8" to islands.toTerrains()))
        }
    }

    @Test
    fun unknownTerrainIdIsRejected() {
        expectFailure("unknown terrain") { RulesDto(width = 8, height = 8, terrain = "nope").toRuleSet() }
    }

    @Test
    fun unknownFleetIsRejected() {
        expectFailure("unknown fleet preset") { RulesDto(fleet = "ARMADA").toRuleSet() }
    }

    @Test
    fun malformedOrOutOfGridMinesAreRejected() {
        expectFailure("malformed mine label") { RulesDto(mines = listOf(listOf("??"), emptyList())).toRuleSet() }
        expectFailure("outside the grid") { RulesDto(width = 8, height = 8, mines = listOf(listOf("I1"), emptyList())).toRuleSet() }
    }

    @Test
    fun presetsAreFoundByName() {
        for (preset in Fleet.PRESETS) assertEquals(preset, fleetPreset(preset.name))
        expectFailure("unknown fleet") { fleetPreset("nope") }
    }

    @Test
    fun placementDtoBecomesAnEnginePlacement() {
        val placement = PlacementDto(ShipClass.SEAPLANE, row = 2, col = 4, rotation = 3).toPlacement()
        assertEquals(ShipClass.SEAPLANE, placement.shipClass)
        assertEquals(Cell(2, 4), placement.origin)
        assertEquals(3, placement.rotationIndex)
    }

    @Test
    fun noTerrainMeansOpenWater() {
        val rules = RulesDto(width = 8, height = 8).toRuleSet()
        assertNull(rules.terrain.firstOrNull { it != Terrain.WATER })
        assertEquals(emptyList<Terrain>(), rules.terrain)
    }

    @Test
    fun weaponsConvoyAndExtraShipsReachTheRuleSet() {
        val variant = VariantDto(
            levelSeed = 1L,
            rules = RulesDto(width = 10, height = 10, fleet = "CLASSIC", extraShips = listOf(listOf(ShipClass.FREIGHTER, ShipClass.FREIGHTER, ShipClass.FREIGHTER), emptyList())),
            ai = AiConfigDto(tier = Difficulty.HARD, blunder = 0),
            weapons = mapOf("SONAR" to WeaponConfigDto(initial = 1, max = 2, earn = listOf(EarnDto(EarnOn.SINK, 1)))),
            firstPlayer = FirstPlayer.PLAYER,
            stars = StarsDto(two = 40, three = 30),
            convoy = ConvoyDto(surviveRounds = 8, minFreighters = 2),
        )
        val rules = variant.toRuleSet()
        assertEquals(WeaponConfig(initial = 1, max = 2, earn = listOf(EarnRule(EarnOn.SINK, 1))), rules.weapons[Weapon.SONAR])
        assertEquals(ConvoyRule(surviveRounds = 8, minFreighters = 2), rules.convoy)
        assertEquals(3, rules.fleetFor(0).ships.count { it == ShipClass.FREIGHTER })
        assertEquals(emptyList<String>(), rules.problems())
        expectFailure("unknown weapon") {
            variant.copy(weapons = mapOf("LASER" to WeaponConfigDto(initial = 1, max = 1))).toRuleSet()
        }
    }

    private fun expectFailure(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected an error containing '$fragment'")
        } catch (e: IllegalArgumentException) {
            assertTrue("message '${e.message}' should contain '$fragment'", e.message.orEmpty().contains(fragment))
        }
    }
}
