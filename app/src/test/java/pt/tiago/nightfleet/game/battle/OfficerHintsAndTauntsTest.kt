package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.ai.AiView
import pt.tiago.nightfleet.core.ai.OfficerHint
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.game.freeplay.FleetChoice
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Officer's hint (count, highlight and star cap) and the admirals' taunts (throttle and both languages). */
class OfficerHintsAndTauntsTest {

    private fun quickBattleSummary(stars: Int) = QuickBattle.Summary(
        won = stars > 0,
        reason = pt.tiago.nightfleet.core.engine.EndReason.FLEET_SUNK,
        shots = 40,
        hits = 20,
        precision = 50,
        stars = stars,
        enemyShipsSunk = 5,
        enemyShipsTotal = 5,
        ownShipsSunk = 0,
        longestHitStreak = 3,
        sunkByClass = emptyMap(),
    )

    @Test
    fun hintsPerBattleFollowTheLevel() {
        assertEquals(2, OfficerHints.limitFor(OpponentLevel.EASY))
        assertEquals(2, OfficerHints.limitFor(OpponentLevel.MEDIUM))
        assertEquals(2, OfficerHints.limitFor(OpponentLevel.HARD))
        assertEquals(0, OfficerHints.limitFor(OpponentLevel.RELENTLESS))
    }

    @Test
    fun aUsedHintCapsTheStarsAtTwo() {
        val capped = OfficerHints.capStars(quickBattleSummary(3), hintsUsed = 1)
        assertEquals(2, capped.stars)
        assertTrue(capped.usedHint)
        val untouched = OfficerHints.capStars(quickBattleSummary(3), hintsUsed = 0)
        assertEquals(3, untouched.stars)
        assertFalse(untouched.usedHint)
        assertEquals(1, OfficerHints.capStars(quickBattleSummary(1), hintsUsed = 2).stars)
    }

    @Test
    fun theHintHighlightsThreeUnknownSquaresOfThePlayersSea() {
        val config = FreePlayConfig(fleet = FleetChoice.CLASSIC, level = OpponentLevel.HARD, weapons = setOf(Weapon.SONAR))
        val setup = setupFor(config)
        val state = (setup.create() as SetupResult.Created).state
        val view = AiView.from(Observation.of(state, 0))
        val cells = OfficerHint.topCells(view, OfficerHints.CELLS)
        assertEquals(3, cells.size)
        assertEquals(cells.size, cells.toSet().size)
        assertTrue(cells.all { view.kind(it) == pt.tiago.nightfleet.core.observation.KnownCell.UNKNOWN })
    }

    @Test
    fun theHighlightLastsOnlyWhileTheHintIsShown() {
        assertEquals(1_200L, OfficerHints.HIGHLIGHT_MILLIS)
    }

    @Test
    fun tauntsAreNeverMoreFrequentThanEveryFiveTurns() {
        for (id in Admirals.ALL.map { it.id }) {
            val shown = (0..400).filter { Taunts.due(id, it) != null }
            for (pair in shown.zipWithNext()) {
                assertTrue("taunts of $id at ${pair.first} and ${pair.second} are too close", pair.second - pair.first >= Taunts.EVERY_TURNS)
            }
            assertTrue("$id should taunt at least once in 400 turns", shown.isNotEmpty())
        }
    }

    @Test
    fun everyAdmiralHasThreeTauntsAndTheProfileKeys() {
        for (admiral in Admirals.ALL) {
            val keys = Taunts.keysOf(admiral.id)
            assertEquals(Taunts.LINES, keys.size)
            for (key in admiral.tauntKeys) assertTrue("${admiral.id}: $key is one of the three lines", key in keys)
        }
    }

    @Test
    fun tauntsAreTheSameKeysInEnglishAndEuropeanPortuguese() {
        val english = stringNames("src/main/res/values/strings.xml")
        val portuguese = stringNames("src/main/res/values-pt/strings.xml")
        val keys = Admirals.ALL.flatMap { Taunts.keysOf(it.id) } + Admirals.ALL.map { "admiral_${it.id}_name" }
        val missingEnglish = keys.filter { it !in english }
        val missingPortuguese = keys.filter { it !in portuguese }
        assertTrue("Missing in values: $missingEnglish", missingEnglish.isEmpty())
        assertTrue("Missing in values-pt: $missingPortuguese", missingPortuguese.isEmpty())
    }

    @Test
    fun theHintStringsExistInBothLocales() {
        val english = stringNames("src/main/res/values/strings.xml")
        val portuguese = stringNames("src/main/res/values-pt/strings.xml")
        for (key in listOf("battle_hint_button", "battle_hint_left", "battle_hint_none", "battle_hint_cap", "battle_taunt_line")) {
            assertTrue("$key in values", key in english)
            assertTrue("$key in values-pt", key in portuguese)
        }
    }

    private fun setupFor(config: FreePlayConfig): MatchSetup {
        for (seed in 1L..200L) {
            val terrain = FreePlayBuilder.terrainFor(config, seed)
            val base = FreePlayBuilder.baseRules(config, terrain)
            val placed = pt.tiago.nightfleet.core.fleet.RandomPlacer.place(
                base.boardFor(0),
                base.fleetFor(0),
                pt.tiago.nightfleet.core.random.SplitMix64(seed),
                shipsMayTouch = base.shipsMayTouch,
            )
            val placements = (placed as? pt.tiago.nightfleet.core.fleet.PlacementResult.Success)?.placements ?: continue
            FreePlayBuilder.finish(config, terrain, seed, placements)?.let { return it }
        }
        error("no setup")
    }

    private fun stringNames(path: String): Set<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).mapNotNull { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name").takeIf { element.getAttribute("translatable") != "false" }
        }.toSet()
    }
}
