package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.random.SplitMix64
import java.io.File

/**
 * CONVOY generation: the player's fleet with its freighters must fit on the terrain of every convoy
 * mission of the twelve seas, and the content validator must agree.
 */
class ConvoyAndDecoyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun everyConvoyTerrainOfAllTwelveSeasHoldsThePlayerFleetWithItsFreighters() {
        var variants = 0
        for (sea in 1..SEAS_COUNT) {
            for (output in buildSea(sea, DEFAULT_SEED)) {
                if (output.mission.type != MissionKind.CONVOY) continue
                val terrains = output.terrain?.let { mapOf(it.id to it.toTerrains()) }.orEmpty()
                for ((difficulty, variant) in output.mission.variants) {
                    val label = "${output.mission.id} ${difficulty.name}"
                    val rules = variant.toRuleSet(terrains)
                    assertTrue(label, rules.problems().isEmpty())
                    assertEquals(label, 3, rules.extraShips[0].size)
                    // Several seeds, not only the level seed: a fleet that fits by luck once is not a fitting terrain.
                    for (seed in listOf(variant.levelSeed, 1L, 2L, 3L)) {
                        val placed = RandomPlacer.place(rules.boardFor(0), rules.fleetFor(0), SplitMix64(seed), PlacementStyle.BALANCED, rules.shipsMayTouch)
                        assertTrue("$label seed $seed: $placed", placed is PlacementResult.Success)
                    }
                    variants++
                }
            }
        }
        assertTrue("convoy variants: $variants", variants >= 3 * 40)
    }

    @Test
    fun theValidatorAcceptsTheGeneratedConvoysOfAllTwelveSeas() {
        val root = tmp.newFolder()
        for (sea in 1..SEAS_COUNT) generateSea(root, sea, 1, MISSIONS_PER_SEA, DEFAULT_SEED)
        val errors = validateContent(root, strict = false).filter { it.severity == Severity.ERROR }
        assertTrue(errors.take(5).toString(), errors.isEmpty())
    }

    @Test
    fun aDecoyBattleWitnessReplaysWithTheAdmiralDecoyOnItsSea() {
        val root = tmp.newFolder()
        generateSea(root, 7, 1, MISSIONS_PER_SEA, DEFAULT_SEED)
        val id = (301..350).map { "s07m%03d".format(it) }.first { id ->
            val mission = readMission(root, id)
            mission.type != MissionKind.HUNT && mission.type != MissionKind.PUZZLE &&
                mission.variants.values.any { "DECOY" in it.weapons.keys }
        }
        val lines = mutableListOf<String>()
        val run = calibrateContent(root, CalibrateOptions(sea = null, mission = id, games = 16, threads = 1, resume = false), lines::add)
        assertEquals(lines.toString(), 0, run.failed)
        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.failed.toString(), report.failed.isEmpty())
        val witnesses = Difficulty.entries.mapNotNull { difficulty ->
            val file = File(root, "certificates/$id-${difficulty.name.first()}.json")
            (ContentJson.json.decodeFromString(CertificateDto.serializer(), file.readText()) as CertificateDto.Calibration).witness
        }
        // The admiral hides one decoy per battle: every witness of this mission carries it.
        assertTrue(witnesses.toString(), witnesses.all { it.decoys.size == 1 })
    }

    private fun readMission(root: File, id: String): MissionDto =
        ContentJson.json.decodeFromString(MissionDto.serializer(), File(root, "missions/s07/$id.json").readText())
}
