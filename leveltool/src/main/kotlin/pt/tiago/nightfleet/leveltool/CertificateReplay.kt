package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.PlacementDto
import pt.tiago.nightfleet.core.content.TerrainDto
import pt.tiago.nightfleet.core.content.asVariant
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.toPlacement
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.engine.BattleCodec
import pt.tiago.nightfleet.core.engine.BattleSetup
import pt.tiago.nightfleet.core.engine.Engine
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.mission.SeedStreams
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.puzzle.ExactSolver
import pt.tiago.nightfleet.core.puzzle.GradeResult
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.core.puzzle.gradePuzzle
import java.io.File

/** Outcome of replaying one witness game. */
sealed interface ReplayOutcome {
    /** Every action was legal, the player won and the final state has the certified hash. */
    data object Replayed : ReplayOutcome

    data class Failed(val reason: String) : ReplayOutcome
}

/** Engine mission type of a content mission: BOSS duels are DUELs. */
internal fun engineMissionType(kind: MissionKind): MissionType = when (kind) {
    MissionKind.DUEL, MissionKind.BOSS -> MissionType.DUEL
    MissionKind.HUNT -> MissionType.HUNT
    MissionKind.CONVOY -> MissionType.CONVOY
    MissionKind.PUZZLE -> error("puzzles have no battle to replay")
}

/**
 * Replays the witness of one calibration certificate through [Engine.apply].
 *
 * The enemy sea is the certified layout `enemyLayout` of the variant's pool; the player's own fleet
 * is generated from the attempt seed exactly as the app does. Every action is decoded with
 * [BattleCodec] and must be legal; the game must end in a win for the player, and the final
 * [StateHasher] value must equal the certified one.
 */
internal fun replayWitness(
    terrains: Map<String, List<Terrain>>,
    pools: Map<String, List<List<PlacementDto>>>,
    mission: MissionDto,
    difficulty: Difficulty,
    certificate: CertificateDto.Calibration,
): ReplayOutcome {
    val variant: VariantDto = mission.variants[difficulty] ?: return ReplayOutcome.Failed("no variant ${difficulty.name}")
    val poolId = variant.layoutPool ?: return ReplayOutcome.Failed("variant has no layout pool")
    val layouts = pools[poolId] ?: return ReplayOutcome.Failed("unknown layout pool $poolId")
    return replayCalibration(terrains, layouts, mission.type, variant, difficulty, certificate)
}

/**
 * Replays the witness of a calibration certificate for a variant with its enemy [layouts]. Used
 * for campaign variants and for daily hunts ([kind] HUNT). The rules of the variant are rebuilt from its
 * content, every action is decoded with [BattleCodec] and must be legal, and the final [StateHasher] value
 * must equal the certified one. A campaign witness must be a win for the player; a BOSS witness is one won duel.
 */
internal fun replayCalibration(
    terrains: Map<String, List<Terrain>>,
    layouts: List<List<PlacementDto>>,
    kind: MissionKind,
    variant: VariantDto,
    difficulty: Difficulty,
    certificate: CertificateDto.Calibration,
): ReplayOutcome {
    val witness = certificate.witness
    val enemy = layouts.getOrNull(witness.enemyLayout)
        ?: return ReplayOutcome.Failed("enemyLayout ${witness.enemyLayout} is not in the pool")
    val seed = try {
        SplitMix64.seedFromHex(witness.seedHex)
    } catch (e: IllegalArgumentException) {
        return ReplayOutcome.Failed(e.message ?: "bad seed")
    }
    // A BOSS witness is one duel of a series, and the series alternates the starting player per duel.
    if (kind != MissionKind.BOSS) {
        val expectedFirst = when (variant.firstPlayer) {
            FirstPlayer.PLAYER -> 0
            FirstPlayer.ADMIRAL -> 1
            FirstPlayer.ALTERNATE -> SeedStreams.firstPlayer(seed)
        }
        if (witness.firstPlayer != expectedFirst) {
            return ReplayOutcome.Failed("firstPlayer ${witness.firstPlayer} differs from the variant rule ($expectedFirst)")
        }
    }

    val rules = try {
        variant.toRuleSet(terrains)
    } catch (e: IllegalArgumentException) {
        return ReplayOutcome.Failed(e.message ?: "invalid rules")
    }
    val admiralDecoys = witness.decoys.map { label ->
        Cell.parse(label) ?: return ReplayOutcome.Failed("malformed decoy label $label")
    }
    val setup = BattleSetup.create(
        rules = rules,
        mission = engineMissionType(kind),
        placements = listOf(null, enemy.map { it.toPlacement() }),
        firstPlayer = witness.firstPlayer,
        attemptSeed = seed,
        shotBudget = variant.shotBudget,
        decoys = listOf(emptyList(), admiralDecoys),
    )
    var state = when (setup) {
        is SetupResult.Created -> setup.state
        is SetupResult.Rejected -> return ReplayOutcome.Failed("setup rejected: ${setup.reasons.joinToString()}")
    }
    for ((index, text) in witness.actions.withIndex()) {
        val action = runCatching { BattleCodec.decodeAction(text) }
            .getOrElse { return ReplayOutcome.Failed("action $index is not a BattleCodec action: ${it.message}") }
        val outcome = Engine.apply(state, action)
        outcome.events.filterIsInstance<Event.IllegalAction>().firstOrNull()?.let {
            return ReplayOutcome.Failed("action $index is illegal: ${it.reason}")
        }
        state = outcome.state
    }
    val result = state.result ?: return ReplayOutcome.Failed("witness does not finish the battle")
    if (result.winner != 0) return ReplayOutcome.Failed("witness is not a win for the player")
    val hash = "%016x".format(StateHasher.hash(state))
    if (hash != witness.finalHash) return ReplayOutcome.Failed("final hash $hash differs from the certificate ${witness.finalHash}")
    return ReplayOutcome.Replayed
}

