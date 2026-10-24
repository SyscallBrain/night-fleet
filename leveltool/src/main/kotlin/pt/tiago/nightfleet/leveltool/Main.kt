package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.DailyPoolDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.EndlessPackDto
import pt.tiago.nightfleet.core.content.PatrolConfigDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.TerrainsDto
import pt.tiago.nightfleet.core.content.fleetPreset
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.random.SplitMix64
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/** Exit codes: 0 success, 1 content or verification failure, 2 usage error. */
const val EXIT_OK = 0
const val EXIT_FAILED = 1
const val EXIT_USAGE = 2

/** Default output of the generators (never `content/`: that is published by hand). */
const val DEFAULT_OUTPUT = "build/generated-content"

/** Simulated games per variant and worker threads of `calibrate`. */
const val DEFAULT_CALIBRATION_GAMES = 64
const val DEFAULT_CALIBRATION_THREADS = 4

private class UsageException(message: String) : RuntimeException(message)

private val USAGE = """
    Night Fleet leveltool: offline content pipeline

    usage: leveltool <command> [options]

    validation and publishing:
      validate [--root content] [--strict]           validate the catalog (--strict: the full campaign)
      seal [--root DIR]                              rewrite catalog.json with the sha256 of every published file
      export-assets [--root content] --output DIR    validate --strict, copy the published files, write manifest.json
      verify-certificates [--root content]           structural checks, PROOF re-solve, witness replay
      calibrate [--root DIR] [--sea N] [--mission ID] [--games 64] [--threads 4] [--resume] [--daily]
                                                     simulated players set stars, budgets and CALIBRATION certificates
                                                     (only uncalibrated variants; --resume skips calibrated ones;
                                                     without --sea/--mission also the daily hunts; --daily: only those)
      report [--root content] --output FILE         Markdown summary: type and modifier counts per sea, UNCALIBRATED list
      hash-file <path>                               print the SHA-256 of a file
      diagnose --mission ID --difficulty D [--root DIR] [--games 16] [--ablate fog,diving,...]
               [--blunder N] [--tier D] [--weapons-off]
                                                     plays the simulated human against the admiral game by game and
                                                     prints winner, end reason, turns and the human's refires (diagnostics)

    generation (write into --output, default $DEFAULT_OUTPUT; --seed defaults to $DEFAULT_SEED):
      daily-hunts --version V [--count 400] [--seed X] [--output DIR]
                                                     daily/hunts.json: dedicated HUNT variants, calibrated later by calibrate --daily
      generate --sea N [--from M] [--to M] [--seed X] [--output DIR]
                                                     the missions of a sea with their pools, puzzles and terrain
      layout-pool --mission ID [--seed X] [--layout-seed Y] [--output DIR]
                                                     regenerates one mission's enemy layout pools (and mines); --layout-seed
                                                     changes only the layouts, keeping the plan of --seed
      terrain-gen --family F --size S --count N [--seed X] [--output DIR]
                                                     seeded island, archipelago or reef patterns (F: scatter, ring, archipelago, reef)
      puzzle-gen --size S --grade G --count N [--seed X] [--output DIR]
                                                     certified puzzles with PROOF certificates (S: 6, 8, 10; G: EASY, MEDIUM, HARD)
      endless-pool --size S [--count 200] [--seed X] [--output DIR]
                                                     endless/pSS.json with its puzzles, ordered by grade and steps
      daily-pool --version V [--count 400] [--seed X] [--output DIR]
                                                     daily/pool.json: certified puzzles per difficulty (hunts pending)
      patrol-config [--output DIR]                   patrol/config.json (provisional values, not calibrated)
    """.trimIndent()

fun main(args: Array<String>) {
    exitProcess(run(args))
}

/** Runs one command and returns its exit code. Output goes to stdout, diagnostics to stderr. */
internal fun run(args: Array<String>): Int {
    val command = args.firstOrNull()
    if (command == null || command == "help" || command == "--help") {
        println(USAGE)
        return if (command == null) EXIT_USAGE else EXIT_OK
    }
    return try {
        val options = parseOptions(args.drop(1))
        when (command) {
            "validate" -> validateCommand(options)
            "seal" -> sealCommand(options)
            "export-assets" -> exportCommand(options)
            "verify-certificates" -> verifyCommand(options)
            "calibrate" -> calibrateCommand(options)
            "diagnose" -> diagnoseCommand(options)
            "report" -> reportCommand(options)
            "hash-file" -> hashCommand(options)
            "generate" -> generateCommand(options)
            "layout-pool" -> layoutPoolCommand(options)
            "terrain-gen" -> terrainCommand(options)
            "puzzle-gen" -> puzzleCommand(options)
            "endless-pool" -> endlessCommand(options)
            "daily-pool" -> dailyCommand(options)
            "daily-hunts" -> dailyHuntsCommand(options)
            "patrol-config" -> patrolCommand(options)
            else -> throw UsageException("unknown command $command")
        }
    } catch (e: UsageException) {
        System.err.println("leveltool: ${e.message}")
        System.err.println(USAGE)
        EXIT_USAGE
    }
}

