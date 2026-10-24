package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.CertificateDto.Calibration
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.QuantilesDto
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.random.SplitMix64
import java.io.File

/** Calibration rules as pure functions: no content, no engine. */
class CalibrationRulesTest {

    @Test
    fun nearestRankUsesTheCeilingRank() {
        val sorted = (1..10).toList()
        assertEquals(1, nearestRank(sorted, 10))
        assertEquals(4, nearestRank(sorted, 35))
        assertEquals(7, nearestRank(sorted, 70))
        assertEquals(9, nearestRank(sorted, 90))
        assertEquals(10, nearestRank(sorted, 100))
    }

    @Test
    fun quantilesNeverDecreaseForAnySample() {
        val rng = SplitMix64(7L)
        repeat(200) {
            val shots = List(1 + rng.nextInt(90)) { 1 + rng.nextInt(120) }
            val q = quantilesOf(shots)
            assertTrue(q.toString(), q.p10 <= q.p35 && q.p35 <= q.p50 && q.p50 <= q.p70 && q.p70 <= q.p90)
        }
    }

    @Test
    fun starsAreTheSeventyAndThirtyFiveQuantiles() {
        val q = QuantilesDto(p10 = 20, p35 = 25, p50 = 29, p70 = 34, p90 = 40)
        assertEquals(StarsDto(two = 34, three = 25), starsOf(q))
    }

    @Test
    fun bandsFollowContentTable() {
        assertEquals(Band(800, 920), bandOf(MissionKind.DUEL, Difficulty.EASY))
        assertEquals(Band(600, 750), bandOf(MissionKind.CONVOY, Difficulty.MEDIUM))
        assertEquals(Band(450, 600), bandOf(MissionKind.DUEL, Difficulty.HARD))
        assertEquals(Band(550, 700), bandOf(MissionKind.HUNT, Difficulty.HARD))
        assertEquals(Band(720, 880), bandOf(MissionKind.BOSS, Difficulty.EASY))
        assertEquals(Band(350, 500), bandOf(MissionKind.BOSS, Difficulty.HARD))
        assertTrue(runCatching { bandOf(MissionKind.PUZZLE, Difficulty.EASY) }.isFailure)
    }

    @Test
    fun bandDistanceIsZeroInsideAndGrowsOutside() {
        val band = Band(650, 800)
        assertEquals(0, band.distance(650))
        assertEquals(0, band.distance(800))
        assertEquals(50, band.distance(600))
        assertEquals(30, band.distance(830))
    }

    @Test
    fun huntBudgetIsCeilingOfMeanTimesFactor() {
        // Mean 25 shots over 4 layouts.
        assertEquals(40, huntBudget(referenceSum = 100, referenceCount = 4, factorPermille = 1600))
        assertEquals(37, huntBudget(referenceSum = 100, referenceCount = 4, factorPermille = 1450))
        assertEquals(33, huntBudget(referenceSum = 100, referenceCount = 4, factorPermille = 1300))
        // 25 × 1.001 is just above 25, so the budget rounds up to 26.
        assertEquals(26, huntBudget(referenceSum = 100, referenceCount = 4, factorPermille = 1001))
    }

    @Test
    fun huntBudgetNeverFallsBelowOne() {
        assertEquals(1, huntBudget(referenceSum = 0, referenceCount = 3, factorPermille = 1000))
    }

    @Test
    fun clearShareCountsShotsThatFitTheBudget() {
        assertEquals(750, clearPermille(listOf(10, 20, 30, 40), budget = 30))
        assertEquals(1000, clearPermille(listOf(10, 20), budget = 20))
        assertEquals(0, clearPermille(listOf(50), budget = 49))
    }

    @Test
    fun baseFactorsAreTheContentValues() {
        assertEquals(1600, baseBudgetFactorPermille(Difficulty.EASY))
        assertEquals(1450, baseBudgetFactorPermille(Difficulty.MEDIUM))
        assertEquals(1300, baseBudgetFactorPermille(Difficulty.HARD))
    }

