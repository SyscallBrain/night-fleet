package pt.tiago.nightfleet.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class DurableFileTest {
    private lateinit var dir: File
    private val decode: (String) -> String? = { text ->
        if (text.startsWith("{") && text.endsWith("}")) text else null
    }

    @Before
    fun setUp() {
        dir = newTempDirectory("durable")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun writeThenReadReturnsTheSameText() {
        val file = DurableFile(dir, "doc")
        file.write("{\"a\":1}")
        val result = file.read(decode)
        assertEquals("{\"a\":1}", result.value)
        assertFalse(result.recoveredFromBackup)
        assertEquals(0, result.movedAside)
    }

    @Test
    fun writeLeavesNoTemporaryFilesBehind() {
        DurableFile(dir, "doc").write("{}")
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertTrue(File(dir, "doc.json").isFile)
        assertTrue(File(dir, "doc.bak.json").isFile)
    }

    @Test
    fun missingFilesReadAsNothingWithoutMovingAnything() {
        val result = DurableFile(dir, "doc").read(decode)
        assertNull(result.value)
        assertEquals(0, result.movedAside)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun truncatedMainFileIsRecoveredFromTheBackupCopy() {
        val file = DurableFile(dir, "doc")
        file.write("{\"version\":2}")
        // Simulate a power cut in the middle of the main write.
        File(dir, "doc.json").writeText("{\"versi")

        val result = file.read(decode)
        assertEquals("{\"version\":2}", result.value)
        assertTrue(result.recoveredFromBackup)
        assertEquals(1, result.movedAside)
    }

    @Test
    fun corruptFileIsMovedAsideAndItsContentIsKept() {
        val damaged = "{\"broken"
        DurableFile(dir, "doc").write("{\"ok\":true}")
        File(dir, "doc.json").writeText(damaged)

        DurableFile(dir, "doc").read(decode)

        val preserved = File(dir, "doc.corrupt-1.json")
        assertTrue(preserved.isFile)
        assertEquals(damaged, preserved.readText())
        assertFalse(File(dir, "doc.json").exists())
    }

    @Test
    fun whenBothCopiesAreDamagedBothAreMovedAsideAndNothingIsReturned() {
        File(dir, "doc.json").writeText("{bad")
        File(dir, "doc.bak.json").writeText("{worse")

        val result = DurableFile(dir, "doc").read(decode)
        assertNull(result.value)
        assertEquals(2, result.movedAside)
        assertTrue(File(dir, "doc.corrupt-1.json").isFile)
        assertTrue(File(dir, "doc.corrupt-2.json").isFile)
    }

    @Test
    fun deleteRemovesBothCopies() {
        val file = DurableFile(dir, "doc")
        file.write("{}")
        file.delete()
        assertNull(file.read(decode).value)
    }
}