/** Parsed `--name value` options and bare flags (`--strict`) plus positional arguments. */
private class Options(val values: Map<String, String>, val flags: Set<String>, val positional: List<String>) {
    fun root(): File = File(values["--root"] ?: "content")

    fun output(): File = File(values["--output"] ?: DEFAULT_OUTPUT)

    fun required(name: String): String = values[name] ?: throw UsageException("missing $name")

    fun int(name: String, default: Int? = null): Int {
        val text = values[name] ?: return default ?: throw UsageException("missing $name")
        return text.toIntOrNull() ?: throw UsageException("$name must be an integer, was $text")
    }

    fun seed(): Long {
        val text = values["--seed"] ?: return DEFAULT_SEED
        return text.toLongOrNull() ?: throw UsageException("--seed must be an integer, was $text")
    }
}

private val VALUE_OPTIONS = setOf(
    "--root", "--output", "--sea", "--from", "--to", "--mission", "--size", "--grade", "--count", "--seed",
    "--version", "--family", "--games", "--threads", "--difficulty", "--ablate", "--blunder", "--tier", "--layout-seed",
)
private val FLAG_OPTIONS = setOf("--strict", "--resume", "--daily", "--partial", "--weapons-off")

private fun parseOptions(args: List<String>): Options {
    val values = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    val positional = mutableListOf<String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        when {
            arg in VALUE_OPTIONS -> {
                val value = args.getOrNull(index + 1) ?: throw UsageException("$arg needs a value")
                values[arg] = value
                index++
            }
            arg in FLAG_OPTIONS -> flags += arg
            arg.startsWith("--") -> throw UsageException("unknown option $arg")
            else -> positional += arg
        }
        index++
    }
    return Options(values, flags, positional)
}

private fun validateCommand(options: Options): Int {
    val root = options.root()
    val strict = "--strict" in options.flags
    val partial = "--partial" in options.flags
    val problems = validateContent(root, strict).withPartialCatalog(partial)
    problems.forEach { println(it) }
    val errors = problems.count { it.severity == Severity.ERROR }
    val mode = if (strict) (if (partial) "strict, partial catalog" else "strict") else "lenient"
    if (errors > 0) {
        println("validate: FAILED with $errors error(s), ${problems.size - errors} warning(s) ($mode)")
        return EXIT_FAILED
    }
    val catalog = readCatalog(root)
    println("validate: OK ($mode): ${catalog.seas.size} seas, ${catalog.missions.size} missions, ${problems.size} warning(s)")
    return EXIT_OK
}

private fun sealCommand(options: Options): Int {
    val root = options.root()
    if (!File(root, "catalog.json").isFile) throw UsageException("seal needs $root/catalog.json")
    val sealed = sealContent(root)
    println("seal: $sealed published file(s) listed in ${File(root, "catalog.json").path}")
    return EXIT_OK
}

private fun exportCommand(options: Options): Int {
    val root = options.root()
    val output = File(options.required("--output"))
    val started = System.nanoTime()
    val problems = exportAssets(root, output, partial = "--partial" in options.flags)
    problems.forEach { println(it) }
    if (problems.any { it.severity == Severity.ERROR }) {
        println("export-assets: FAILED, nothing written")
        return EXIT_FAILED
    }
    val files = output.walkTopDown().count { it.isFile }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("export-assets: $files files with manifest.json into ${output.path} in $millis ms")
    return EXIT_OK
}

private fun verifyCommand(options: Options): Int {
    val report = verifyCertificates(options.root())
    report.problems.forEach { println(it) }
    report.failed.forEach { println("verify-certificates: FAILED $it") }
    if (!report.ok) {
        println("verify-certificates: FAILED (${report.failed.size} replay failure(s), ${report.problems.count { it.severity == Severity.ERROR }} structural error(s))")
        return EXIT_FAILED
    }
    println("verify-certificates: OK (${report.replayed} witnesses replayed, ${report.proofs} proofs re-solved)")
    return EXIT_OK
}