    @Test
    fun gridsStartAtZeroAndEndAtTheirMaxima() {
        assertEquals(0, FOLLOW_UP_GRID.first())
        assertEquals(900, FOLLOW_UP_GRID.last())
        assertEquals(0, BLUNDER_GRID.first())
        assertEquals(BLUNDER_MAX, BLUNDER_GRID.last())
        assertEquals(FACTOR_MAX, FACTOR_GRID.last())
        assertEquals(999, WEAPON_DELAY_MAX)
        assertEquals(listOf(4, 8, 16, 999), WEAPON_DELAY_LADDER)
    }

    @Test
    fun bisectionFindsTheBandOnAMonotoneGridInFewProbes() {
        val probes = mutableListOf<Int>()
        bisectIndex(IntArray(20) { it }, start = 0, band = Band(500, 540)) { i ->
            probes += i
            100 + 40 * i
        }
        // Index 10 gives 500 (in band): the search must end on it, within the 5 probes of a 20-value grid.
        assertEquals(10, probes.last())
        assertTrue(probes.toString(), probes.size <= 5)
    }

    @Test
    fun bisectionStopsWhenTheBudgetIsUsedUp() {
        var calls = 0
        bisectIndex(IntArray(20) { it }, start = 0, band = Band(500, 540)) { calls++; if (calls < 3) 100 else null }
        assertEquals(3, calls)
    }

    @Test
    fun tuningStopsAtOnceInsideTheBand() {
        val search = tuneAdmiral(Knobs(0, 0, 4), Band(650, 800)) { _ -> 700 to "in" }
        assertEquals(1, search.measurements)
        assertEquals(0, search.adjustments)
        assertTrue(search.inBand)
    }

    @Test
    fun tuningConvergesOnAMonotoneSyntheticFunctionForEveryReachableBand() {
        // The win rate rises with the follow-up miss, the blunder and the weapon delay (monotone, as in the game).
        fun rate(k: Knobs): Int = 150 + k.followUp * 2 / 5 + k.blunder / 2 + 3 * k.weaponDelay
        for (centre in 300..950 step 25) {
            val band = Band(centre - 15, centre + 15)
            val search = tuneAdmiral(Knobs(0, 0, 4), band) { k -> rate(k) to k }
            assertTrue("centre $centre: ${search.best?.winPermille}", search.inBand)
            assertTrue(search.measurements <= MAX_TUNING_ROUNDS + 1)
        }
    }

    @Test
    fun tuningIsDeterministic() {
        fun run(): Triple<Knobs, Int, Boolean> {
            val search = tuneAdmiral(Knobs(450, 300, 4), Band(600, 620)) { k -> (150 + k.followUp * 2 / 5 + k.blunder / 2) to k }
            return Triple(checkNotNull(search.best).knobs, search.measurements, search.inBand)
        }
        assertEquals(run(), run())
    }

    @Test
    fun tuningKeepsTheEarliestMeasurementWhenTheBandIsOutOfReach() {
        val search = tuneAdmiral(Knobs(0, 0, 4), Band(650, 800)) { _ -> 100 to "flat" }
        assertFalse(search.inBand)
        // Every rate is equally far from the band: the first measurement is the kept one.
        assertEquals(0, search.adjustments)
        assertEquals(Knobs(0, 0, 4), checkNotNull(search.best).knobs)
    }

    @Test
    fun tuningNeverRunsMoreThanTheRoundBudget() {
        var calls = 0
        val search = tuneAdmiral(Knobs(0, 0, 4), Band(650, 800)) { _ -> calls++; 100 to calls }
        assertTrue(search.measurements <= MAX_TUNING_ROUNDS + 1)
        assertEquals(search.measurements, calls)
    }

