package pt.tiago.nightfleet.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileCodecTest {
    private val sample = Profile(
        variants = mapOf(variantKey("s02m010", Difficulty.MEDIUM) to VariantProgress(stars = 2, bestShots = 31, wins = 1)),
        unlockedSeas = mapOf(Difficulty.EASY to 3, Difficulty.MEDIUM to 1, Difficulty.HARD to 1),
        selectedPaint = DEFAULT_PAINT,
    )

    @Test
    fun encodeThenDecodeReturnsTheSameProfile() {
        val codec = ProfileCodec()
        assertEquals(sample, codec.decode(codec.encode(sample)))
    }

    @Test
    fun unknownFieldsFromANewerBuildAreIgnored() {
        val codec = ProfileCodec()
        val text = codec.encode(sample).replaceFirst("{", "{\"futureField\": [1, 2, 3],")
        assertEquals(sample, codec.decode(text))
    }

    @Test
    fun schemaFromTheFutureIsRejectedNotDowngraded() {
        val codec = ProfileCodec()
        val text = codec.encode(sample).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertNull(codec.decode(text))
    }

    @Test
    fun unreadableTextIsRejected() {
        val codec = ProfileCodec()
        assertNull(codec.decode("{\"variants\": {"))
        assertNull(codec.decode("not json at all"))
        assertNull(codec.decode("[1, 2, 3]"))
    }

    @Test
    fun fakeVersionZeroIsMigratedToVersionOne() {
        // Schema 0 stored the stars of each variant in a flat "progress" map.
        val v0 = """{"progress": {"s01m001:EASY": 2, "s01m002:EASY": 3}}"""
        val migration: ProfileMigration = { old: JsonObject ->
            val progress = old.getValue("progress").jsonObject
            val variants = JsonObject(progress.mapValues { (_, stars) ->
                buildJsonObject { put("stars", stars) }
            })
            JsonObject((old - "progress") + ("variants" to variants))
        }
        val codec = ProfileCodec(currentVersion = 1, migrations = mapOf(0 to migration))

        val migrated = codec.decode(v0)
        requireNotNull(migrated)
        assertEquals(1, migrated.schemaVersion)
        assertEquals(2, migrated.variants.getValue("s01m001:EASY").stars)
        assertEquals(3, migrated.variants.getValue("s01m002:EASY").stars)
    }

    @Test
    fun missingMigrationStepFailsInsteadOfGuessing() {
        val identity: ProfileMigration = { it }
        val codec = ProfileCodec(currentVersion = 2, migrations = mapOf(1 to identity))
        // Version 0 has no step 0 -> 1, so the chain is broken.
        assertNull(codec.decode("""{"variants": {}}"""))
    }
}