private fun diagnoseCommand(options: Options): Int {
    val mission = options.required("--mission")
    if (!Regex("s\\d{2}m\\d{3}").matches(mission)) throw UsageException("--mission must look like s03m112")
    val difficulty = options.required("--difficulty").uppercase().let { name ->
        Difficulty.entries.firstOrNull { it.name == name } ?: throw UsageException("--difficulty must be EASY, MEDIUM or HARD")
    }
    val games = options.int("--games", 16)
    if (games < 1) throw UsageException("--games must be at least 1")
    val ablate = options.values["--ablate"]?.split(',')?.filter { it.isNotBlank() }?.toSet().orEmpty()
    val tier = options.values["--tier"]?.uppercase()?.let { name ->
        Difficulty.entries.firstOrNull { it.name == name } ?: throw UsageException("--tier must be EASY, MEDIUM or HARD")
    }
    val blunder = options.values["--blunder"]?.let { options.int("--blunder") }
    try {
        diagnoseMission(options.root(), mission, difficulty, games, ablate, blunder, "--weapons-off" in options.flags, tier, ::println)
    } catch (e: IllegalArgumentException) {
        throw UsageException(e.message ?: "invalid option")
    }
    return EXIT_OK
}

private fun calibrateCommand(options: Options): Int {
    val sea = options.values["--sea"]?.let { options.int("--sea") }
    if (sea != null && sea !in 1..SEAS_COUNT) throw UsageException("--sea must be 1..$SEAS_COUNT")
    val games = options.int("--games", DEFAULT_CALIBRATION_GAMES)
    if (games < 1) throw UsageException("--games must be at least 1")
    val threads = options.int("--threads", DEFAULT_CALIBRATION_THREADS)
    if (threads < 1) throw UsageException("--threads must be at least 1")
    val mission = options.values["--mission"]
    if (mission != null && !Regex("s\\d{2}m\\d{3}").matches(mission)) throw UsageException("--mission must look like s03m112")
    val dailyOnly = "--daily" in options.flags
    if (dailyOnly && (sea != null || mission != null)) throw UsageException("--daily cannot be combined with --sea or --mission")
    val started = System.nanoTime()
    val run = try {
        calibrateContent(
            root = options.root(),
            options = CalibrateOptions(sea, mission, games, threads, "--resume" in options.flags, dailyOnly),
            out = ::println,
        )
    } catch (e: IllegalStateException) {
        System.err.println("calibrate: ${e.message}")
        return EXIT_FAILED
    }
    val seconds = (System.nanoTime() - started) / 1_000_000_000.0
    println("calibrate: ${run.done} calibrated, ${run.failed} failed, ${run.skipped} skipped (resumed), ${run.outOfBand} out of band, %.1f s with %d thread(s)".format(seconds, threads))
    return if (run.failed == 0) EXIT_OK else EXIT_FAILED
}

private fun reportCommand(options: Options): Int {
    val output = File(options.required("--output"))
    output.absoluteFile.parentFile?.mkdirs()
    output.writeText(buildReport(options.root()), Charsets.UTF_8)
    println("report: wrote ${output.path}")
    return EXIT_OK
}

private fun hashCommand(options: Options): Int {
    val path = options.positional.singleOrNull() ?: throw UsageException("hash-file needs exactly one path")
    val digest = MessageDigest.getInstance("SHA-256").digest(File(path).readBytes())
    println(digest.joinToString("") { "%02x".format(it) } + "  " + path)
    return EXIT_OK
}

private fun generateCommand(options: Options): Int {
    val sea = options.int("--sea")
    if (sea !in 1..SEAS_COUNT) throw UsageException("--sea must be 1..$SEAS_COUNT")
    val from = options.int("--from", 1)
    val to = options.int("--to", MISSIONS_PER_SEA)
    val output = options.output()
    val started = System.nanoTime()
    val written = generateSea(output, sea, from, to, options.seed())
    val millis = (System.nanoTime() - started) / 1_000_000
    println("generate: sea $sea, ${written.size} mission(s) written to ${output.path} in $millis ms")
    return EXIT_OK
}

private fun layoutPoolCommand(options: Options): Int {
    val mission = options.required("--mission")
    val sea = seaNumberOfMission(mission)
    val index = mission.substring(mission.indexOf('m') + 1).toInt() - (sea - 1) * MISSIONS_PER_SEA
    val output = options.output()
    // --layout-seed regenerates only the layouts (and terrain) of the mission; the plan stays the --seed plan.
    val layoutSeed = options.values["--layout-seed"]?.toLong() ?: options.seed()
    generateSea(output, sea, index, index, options.seed(), layoutSeed)
    println("layout-pool: $mission written to ${output.path}/layouts")
    return EXIT_OK
}

private fun seaNumberOfMission(mission: String): Int {
    val match = Regex("s(\\d{2})m(\\d{3})").matchEntire(mission) ?: throw UsageException("--mission must look like s03m112")
    return match.groupValues[1].toInt()
}

