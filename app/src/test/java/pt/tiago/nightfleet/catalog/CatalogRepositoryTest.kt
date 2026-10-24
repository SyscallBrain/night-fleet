package pt.tiago.nightfleet.catalog

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.content.AiConfigDto
import pt.tiago.nightfleet.core.content.CanonicalJson
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CatalogManifestDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.PlacementDto
import pt.tiago.nightfleet.core.content.QuantilesDto
import pt.tiago.nightfleet.core.content.RulesDto
import pt.tiago.nightfleet.core.content.SampleDto
import pt.tiago.nightfleet.core.content.StarsDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.WitnessDto
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.sha256Hex
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.content.CertificateDto.Calibration

/** An in-memory APK: asset path -> bytes, with a count of how often each path was read. */
private class FakeAssets(val files: MutableMap<String, ByteArray>) {
    val reads = HashMap<String, Int>()

    fun open(path: String): ByteArray? {
        reads[path] = (reads[path] ?: 0) + 1
        return files[path]
    }
}

class CatalogRepositoryTest {

    private fun mission(id: String, sea: Int, ordinal: Int): MissionDto = MissionDto(
        id = id,
        sea = sea,
        ordinal = ordinal,
        type = MissionKind.DUEL,
        family = "open-classic",
        tags = emptyList(),
        tutorial = null,
        variants = Difficulty.entries.associateWith { difficulty ->
            VariantDto(
                levelSeed = ordinal.toLong(),
                rules = RulesDto(),
                ai = AiConfigDto(tier = difficulty, blunder = 100),
                layoutPool = "s0$sea-E",
                firstPlayer = FirstPlayer.PLAYER,
                stars = StarsDto(two = 60, three = 40),
            )
        },
    )

    private val pool = LayoutPoolDto(
        id = "s01-E",
        fleet = "CLASSIC",
        terrain = null,
        terrainHash = null,
        layouts = listOf(listOf(PlacementDto(ShipClass.DESTROYER, 0, 0, 0))),
    )

    private val certificate = CertificateDto.Calibration(
        id = "s01m001-E",
        missionId = "s01m001",
        difficulty = Difficulty.EASY,
        variantHash = "0".repeat(64),
        poolHash = canonicalHash(LayoutPoolDto.serializer(), pool),
        engineVersion = ENGINE_VERSION,
        aiVersion = 1,
        contentVersion = 1,
        sample = SampleDto(games = 64, wins = 60, shots = QuantilesDto(10, 12, 14, 18, 22)),
        thresholds = StarsDto(two = 60, three = 40),
        witness = WitnessDto("1", 0, 0, listOf("F:A1"), "0000000000000000"),
    )

    /** A complete, correctly hashed set of assets. Tests break one thing at a time. */
    private fun baseFiles(): MutableMap<String, ByteArray> {
        val catalog = CatalogDto(
            contentVersion = 1,
            engineVersion = ENGINE_VERSION,
            aiVersion = 1,
            seas = listOf("s01", "s02"),
            missions = listOf("s01m001", "s01m002", "s02m051"),
            files = emptyMap(),
        )
        val files = linkedMapOf(
            "catalog.json" to CanonicalJson.bytes(CatalogDto.serializer(), catalog),
            "missions/s01/s01m001.json" to CanonicalJson.bytes(MissionDto.serializer(), mission("s01m001", 1, 1)),
            "missions/s01/s01m002.json" to CanonicalJson.bytes(MissionDto.serializer(), mission("s01m002", 1, 2)),
            "missions/s02/s02m051.json" to CanonicalJson.bytes(MissionDto.serializer(), mission("s02m051", 2, 51)),
            "layouts/s01-E.json" to CanonicalJson.bytes(LayoutPoolDto.serializer(), pool),
            "certificates/s01m001-E.json" to CanonicalJson.bytes(CertificateDto.serializer(), certificate),
        )
        val out = LinkedHashMap<String, ByteArray>(files)
        out[CatalogRepository.MANIFEST_PATH] = manifestFor(files)
        return out
    }