    @Test
    fun aConvoyAboveTheBandMakesTheAdmiralStrongestThenGrowsTheRoundsThenTheFreighters() {
        // Rate: the survival chance. Longer convoys and more freighters kept afloat are harder; the blunder and the
        // follow-up miss are the admiral's strength. Above the band with the start knobs.
        fun rate(k: Knobs): Int = 700 + 5 * (SURVIVE_MAX - (k.surviveRounds ?: 0)) + k.blunder / 4 + k.followUp / 4 -
            60 * ((k.minFreighters ?: 1) - 1)
        val calls = mutableListOf<Knobs>()
        val search = tuneConvoy(Knobs(0, 200, 4, surviveRounds = 12, minFreighters = 1), Band(600, 650)) { k ->
            calls += k
            rate(k) to k
        }
        assertTrue(search.inBand)
        // First the start, then the strongest admiral, with the same rounds and freighters.
        assertEquals(Knobs(0, 200, 4, 12, 1), calls[0])
        assertEquals(Knobs(0, 0, 0, 12, 1), calls[1])
        // Then the rounds move in steps of four only, and the freighters go up only after the rounds reached 60.
        val rounds = calls.mapNotNull { it.surviveRounds }
        assertTrue(rounds.toString(), rounds.all { (it - SURVIVE_MIN) % SURVIVE_STEP == 0 && it in SURVIVE_MIN..SURVIVE_MAX })
        val firstFreighters = calls.indexOfFirst { it.minFreighters == 2 }
        assertTrue(firstFreighters > 0)
        assertTrue(calls.take(firstFreighters).filter { it.minFreighters == 1 }.any { it.surviveRounds == SURVIVE_MAX })
        // The kept convoy is the one in band: 60 rounds with two freighters that must stay afloat.
        val kept = checkNotNull(search.best).knobs
        assertEquals(2, kept.minFreighters)
        assertEquals(SURVIVE_MAX, kept.surviveRounds)
        assertEquals(0, kept.blunder)
        assertEquals(0, kept.followUp)
    }

    @Test
    fun aConvoyBelowTheBandShortensTheRoundsBeforeWeakeningTheAdmiral() {
        fun rate(k: Knobs): Int = 100 + 5 * (SURVIVE_MAX - (k.surviveRounds ?: 0)) + k.blunder / 4 + k.followUp / 4 -
            60 * ((k.minFreighters ?: 1) - 1)
        val calls = mutableListOf<Knobs>()
        val search = tuneConvoy(Knobs(0, 0, 4, surviveRounds = 40, minFreighters = 1), Band(600, 650)) { k ->
            calls += k
            rate(k) to k
        }
        assertTrue(search.inBand)
        // Until the admiral changes, the rounds only go down (shorter convoys are easier) and the freighters stay.
        val firstChange = calls.indexOfFirst { it.blunder > 0 || it.followUp > 0 }
        assertTrue(firstChange > 0)
        assertTrue(calls.take(firstChange).all { it.minFreighters == 1 && it.followUp == 0 && it.blunder == 0 })
        assertEquals(SURVIVE_MIN, calls.take(firstChange).minOf { it.surviveRounds ?: Int.MAX_VALUE })
        assertTrue(calls.all { it.minFreighters == 1 })
    }

    @Test
    fun aConvoyStaysDeterministicAndWithinItsRoundBudget() {
        fun run(): List<Knobs> {
            val calls = mutableListOf<Knobs>()
            tuneConvoy(Knobs(0, 300, 2, surviveRounds = 10, minFreighters = 2), Band(300, 340)) { k ->
                calls += k
                (900 - (k.surviveRounds ?: 0) * 3 - k.blunder / 5) to k
            }
            return calls
        }
        assertEquals(run(), run())
        assertTrue(run().size <= CONVOY_TUNING_ROUNDS + 1)
    }

    @Test
    fun theWeaponLadderRisesInBigStepsThenDisablesTheAdmiralWeaponsWhenBelowTheBand() {
        // Only the blunder (up to its maximum) and the weapons move the rate: a long delay and then no weapons at all.
        fun rate(k: Knobs): Int = 100 + k.blunder / 4 + (if (k.weaponDelay >= 16) 100 else 0) + (if (k.weaponsOff) 300 else 0)
        val calls = mutableListOf<Knobs>()
        val search = tuneAdmiral(Knobs(0, 0, 4), Band(700, 800)) { k ->
            calls += k
            rate(k) to k
        }
        assertTrue(search.inBand)
        val ladder = calls.filter { it.blunder == BLUNDER_MAX }.map { it.weaponDelay to it.weaponsOff }.distinct()
        assertTrue(ladder.toString(), ladder.containsAll(listOf(8 to false, 16 to false, WEAPON_NEVER to false, WEAPON_NEVER to true)))
        assertEquals(Knobs(0, BLUNDER_MAX, WEAPON_NEVER, weaponsOff = true), calls.last())
        assertTrue(calls.filter { it.blunder == BLUNDER_MAX && !it.weaponsOff }.none { it.weaponDelay < 8 && it.weaponDelay != 4 })
    }

