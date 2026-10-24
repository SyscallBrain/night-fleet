package pt.tiago.nightfleet.data

import java.io.File
import java.io.FileOutputStream

/**
 * A JSON document stored as `<name>.json` with an independent recovery copy `<name>.bak.json`.
 *
 * Writes go to `<name>.json.tmp`, are fsynced and then renamed over the target, so a crash never
 * leaves a half-written file under the real name. Both copies are written with the same text.
 * A file that cannot be decoded is moved aside as `<name>.corrupt-<n>.json` and is never
 * overwritten silently.
 */
class DurableFile(private val directory: File, private val name: String) {
    val main: File = File(directory, "$name.json")
    val backup: File = File(directory, "$name.bak.json")

    /** Decodes the main file, then the backup. Returns null when neither is readable. */
    fun <T : Any> read(decode: (String) -> T?): ReadResult<T> {
        var movedAside = 0
        for ((index, file) in listOf(main, backup).withIndex()) {
            if (!file.isFile) continue
            val value = runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrNull()
            if (value != null) return ReadResult(value, recoveredFromBackup = index == 1, movedAside = movedAside)
            moveAside(file)
            movedAside++
        }
        return ReadResult(null, recoveredFromBackup = false, movedAside = movedAside)
    }

    fun write(text: String) {
        directory.mkdirs()
        writeAtomically(main, text)
        writeAtomically(backup, text)
    }

    fun delete() {
        listOf(main, backup, File(directory, "${main.name}.tmp"), File(directory, "${backup.name}.tmp"))
            .forEach { it.delete() }
    }

    /** Preserves the text under a separate name, e.g. before a destructive import. */
    fun writeCopy(copyName: String, text: String) {
        DurableFile(directory, copyName).write(text)
    }

    private fun moveAside(file: File) {
        var index = 1
        while (File(directory, "$name.corrupt-$index.json").exists()) index++
        check(file.renameTo(File(directory, "$name.corrupt-$index.json"))) { "Cannot preserve damaged ${file.name}" }
    }

    private fun writeAtomically(target: File, text: String) {
        val temporary = File(directory, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }
        check(temporary.renameTo(target)) { "Cannot atomically replace ${target.name}" }
    }

    data class ReadResult<T : Any>(
        val value: T?,
        val recoveredFromBackup: Boolean,
        /** Number of unreadable files that were moved aside while reading. */
        val movedAside: Int,
    )
}
