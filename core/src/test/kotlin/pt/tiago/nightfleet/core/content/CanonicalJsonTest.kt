package pt.tiago.nightfleet.core.content

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import pt.tiago.nightfleet.core.puzzle.Grade
import pt.tiago.nightfleet.core.puzzle.PlayerCell
import pt.tiago.nightfleet.core.puzzle.PlayerGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.puzzle.Technique
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.weapons.EarnOn

class CanonicalJsonTest {

    private fun mission(ordinal: Int = 1) = MissionDto(
        id = "s01m%03d".format(ordinal),
        sea = 1,
        ordinal = ordinal,
        type = MissionKind.DUEL,
        family = "open-classic",
        tags = listOf(MissionTag.ISLANDS),
        tutorial = null,
        variants = mapOf(
            Difficulty.EASY to variant(Difficulty.EASY),
            Difficulty.MEDIUM to variant(Difficulty.MEDIUM),
            Difficulty.HARD to variant(Difficulty.HARD),
        ),
    )

    private fun variant(d: Difficulty) = VariantDto(
        levelSeed = 811092341L,
        rules = RulesDto(width = 10, height = 10, fleet = "CLASSIC", terrain = "islands-ring-a", mines = listOf(listOf("B2"), emptyList())),
        ai = AiConfigDto(tier = d, blunder = 350),
        weapons = mapOf("SONAR" to WeaponConfigDto(initial = 2, max = 2, earn = listOf(EarnDto(EarnOn.STREAK, 3)))),
        layoutPool = "s01m001-E",
        firstPlayer = FirstPlayer.PLAYER,
        stars = StarsDto(two = 58, three = 46),
    )

    @Test
    fun encodingTwiceGivesTheSameBytes() {
        val first = CanonicalJson.bytes(MissionDto.serializer(), mission())
        val second = CanonicalJson.bytes(MissionDto.serializer(), mission())
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun objectKeysAreSortedWhateverTheConstructionOrder() {
        val element = JsonObject(linkedMapOf("zeta" to JsonPrimitive(1), "alpha" to JsonPrimitive(2)))
        assertEquals("{\n  \"alpha\": 2,\n  \"zeta\": 1\n}\n", CanonicalJson.encode(element))
    }

    @Test
    fun nestedObjectsInsideArraysAreSortedToo() {
        val element = JsonArray(listOf(JsonObject(linkedMapOf("b" to JsonNull, "a" to JsonPrimitive("x")))))
        val text = CanonicalJson.encode(element)
        assertTrue(text.indexOf("\"a\"") < text.indexOf("\"b\""))
    }

    @Test
    fun outputUsesTwoSpacesLfTheTrailingNewlineAndNoBom() {
        val bytes = CanonicalJson.bytes(MissionDto.serializer(), mission())
        assertFalse(bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.endsWith("}\n"))
        assertFalse(text.contains("\r"))
        assertTrue(text.lines().drop(1).filter { it.startsWith(" ") }.all { it.takeWhile { c -> c == ' ' }.length % 2 == 0 })
    }

    @Test
    fun missionRoundTripsWithoutLoss() {
        val original = mission()
        val text = CanonicalJson.encode(MissionDto.serializer(), original)
        assertEquals(original, ContentJson.json.decodeFromString(MissionDto.serializer(), text))
    }

    @Test
    fun explicitNullsAreWritten() {
        val text = CanonicalJson.encode(MissionDto.serializer(), mission())
        assertTrue(text.contains("\"tutorial\": null"))
    }

    @Test
    fun unknownKeysAreRejected() {
        val text = CanonicalJson.encode(MissionDto.serializer(), mission()).replaceFirst("\"family\"", "\"bogus\": 1,\n  \"family\"")
        try {
            ContentJson.json.decodeFromString(MissionDto.serializer(), text)
            fail("unknown key accepted")
        } catch (e: kotlinx.serialization.SerializationException) {
            assertTrue(e.message.orEmpty().contains("bogus"))
        }
    }

    @Test
    fun certificateKindsRoundTripWithTheirDiscriminator() {
        val proof: CertificateDto = CertificateDto.Proof(
            id = "s03m112-E",
            puzzleId = "p-1",
            puzzleHash = "a".repeat(64),
            solution = PlayerGrid(2, 2, listOf(PlayerCell.SHIP, PlayerCell.WATER, PlayerCell.WATER, PlayerCell.WATER)),
            solutionCount = 1,
            difficulty = PuzzleGrade.EASY,
            grade = Grade(Technique.T2, 4),
            puzzleVersion = 1,
            engineVersion = 1,
            contentVersion = 1,
        )
        val calibration: CertificateDto = CertificateDto.Calibration(
            id = "s03m112-M",
            missionId = "s03m112",
            difficulty = Difficulty.MEDIUM,
            variantHash = "b".repeat(64),
            poolHash = "c".repeat(64),
            engineVersion = 1,
            aiVersion = 1,
            contentVersion = 1,
            sample = SampleDto(games = 64, wins = 58, shots = QuantilesDto(20, 25, 30, 35, 40)),
            thresholds = StarsDto(two = 40, three = 30),
            witness = WitnessDto("1f", 0, 3, listOf("F:A1"), "0123456789abcdef"),
        )
        for (certificate in listOf(proof, calibration)) {
            val text = CanonicalJson.encode(CertificateDto.serializer(), certificate)
            assertEquals(certificate, ContentJson.json.decodeFromString(CertificateDto.serializer(), text))
        }
        val proofText = CanonicalJson.encode(CertificateDto.serializer(), proof)
        assertEquals("PROOF", ContentJson.json.parseToJsonElement(proofText).jsonObject["kind"]?.let { (it as JsonPrimitive).content })
        val calibrationText = CanonicalJson.encode(CertificateDto.serializer(), calibration)
        assertEquals("CALIBRATION", ContentJson.json.parseToJsonElement(calibrationText).jsonObject["kind"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun canonicalHashIsStableAndSensitiveToEveryChange() {
        val a = canonicalHash(MissionDto.serializer(), mission())
        assertEquals(a, canonicalHash(MissionDto.serializer(), mission()))
        assertEquals(64, a.length)
        assertNotEquals(a, canonicalHash(MissionDto.serializer(), mission(ordinal = 2)))
    }

    @Test
    fun placementDtoKeepsTheShipNameInTheFile() {
        val text = CanonicalJson.encode(PlacementDto.serializer(), PlacementDto(ShipClass.CRUISER, 2, 3, 1))
        assertTrue(text.contains("\"ship\": \"CRUISER\""))
    }
}