    @Test
    fun theWeaponLadderStopsAsSoonAsTheBandIsReached() {
        fun rate(k: Knobs): Int = 100 + k.blunder / 4 + (if (k.weaponDelay >= 16) 400 else 0)
        val calls = mutableListOf<Knobs>()
        val search = tuneAdmiral(Knobs(0, 0, 4), Band(700, 800)) { k ->
            calls += k
            rate(k) to k
        }
        assertTrue(search.inBand)
        // 16 turns already put the rate in band: neither 999 nor the emptied set is measured.
        assertTrue(calls.none { it.weaponDelay == WEAPON_NEVER })
        assertTrue(calls.none { it.weaponsOff })
    }

    @Test
    fun aVariantAboveItsBandWithNoBlunderAndNoFollowUpMissEscalatesOneTier() {
        val band = Band(600, 750)
        val weakest = listOf(Knobs(0, 0, 4) to 900)
        assertEquals(Difficulty.MEDIUM, escalationTarget(900, weakest, band, Difficulty.EASY))
        assertEquals(Difficulty.HARD, escalationTarget(900, weakest, band, Difficulty.MEDIUM))
        // Never above Hard.
        assertNull(escalationTarget(900, weakest, band, Difficulty.HARD))
        // Only when the kept result is above the band.
        assertNull(escalationTarget(700, weakest, band, Difficulty.EASY))
        // The weakest admiral must have been measured above the band: a blunder or a follow-up miss in every probe
        // above the band is not enough.
        assertNull(escalationTarget(900, listOf(Knobs(0, 40, 4) to 900, Knobs(75, 0, 4) to 900), band, Difficulty.EASY))
        assertNull(escalationTarget(300, listOf(Knobs(0, 0, 4) to 300), band, Difficulty.EASY))
    }

    @Test
    fun theWeaponDelayMaximumMeansNeverAndTheSetIsEmptyWhenOff() {
        assertEquals(999, WEAPON_NEVER)
        assertEquals(listOf(4, 8, 16, 999), WEAPON_DELAY_LADDER)
        assertTrue(Knobs(0, 0, 0, weaponsOff = true).weaponsOff)
    }
}