    private fun manifestFor(files: Map<String, ByteArray>, listed: Set<String> = files.keys): ByteArray {
        val manifest = CatalogManifestDto(
            format = CatalogManifestDto.FORMAT,
            contentVersion = 1,
            engineVersion = ENGINE_VERSION,
            aiVersion = 1,
            files = listed.filter { it != CatalogRepository.MANIFEST_PATH }.associateWith { sha256Hex(files.getValue(it)) }.toSortedMap(),
        )
        return CanonicalJson.bytes(CatalogManifestDto.serializer(), manifest)
    }

    private fun repository(assets: FakeAssets) = CatalogRepository { assets.open(it) }

    @Test
    fun catalogLoadsWhenEveryFileMatchesItsHash() {
        val repo = repository(FakeAssets(baseFiles()))
        val catalog = repo.catalog()
        assertTrue(catalog is CatalogResult.Ok)
        assertEquals(listOf("s01", "s02"), (catalog as CatalogResult.Ok).value.seas)
    }

    @Test
    fun missionsForSeaKeepCampaignOrder() {
        val repo = repository(FakeAssets(baseFiles()))
        val sea1 = repo.missionsForSea(1) as CatalogResult.Ok
        assertEquals(listOf("s01m001", "s01m002"), sea1.value.map { it.id })
        val sea2 = repo.missionsForSea(2) as CatalogResult.Ok
        assertEquals(listOf("s02m051"), sea2.value.map { it.id })
    }

    @Test
    fun seaWithoutMissionsIsEmptyNotAnError() {
        val repo = repository(FakeAssets(baseFiles()))
        assertEquals(emptyList<MissionDto>(), (repo.missionsForSea(7) as CatalogResult.Ok).value)
    }

    @Test
    fun missionByIdDecodesOnlyThatMission() {
        val assets = FakeAssets(baseFiles())
        val repo = repository(assets)
        val mission = repo.mission("s01m002") as CatalogResult.Ok
        assertEquals(2, mission.value.ordinal)
        assertEquals(0, assets.reads["missions/s02/s02m051.json"] ?: 0)
    }

    @Test
    fun malformedMissionIdFails() {
        val repo = repository(FakeAssets(baseFiles()))
        val result = repo.mission("bogus")
        assertTrue(result is CatalogResult.Failed && result.reason.contains("malformed mission id"))
    }

    @Test
    fun layoutPoolAndCertificateAreVerifiedAndDecoded() {
        val repo = repository(FakeAssets(baseFiles()))
        assertEquals(pool, (repo.layoutPool("s01-E") as CatalogResult.Ok).value)
        assertEquals(certificate, (repo.certificate("s01m001-E") as CatalogResult.Ok).value)
    }

    @Test
    fun tamperedMissionFailsClosedForItsSeaAndNotForOthers() {
        val files = baseFiles()
        files["missions/s01/s01m001.json"] = files.getValue("missions/s01/s01m001.json").let { it + "\n".toByteArray() }
        val repo = repository(FakeAssets(files))
        val sea1 = repo.missionsForSea(1)
        assertTrue(sea1 is CatalogResult.Failed && sea1.reason.contains("failed its SHA-256 check"))
        assertTrue(repo.mission("s01m001") is CatalogResult.Failed)
        assertTrue(repo.missionsForSea(2) is CatalogResult.Ok)
    }

    @Test
    fun tamperedCatalogFailsEverythingThatNeedsIt() {
        val files = baseFiles()
        files["catalog.json"] = files.getValue("catalog.json") + " ".toByteArray()
        val repo = repository(FakeAssets(files))
        assertTrue(repo.catalog() is CatalogResult.Failed)
        assertTrue(repo.missionsForSea(1) is CatalogResult.Failed)
    }

    @Test
    fun missingAssetIsReportedWithItsPath() {
        val files = baseFiles()
        files.remove("missions/s02/s02m051.json")
        val repo = repository(FakeAssets(files))
        val result = repo.missionsForSea(2)
        assertTrue(result is CatalogResult.Failed && result.reason == "missions/s02/s02m051.json is missing from the app assets")
    }