/** Result of `verify-certificates`: the structural problems plus one replay outcome per calibration. */
class VerifyReport(
    val problems: List<Problem>,
    val replayed: Int,
    val failed: List<String>,
    /** PROOF certificates re-proved with the exact solver. */
    val proofs: Int = 0,
) {
    val ok: Boolean get() = failed.isEmpty() && problems.none { it.severity == Severity.ERROR }
}

/**
 * Re-proves a PROOF: the exact solver must find exactly one solution, equal to the stored
 * one, and the logic grader must give the stored grade. Returns the failure reason, or null.
 */
internal fun replayProof(puzzle: PuzzleDefinition, proof: CertificateDto.Proof): String? {
    val count = ExactSolver.countSolutions(puzzle)
    if (!count.isUnique) {
        return "the exact solver does not prove a unique solution (count ${count.count}, budget exceeded: ${count.budgetExceeded})"
    }
    val solved = ExactSolver.solve(puzzle) ?: return "the exact solver finds no unique solution"
    if (solved != proof.solution) return "the stored solution differs from the solver's"
    val graded = gradePuzzle(puzzle) as? GradeResult.Graded ?: return "logic does not solve the puzzle"
    if (graded.grade != proof.grade) return "grade ${proof.grade} differs from the graded ${graded.grade}"
    return null
}

/** Structural checks (as in `validate`) plus the witness replay of every calibration certificate. */
fun verifyCertificates(root: File): VerifyReport {
    val set = loadContent(root)
    val problems = ContentValidator(set, strict = false).run()
    val catalog = set.catalog ?: return VerifyReport(problems, 0, listOf("catalog.json is missing"))
    if (catalog.engineVersion != ENGINE_VERSION) {
        return VerifyReport(problems, 0, listOf("catalog engineVersion does not match the engine"))
    }
    val terrains: Map<String, List<Terrain>> = set.terrains?.value?.terrains
        ?.mapNotNull { dto: TerrainDto -> runCatching { dto.toTerrains() }.getOrNull()?.let { dto.id to it } }
        ?.toMap()
        .orEmpty()
    val pools = set.pools.mapValues { (_, loaded) -> loaded.value.layouts }
    var replayed = 0
    val failed = mutableListOf<String>()
    for (missionId in catalog.missions) {
        val mission = set.missions[missionId]?.value ?: continue
        if (mission.type == MissionKind.PUZZLE) continue
        for (difficulty in Difficulty.entries) {
            val id = certificateId(missionId, difficulty)
            val certificate = set.certificates[id]?.value as? CertificateDto.Calibration ?: continue
            when (val outcome = replayWitness(terrains, pools, mission, difficulty, certificate)) {
                ReplayOutcome.Replayed -> replayed++
                is ReplayOutcome.Failed -> failed += "$id: ${outcome.reason}"
            }
        }
    }
    // Daily hunts carry their own terrain, layouts and certificate.
    val hunts = set.dailyHunts?.value?.hunts.orEmpty()
    val huntTerrains = runCatching { dailyTerrains(hunts) }.getOrElse { emptyMap() }
    for (hunt in hunts) {
        val certificate = hunt.certificate ?: continue
        val terrainsOfHunt = huntTerrains + terrains
        when (val outcome = replayCalibration(terrainsOfHunt, hunt.layouts, MissionKind.HUNT, hunt.asVariant(), hunt.difficulty, certificate)) {
            ReplayOutcome.Replayed -> replayed++
            is ReplayOutcome.Failed -> failed += "${hunt.id}: ${outcome.reason}"
        }
    }
    var proofs = 0
    for ((id, loaded) in set.certificates.toSortedMap()) {
        val proof = loaded.value as? CertificateDto.Proof ?: continue
        val puzzle = set.puzzles[proof.puzzleId]?.value
        if (puzzle == null) {
            failed += "$id: unknown puzzle ${proof.puzzleId}"
            continue
        }
        val reason = replayProof(puzzle, proof)
        if (reason == null) proofs++ else failed += "$id: $reason"
    }
    return VerifyReport(problems, replayed, failed, proofs)
}