private fun terrainCommand(options: Options): Int {
    val kind = when (options.required("--family").lowercase()) {
        "scatter" -> TerrainKind.SCATTER
        "ring" -> TerrainKind.RING
        "archipelago" -> TerrainKind.ARCHIPELAGO
        "reef" -> TerrainKind.REEF
        else -> throw UsageException("--family must be scatter, ring, archipelago or reef")
    }
    val side = options.int("--size")
    if (side !in listOf(8, 10, 12)) throw UsageException("--size must be 8, 10 or 12")
    val count = options.int("--count", 10)
    val output = options.output()
    val rng = SplitMix64(SplitMix64.split(options.seed(), side.toLong() * 31L + kind.ordinal))
    val used = HashSet<String>()
    val fleet = fleetPreset(fleetForSide(side))
    val terrains = mutableListOf<TerrainDto>()
    for (i in 0 until count) {
        val rows = TerrainGen.generate(side, kind, fleet, rng, used)
            ?: throw UsageException("no $kind pattern fits ${side}x$side after many tries")
        terrains += TerrainDto("${kind.family}-${side}x$side-%02d".format(i), rows)
    }
    val file = File(output, "terrains.json")
    val existing = if (file.isFile) ContentJson.json.decodeFromString(TerrainsDto.serializer(), file.readText(Charsets.UTF_8)).terrains else emptyList()
    val merged = (existing.filterNot { old -> terrains.any { it.id == old.id } } + terrains).sortedBy { it.id }
    writeCanonicalFile(output, "terrains.json", TerrainsDto.serializer(), TerrainsDto(merged))
    println("terrain-gen: ${terrains.size} ${kind.family} pattern(s) on ${side}x$side written to ${file.path}")
    return EXIT_OK
}

private fun puzzleCommand(options: Options): Int {
    val side = options.int("--size")
    if (side !in listOf(6, 8, 10)) throw UsageException("--size must be 6, 8 or 10")
    val grade = grade(options.required("--grade"))
    val count = options.int("--count")
    val output = options.output()
    val seed = options.seed()
    val started = System.nanoTime()
    for (i in 0 until count) {
        val proven = Puzzles.prove(side, grade, SplitMix64.split(seed, i.toLong()))
        Puzzles.write(output, proven, certificateId = proven.definition.id)
    }
    val millis = (System.nanoTime() - started) / 1_000_000
    println("puzzle-gen: $count ${side}x$side ${grade.name} puzzle(s) with PROOF certificates in ${output.path} in $millis ms")
    return EXIT_OK
}

private fun endlessCommand(options: Options): Int {
    val side = options.int("--size")
    if (side !in listOf(6, 8, 10)) throw UsageException("--size must be 6, 8 or 10")
    val count = options.int("--count", STRICT_ENDLESS_PER_PACK)
    val output = options.output()
    val started = System.nanoTime()
    val pack: EndlessPackDto = Puzzles.endlessPack(output, side, count, options.seed() + side)
    val millis = (System.nanoTime() - started) / 1_000_000
    println("endless-pool: ${pack.puzzles.size} puzzle(s) of ${side}x$side in endless/p%02d.json, ${millis} ms".format(side))
    return EXIT_OK
}

private fun dailyCommand(options: Options): Int {
    val version = options.int("--version")
    if (version < 1) throw UsageException("--version must be at least 1")
    val count = options.int("--count", 400)
    val output = options.output()
    val started = System.nanoTime()
    val pool: DailyPoolDto = Puzzles.dailyPool(output, version, count, options.seed())
    val millis = (System.nanoTime() - started) / 1_000_000
    println("daily-pool: version $version, ${pool.entries.size} entries in ${output.path}/daily/pool.json in $millis ms")
    return EXIT_OK
}

private fun dailyHuntsCommand(options: Options): Int {
    val version = options.int("--version")
    if (version < 1) throw UsageException("--version must be at least 1")
    val count = options.int("--count", STRICT_DAILY_PER_KIND_AND_DIFFICULTY)
    val output = options.output()
    val started = System.nanoTime()
    val hunts = generateDailyHunts(output, version, count, options.seed())
    val millis = (System.nanoTime() - started) / 1_000_000
    println("daily-hunts: version $version, ${hunts.hunts.size} hunt(s) in ${output.path}/$DAILY_HUNTS_PATH, HUNT entries in $DAILY_POOL_PATH, $millis ms")
    return EXIT_OK
}

private fun patrolCommand(options: Options): Int {
    val output = options.output()
    val config: PatrolConfigDto = patrolConfig()
    writeCanonicalFile(output, "patrol/config.json", PatrolConfigDto.serializer(), config)
    println("patrol-config: ${config.stages.size} stages written to ${output.path}/patrol/config.json")
    return EXIT_OK
}

private fun grade(text: String): PuzzleGrade = try {
    PuzzleGrade.valueOf(text.uppercase())
} catch (e: IllegalArgumentException) {
    throw UsageException("--grade must be EASY, MEDIUM or HARD")
}

private fun readCatalog(root: File): CatalogDto =
    ContentJson.json.decodeFromString(CatalogDto.serializer(), File(root, "catalog.json").readText(Charsets.UTF_8))