    @Test
    fun fileNotListedInTheManifestIsNotUsed() {
        val files = baseFiles()
        files[CatalogRepository.MANIFEST_PATH] = manifestFor(files, listed = files.keys - "layouts/s01-E.json")
        val repo = repository(FakeAssets(files))
        val result = repo.layoutPool("s01-E")
        assertTrue(result is CatalogResult.Failed && result.reason.contains("not listed in the manifest"))
        assertTrue(repo.mission("s01m001") is CatalogResult.Ok)
    }

    @Test
    fun missingManifestFailsClosed() {
        val files = baseFiles()
        files.remove(CatalogRepository.MANIFEST_PATH)
        val result = repository(FakeAssets(files)).catalog()
        assertTrue(result is CatalogResult.Failed && result.reason.contains("manifest.json is missing"))
    }

    @Test
    fun unknownManifestFormatFailsClosed() {
        val files = baseFiles()
        val manifest = ContentJson.json.decodeFromString(CatalogManifestDto.serializer(), files.getValue(CatalogRepository.MANIFEST_PATH).toString(Charsets.UTF_8))
        files[CatalogRepository.MANIFEST_PATH] = CanonicalJson.bytes(CatalogManifestDto.serializer(), manifest.copy(format = "nightfleet-assets-99"))
        val result = repository(FakeAssets(files)).catalog()
        assertTrue(result is CatalogResult.Failed && result.reason.contains("unknown content format"))
    }

    @Test
    fun undecodableMissionWithAValidHashFailsWithTheReason() {
        val files = baseFiles()
        files["missions/s01/s01m001.json"] = "{\"id\": \"s01m001\"}\n".toByteArray()
        files[CatalogRepository.MANIFEST_PATH] = manifestFor(files)
        val result = repository(FakeAssets(files)).mission("s01m001")
        assertTrue(result is CatalogResult.Failed && result.reason.startsWith("cannot decode missions/s01/s01m001.json"))
    }

    @Test
    fun unknownFieldInAValidlyHashedFileIsRejected() {
        val files = baseFiles()
        val text = files.getValue("certificates/s01m001-E.json").toString(Charsets.UTF_8).replaceFirst("\"id\"", "\"extra\": 1,\n  \"id\"")
        files["certificates/s01m001-E.json"] = text.toByteArray()
        files[CatalogRepository.MANIFEST_PATH] = manifestFor(files)
        val result = repository(FakeAssets(files)).certificate("s01m001-E")
        assertTrue(result is CatalogResult.Failed && result.reason.startsWith("cannot decode"))
    }

    @Test
    fun eachFileIsReadAndHashedOnce() {
        val assets = FakeAssets(baseFiles())
        val repo = repository(assets)
        repo.missionsForSea(1)
        repo.mission("s01m001")
        repo.missionsForSea(1)
        repo.catalog()
        assertEquals(1, assets.reads["missions/s01/s01m001.json"])
        assertEquals(1, assets.reads["catalog.json"])
        assertEquals(1, assets.reads[CatalogRepository.MANIFEST_PATH])
    }

    @Test
    fun sealedCalibrationCertificateKeepsItsKind() {
        val repo = repository(FakeAssets(baseFiles()))
        val result = repo.certificate("s01m001-E") as CatalogResult.Ok
        assertTrue(result.value is Calibration)
    }

    @Test
    fun seaIdsFollowTheCampaignNaming() {
        assertEquals("s03", CatalogRepository.seaId(3))
        assertEquals("s03", CatalogRepository.seaIdOf("s03m112"))
        assertEquals(null, CatalogRepository.seaIdOf("m112"))
    }

    @Test
    fun catalogJsonRoundTripsThroughTheWriter() {
        val catalog = CatalogDto(1, ENGINE_VERSION, 1, listOf("s01"), listOf("s01m001"), mapOf("catalog.json" to "x"))
        val text = CanonicalJson.encode(CatalogDto.serializer(), catalog)
        assertEquals(catalog, ContentJson.json.decodeFromString(CatalogDto.serializer(), text))
        assertTrue(text.endsWith("}\n"))
    }
}
