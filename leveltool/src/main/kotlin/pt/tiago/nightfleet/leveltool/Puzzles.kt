package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.KSerializer
import pt.tiago.nightfleet.core.content.CanonicalJson
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.DailyEntryDto
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.EndlessPackDto
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.puzzle.PuzzleCertificate
import pt.tiago.nightfleet.core.puzzle.PuzzleCertifier
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.PuzzleGenerator
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.random.SplitMix64
import java.io.File

/** A puzzle with the certificate that [PuzzleCertifier] issued for it. */
internal class ProvenPuzzle(val definition: PuzzleDefinition, val certificate: PuzzleCertificate)

/** Puzzle generation, proof and the files that hold them. */
internal object Puzzles {
    /** Seeds tried for one puzzle before giving up. */
    private const val ATTEMPTS = 40

    /** Grade of a puzzle that a variant of [difficulty] uses (names match). */
    fun gradeOf(difficulty: Difficulty): PuzzleGrade = PuzzleGrade.valueOf(difficulty.name)

    /**
     * A `side` x `side` puzzle of [grade] that the logic grader solves with T1–T4 and the exact
     * solver proves unique. Fully determined by [seed]. Fails loudly when no attempt succeeds.
     */
    fun prove(side: Int, grade: PuzzleGrade, seed: Long): ProvenPuzzle {
        for (attempt in 0 until ATTEMPTS) {
            val rng = SplitMix64(SplitMix64.split(seed, attempt.toLong()))
            val islands = rng.nextInt(side / 3 + 1)
            val definition = PuzzleGenerator.generate(side, grade, islands, rng) ?: continue
            val certificate = PuzzleCertifier.certify(definition) ?: continue
            return ProvenPuzzle(definition, certificate)
        }
        error("no ${side}x$side puzzle of grade $grade for seed $seed in $ATTEMPTS attempts")
    }

    /** The PROOF certificate of [proven] under [id] (a variant id, or the puzzle id for stand-alone puzzles). */
    fun proof(id: String, proven: ProvenPuzzle): CertificateDto.Proof = CertificateDto.Proof(
        id = id,
        puzzleId = proven.definition.id,
        puzzleHash = proven.certificate.puzzleHash,
        solution = proven.certificate.solution,
        solutionCount = proven.certificate.solutionCount,
        difficulty = proven.certificate.difficulty,
        grade = proven.certificate.grade,
        puzzleVersion = proven.certificate.puzzleVersion,
        engineVersion = ENGINE_VERSION,
        contentVersion = CONTENT_VERSION,
    )

    /** Writes `puzzles/<id>.json` and, when [certificateId] is set, its PROOF in `certificates/`. */
    fun write(root: File, proven: ProvenPuzzle, certificateId: String?) {
        writeCanonicalFile(root, "puzzles/${proven.definition.id}.json", PuzzleDefinition.serializer(), proven.definition)
        if (certificateId != null) {
            writeCanonicalFile(root, "certificates/$certificateId.json", CertificateDto.serializer(), proof(certificateId, proven))
        }
    }

    /**
     * `endless/pNN.json`: [count] puzzles of [side], cycling through the three grades,
     * listed by grade, then by number of logic steps, then by id.
     */
    fun endlessPack(root: File, side: Int, count: Int, seed: Long): EndlessPackDto {
        val proven = (0 until count).map { i ->
            val grade = PuzzleGrade.entries[i % PuzzleGrade.entries.size]
            prove(side, grade, SplitMix64.split(seed, i.toLong())).also { write(root, it, certificateId = it.definition.id) }
        }
        val ordered = proven.sortedWith(
            compareBy<ProvenPuzzle> { it.certificate.grade.maxTechnique.ordinal }
                .thenBy { it.certificate.grade.steps }
                .thenBy { it.definition.id },
        )
        val pack = EndlessPackDto(size = side, puzzles = ordered.map { it.definition.id })
        writeCanonicalFile(root, "endless/p%02d.json".format(side), EndlessPackDto.serializer(), pack)
        return pack
    }

    /**
     * `daily/pool.json`: [count] stand-alone puzzles per difficulty, certified. Entries of
     * one kind and difficulty keep their order; the day picks one through DailyIndex.
     */
    fun dailyPool(root: File, version: Int, count: Int, seed: Long): DailyPoolDto {
        val entries = mutableListOf<DailyEntryDto>()
        for (difficulty in Difficulty.entries) {
            val side = if (difficulty == Difficulty.HARD) 10 else 8
            for (i in 0 until count) {
                val proven = prove(side, gradeOf(difficulty), SplitMix64.split(seed, difficulty.ordinal * 100_000L + i))
                write(root, proven, certificateId = proven.definition.id)
                entries += DailyEntryDto(
                    id = "v$version-puzzle-${difficulty.name.lowercase()}-%03d".format(i),
                    kind = DailyKind.PUZZLE,
                    difficulty = difficulty,
                    ref = proven.definition.id,
                )
            }
        }
        // Hunt entries come from daily-hunts: rewriting the puzzle entries must keep them.
        val hunts = readDailyPool(root)?.entries?.filter { it.kind == DailyKind.HUNT }.orEmpty()
        val pool = DailyPoolDto(version = version, entries = entries + hunts)
        writeCanonicalFile(root, "daily/pool.json", DailyPoolDto.serializer(), pool)
        return pool
    }
}

/** Writes [value] as canonical JSON to `root/relative`, creating the directories. */
internal fun <T> writeCanonicalFile(root: File, relative: String, serializer: KSerializer<T>, value: T) {
    val file = File(root, relative)
    file.parentFile?.mkdirs()
    file.writeBytes(CanonicalJson.bytes(serializer, value))
}