/** Calibration of real generated content: the same code path as `leveltool calibrate`. */
class CalibrationRunTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun generated(sea: Int, from: Int, to: Int): File {
        val root = tmp.newFolder()
        generateSea(root, sea, from, to, DEFAULT_SEED)
        return root
    }

    private fun calibrate(root: File, options: CalibrateOptions): Pair<CalibrationRun, List<String>> {
        val lines = mutableListOf<String>()
        val run = calibrateContent(root, options, lines::add)
        return run to lines
    }

    private fun options(sea: Int? = null, mission: String? = null, games: Int = 16, threads: Int = 1, resume: Boolean = false) =
        CalibrateOptions(sea = sea, mission = mission, games = games, threads = threads, resume = resume)

    private fun readMission(root: File, id: String): MissionDto =
        ContentJson.json.decodeFromString(MissionDto.serializer(), File(root, "missions/${seaFolder(id)}/$id.json").readText())

    private fun seaFolder(id: String): String = "s" + id.substring(1, 3)

    /** Every byte of the calibrated files, keyed by relative path, for comparisons. */
    private fun snapshot(root: File): Map<String, String> = root.walkTopDown()
        .filter { it.isFile && (it.parentFile.name == "certificates" || it.parentFile.name == "missions" || it.parentFile.parentFile.name == "missions") }
        .associate { it.relativeTo(root).path to it.readText() }

    @Test
    fun calibrationCommitsStarsBudgetsAndCertificates() {
        val root = generated(1, 1, 2)
        val (run, _) = calibrate(root, options(sea = 1, games = 16))
        assertEquals(6, run.done)
        assertEquals(0, run.failed)
        val mission = readMission(root, "s01m001")
        val variant = mission.variants.getValue(Difficulty.MEDIUM)
        assertTrue(variant.calibrated)
        assertNotNull(variant.stars)
        assertTrue(variant.stars!!.three <= variant.stars!!.two)
        val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), File(root, "certificates/s01m001-M.json").readText())
        assertTrue(certificate is Calibration)
        certificate as Calibration
        assertEquals(variant.stars, certificate.thresholds)
        assertEquals(canonicalHash(VariantDto.serializer(), variant), certificate.variantHash)
        assertEquals(16, certificate.sample.games)
        assertNotNull(certificate.tuning)
    }

    @Test
    fun calibrationIsByteIdenticalAcrossRunsAndThreadCounts() {
        val single = generated(1, 1, 3)
        val repeat = generated(1, 1, 3)
        val parallel = generated(1, 1, 3)
        calibrate(single, options(sea = 1, games = 16, threads = 1))
        calibrate(repeat, options(sea = 1, games = 16, threads = 1))
        calibrate(parallel, options(sea = 1, games = 16, threads = 4))
        val expected = snapshot(single)
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, snapshot(repeat))
        assertEquals(expected, snapshot(parallel))
    }

    @Test
    fun resumeSkipsCalibratedVariantsAndLeavesTheirBytesAlone() {
        val root = generated(1, 1, 2)
        calibrate(root, options(sea = 1, games = 16))
        val before = snapshot(root)
        val (run, lines) = calibrate(root, options(sea = 1, games = 16, resume = true))
        assertEquals(0, run.done)
        assertEquals(6, run.skipped)
        assertTrue(lines.none { it.contains("win") && it.startsWith("s01") })
        assertEquals(before, snapshot(root))
    }

    @Test
    fun calibratedVariantsWithoutResumeAreAnError() {
        val root = generated(1, 1, 1)
        calibrate(root, options(sea = 1, games = 8))
        val failure = runCatching { calibrate(root, options(sea = 1, games = 8)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("--resume"))
    }

    @Test
    fun resumeRejectsACertificateThatNoLongerMatchesItsVariant() {
        val root = generated(1, 1, 1)
        calibrate(root, options(sea = 1, games = 8))
        val path = File(root, "certificates/s01m001-E.json")
        val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), path.readText()) as Calibration
        path.writeText(ContentJson.json.encodeToString(CertificateDto.serializer(), certificate.copy(variantHash = "0".repeat(64))))
        val failure = runCatching { calibrate(root, options(sea = 1, games = 8, resume = true)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun huntBudgetsFollowTheHardReferenceAndTheRecordedFactor() {
        val root = generated(1, 8, 8)
        calibrate(root, options(sea = 1, mission = "s01m008", games = 16))
        val mission = readMission(root, "s01m008")
        for (difficulty in Difficulty.entries) {
            val variant = mission.variants.getValue(difficulty)
            val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), File(root, "certificates/s01m008-${difficulty.name.first()}.json").readText()) as Calibration
            val budget = checkNotNull(variant.shotBudget) { "HUNT ${difficulty.name} needs a budget" }
            assertTrue(budget >= 1)
            assertNotNull(certificate.tuning!!.budgetFactorPermille)
            assertNotNull(certificate.tuning!!.hardClearPermille)
        }
    }

    @Test
    fun bossSeriesCalibratesWithAWitnessThatReplays() {
        val root = generated(1, 50, 50)
        val (run, _) = calibrate(root, options(sea = 1, mission = "s01m050", games = 16))
        assertEquals(3, run.done)
        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.failed.toString(), report.failed.isEmpty())
        // Three variants replay; the structural errors left are only the 64-game minimum of a short run.
        assertEquals(3, report.replayed)
        assertTrue(report.problems.filter { it.severity == Severity.ERROR }.all { it.message.contains("at least 64 games") })
    }

    @Test
    fun convoyCalibratesTheFreighterRule() {
        val root = generated(7, 1, 50)
        val convoyId = (301..350).map { "s07m%03d".format(it) }
            .firstOrNull { id -> readMission(root, id).type == MissionKind.CONVOY }
        assertNotNull("sea 7 has a CONVOY mission", convoyId)
        val (run, _) = calibrate(root, options(sea = 7, mission = convoyId, games = 8))
        assertEquals(3, run.done)
        val variant = readMission(root, convoyId!!).variants.getValue(Difficulty.EASY)
        assertNotNull(variant.convoy)
        assertNull(variant.shotBudget)
    }

    @Test
    fun witnessReplaysAndATamperedOneFails() {
        val root = generated(1, 1, 1)
        calibrate(root, options(sea = 1, games = 16))
        sealContent(root)
        val clean = verifyCertificates(root)
        assertTrue(clean.failed.toString(), clean.failed.isEmpty())
        assertEquals(3, clean.replayed)

        val path = File(root, "certificates/s01m001-H.json")
        val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), path.readText()) as Calibration
        val witness = certificate.witness
        // The actions stay legal, so only the certified final hash can expose the change.
        val tampered = witness.copy(finalHash = "0000000000000001")
        path.writeText(ContentJson.json.encodeToString(CertificateDto.serializer(), certificate.copy(witness = tampered)))
        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.failed.any { it.startsWith("s01m001-H: final hash") })
        assertFalse(report.ok)
    }

    @Test
    fun aWitnessWithAnIllegalActionFails() {
        val root = generated(1, 1, 1)
        calibrate(root, options(sea = 1, games = 8))
        sealContent(root)
        val path = File(root, "certificates/s01m001-E.json")
        val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), path.readText()) as Calibration
        val doubled = certificate.witness.actions.take(1) + certificate.witness.actions
        path.writeText(ContentJson.json.encodeToString(CertificateDto.serializer(), certificate.copy(witness = certificate.witness.copy(actions = doubled))))
        sealContent(root)
        val report = verifyCertificates(root)
        // A repeated action is either illegal at that index or changes the game, so the witness must fail.
        assertTrue(report.failed.toString(), report.failed.any { it.startsWith("s01m001-E: ") })
    }

    @Test
    fun aSmallSubsetCalibratesEndToEndAndVerifies() {
        val started = System.nanoTime()
        val root = generated(1, 1, 5)
        val (run, lines) = calibrate(root, options(sea = 1, games = 16, threads = 2))
        assertEquals(15, run.done)
        assertEquals(0, run.failed)
        assertTrue(lines.any { it.startsWith("calibrate: sea 1 EASY") })
        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.failed.toString(), report.failed.isEmpty())
        assertEquals(15, report.replayed)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000_000.0} s", (System.nanoTime() - started) < 120_000_000_000L)
    }

    @Test
    fun aWrongFirstPlayerInAWitnessIsRejectedForNonBossMissions() {
        val root = generated(1, 1, 1)
        calibrate(root, options(sea = 1, games = 8))
        sealContent(root)
        val path = File(root, "certificates/s01m001-M.json")
        val certificate = ContentJson.json.decodeFromString(CertificateDto.serializer(), path.readText()) as Calibration
        val first = certificate.witness.firstPlayer
        path.writeText(ContentJson.json.encodeToString(CertificateDto.serializer(), certificate.copy(witness = certificate.witness.copy(firstPlayer = 1 - first))))
        sealContent(root)
        val report = verifyCertificates(root)
        assertTrue(report.failed.any { it.startsWith("s01m001-M: firstPlayer") })
    }

    @Test
    fun calibrateCommandRejectsZeroGames() {
        val root = generated(1, 1, 1)
        val code = run(arrayOf("calibrate", "--root", root.path, "--sea", "1", "--games", "0"))
        assertEquals(EXIT_USAGE, code)
    }

    @Test
    fun calibrateCommandRejectsZeroThreads() {
        val root = generated(1, 1, 1)
        assertEquals(EXIT_USAGE, run(arrayOf("calibrate", "--root", root.path, "--threads", "0")))
    }

    @Test
    fun calibrateCommandSucceedsOnAGoodRun() {
        val root = generated(1, 1, 1)
        assertEquals(EXIT_OK, run(arrayOf("calibrate", "--root", root.path, "--sea", "1", "--games", "4")))
        assertEquals(EXIT_USAGE, run(arrayOf("calibrate", "--root", root.path, "--sea", "13")))
    }
}
