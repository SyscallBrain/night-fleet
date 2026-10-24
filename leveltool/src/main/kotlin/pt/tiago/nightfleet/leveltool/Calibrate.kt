package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.DailyHuntsDto
import pt.tiago.nightfleet.core.content.FirstPlayer
import pt.tiago.nightfleet.core.content.asVariant
import pt.tiago.nightfleet.core.content.CertificateDto.Calibration
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.LayoutPoolDto
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.SampleDto
import pt.tiago.nightfleet.core.content.TuningDto
import pt.tiago.nightfleet.core.content.VariantDto
import pt.tiago.nightfleet.core.content.WitnessDto
import pt.tiago.nightfleet.core.content.canonicalHash
import pt.tiago.nightfleet.core.content.toPlacement
import pt.tiago.nightfleet.core.content.toRuleSet
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.engine.BattleCodec
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Options of `calibrate`. */
internal class CalibrateOptions(
    val sea: Int?,
    val mission: String?,
    val games: Int,
    val threads: Int,
    val resume: Boolean,
    /** Only the daily hunts (`--daily`): the campaign is left alone. */
    val dailyOnly: Boolean = false,
)

/** What one variant produced: the new variant and its certificate, or the reason it could not be calibrated. */
internal sealed class CalibrationResult {
    class Done(
        val missionId: String,
        val seaNumber: Int,
        val difficulty: Difficulty,
        val variant: VariantDto,
        val certificate: Calibration,
        val line: String,
    ) : CalibrationResult() {
        val winPermille: Int get() = certificate.tuning?.winPermille ?: 0
        val outOfBand: Boolean get() = certificate.outOfBand
    }

    class Failed(val label: String, val reason: String) : CalibrationResult()
}

/** One variant to calibrate, with everything its worker needs (read-only). */
internal class CalibrationJob(
    val missionId: String,
    val seaNumber: Int,
    val kind: MissionKind,
    val difficulty: Difficulty,
    val variant: VariantDto,
    val battlefield: Battlefield,
    val poolId: String,
    val poolHash: String,
    /** Id of the certificate: `<missionId>-<E|M|H>` for the campaign, the hunt id for a daily hunt. */
    val certificateId: String,
    /** True for a daily hunt, whose result is written into `daily/hunts.json`. */
    val daily: Boolean = false,
)

/** Aggregates per sea and difficulty, for the summary lines. */
internal class CalibrationTally {
    private val rates = sortedMapOf<Pair<Int, Difficulty>, MutableList<Int>>(
        compareBy<Pair<Int, Difficulty>> { it.first }.thenBy { it.second.ordinal },
    )
    private val outOfBand = sortedMapOf<Pair<Int, Difficulty>, MutableList<String>>(
        compareBy<Pair<Int, Difficulty>> { it.first }.thenBy { it.second.ordinal },
    )
    private val tuned = mutableMapOf<Pair<Int, Difficulty>, Int>()

    fun add(done: CalibrationResult.Done) {
        val key = done.seaNumber to done.difficulty
        rates.getOrPut(key) { mutableListOf() } += done.winPermille
        if (done.outOfBand) outOfBand.getOrPut(key) { mutableListOf() } += "${done.missionId} (${done.winPermille / 10}%)"
        tuned[key] = (tuned[key] ?: 0) + (done.certificate.tuning?.adjustments ?: 0).coerceAtLeast(0)
    }

    fun lines(): List<String> = rates.map { (key, values) ->
        val (sea, difficulty) = key
        val mean = values.sum() / values.size
        val outs = outOfBand[key].orEmpty()
        "calibrate: sea %d %-6s %3d variant(s), mean win %d%%, out of band %d%s, tuning steps %d".format(
            sea, difficulty.name, values.size, mean / 10, outs.size,
            if (outs.isEmpty()) "" else " ${outs.joinToString(", ")}", tuned[key] ?: 0,
        )
    }

    fun outOfBandCount(): Int = outOfBand.values.sumOf { it.size }
}

/**
 * Calibrates every non-PUZZLE variant of [root] that is not calibrated yet:
 * simulated players measure the win rate and the shot quantiles, the tuning search keeps the closest
 * values, and each variant gets its stars, shot budget, CALIBRATION certificate and witness.
 *
 * The simulations run on [CalibrateOptions.threads] workers. Each one is a pure function of its variant,
 * and the results are committed (certificate first, then mission) and printed in catalog order, so the
 * files and the output do not depend on the thread count or the scheduling.
 */
internal fun calibrateContent(root: File, options: CalibrateOptions, out: (String) -> Unit): CalibrationRun {
    val set = loadContent(root)
    val catalog = set.catalog ?: throw IllegalStateException("catalog.json is missing")
    val terrains = set.terrains?.value?.terrains.orEmpty().associate { it.id to it.toTerrains() }
    val admirals = set.admirals?.value?.admirals.orEmpty()

    val missions = linkedMapOf<String, MissionDto>()
    val missionFiles = mutableMapOf<String, String>()
    val jobs = mutableListOf<CalibrationJob>()
    val skipped = mutableListOf<String>()
    for (missionId in catalog.missions) {
        val loaded = set.missions[missionId] ?: throw IllegalStateException("mission $missionId is missing")
        missions[missionId] = loaded.value
        missionFiles[missionId] = loaded.file
        val mission = loaded.value
        if (mission.type == MissionKind.PUZZLE) continue
        if (options.dailyOnly) continue
        if (options.sea != null && mission.sea != options.sea) continue
        if (options.mission != null && missionId != options.mission) continue
        for (difficulty in Difficulty.entries) {
            val variant = mission.variants[difficulty] ?: continue
            val label = "$missionId ${difficulty.name}"
            val poolId = variant.layoutPool ?: throw IllegalStateException("$label has no layout pool")
            val pool = set.pools[poolId]?.value ?: throw IllegalStateException("$label: layout pool $poolId is missing")
            val certificateId = certificateId(missionId, difficulty)
            if (variant.calibrated) {
                val certificate = set.certificates[certificateId]?.value as? Calibration
                val matches = certificate != null && certificate.variantHash == canonicalHash(VariantDto.serializer(), variant)
                if (!options.resume) throw IllegalStateException("$label is already calibrated: use --resume to skip calibrated variants")
                if (!matches) throw IllegalStateException("$label is calibrated but its certificate $certificateId does not match the variant")
                skipped += label
                continue
            }
            jobs += CalibrationJob(
                missionId = missionId,
                seaNumber = mission.sea,
                kind = mission.type,
                difficulty = difficulty,
                variant = variant,
                battlefield = battlefieldOf(set, admirals, terrains, mission, difficulty, variant, pool.layouts),
                poolId = poolId,
                poolHash = canonicalHash(LayoutPoolDto.serializer(), pool),
                certificateId = certificateId,
            )
        }
    }

    // Daily hunts are calibrated with the whole campaign: they have no sea of their own.
    val dailyHunts = set.dailyHunts?.value
    if (options.dailyOnly && dailyHunts == null) throw IllegalStateException("$DAILY_HUNTS_PATH is missing: run daily-hunts first")
    if (dailyHunts != null && options.sea == null && options.mission == null) {
        val terrainsOfHunts = dailyTerrains(dailyHunts.hunts)
        for (hunt in dailyHunts.hunts) {
            val label = "${hunt.id} ${hunt.difficulty.name}"
            val variant = hunt.asVariant()
            if (variant.calibrated) {
                val certificate = hunt.certificate
                val matches = certificate != null && certificate.variantHash == canonicalHash(VariantDto.serializer(), variant)
                if (!options.resume) throw IllegalStateException("$label is already calibrated: use --resume to skip calibrated variants")
                if (!matches) throw IllegalStateException("$label is calibrated but its certificate does not match the hunt")
                skipped += label
                continue
            }
            val layouts = hunt.layouts.map { layout -> layout.map { it.toPlacement() } }
            jobs += CalibrationJob(
                missionId = hunt.id,
                seaNumber = 0,
                kind = MissionKind.HUNT,
                difficulty = hunt.difficulty,
                variant = variant,
                battlefield = Battlefield(
                    kind = MissionKind.HUNT,
                    difficulty = hunt.difficulty,
                    rules = variant.toRuleSet(terrainsOfHunts),
                    firstPlayer = FirstPlayer.PLAYER,
                    layouts = layouts,
                    admiral = Admirals.ALL.first(),
                    levelSeed = variant.levelSeed,
                    ai = variant.ai,
                ),
                poolId = hunt.id,
                poolHash = canonicalHash(LayoutPoolDto.serializer(), hunt.layoutPool()),
                certificateId = hunt.id,
                daily = true,
            )
        }
    }

    val tally = CalibrationTally()
    var failed = 0
    var done = 0
    val calibratedHunts = linkedMapOf<String, CalibrationResult.Done>()
    val executor = Executors.newFixedThreadPool(options.threads.coerceAtLeast(1))
    try {
        val futures = jobs.map { job -> executor.submit(Callable { calibrateVariant(job, catalog, options.games) }) }
        for ((job, future) in jobs.zip(futures)) {
            when (val result = future.get()) {
                is CalibrationResult.Failed -> {
                    failed++
                    out("${result.label} FAILED: ${result.reason}")
                }
                is CalibrationResult.Done -> {
                    if (job.daily) calibratedHunts[job.missionId] = result else commit(root, result, missions, missionFiles, job.missionId)
                    done++
                    tally.add(result)
                    out(result.line)
                }
            }
        }
    } finally {
        executor.shutdown()
    }
    if (calibratedHunts.isNotEmpty() && dailyHunts != null) {
        writeCanonicalFile(root, DAILY_HUNTS_PATH, DailyHuntsDto.serializer(), dailyHuntsWith(dailyHunts, calibratedHunts))
    }
    tally.lines().forEach(out)
    return CalibrationRun(done = done, failed = failed, skipped = skipped.size, outOfBand = tally.outOfBandCount())
}

/** Totals of one calibrate run. */
internal class CalibrationRun(val done: Int, val failed: Int, val skipped: Int, val outOfBand: Int)

/** The daily hunts with the calibrated ones replaced, in their original order. */
private fun dailyHuntsWith(daily: DailyHuntsDto, calibrated: Map<String, CalibrationResult.Done>): DailyHuntsDto =
    daily.copy(hunts = daily.hunts.map { hunt ->
        val done = calibrated[hunt.id] ?: return@map hunt
        hunt.copy(
            calibrated = true,
            stars = done.variant.stars,
            shotBudget = done.variant.shotBudget,
            certificate = done.certificate,
        )
    })

/** Writes the certificate, then the mission with the calibrated variant (canonical JSON only). */
private fun commit(
    root: File,
    result: CalibrationResult.Done,
    missions: MutableMap<String, MissionDto>,
    missionFiles: Map<String, String>,
    missionId: String,
) {
    writeCanonicalFile(root, "certificates/${result.certificate.id}.json", CertificateDto.serializer(), result.certificate)
    val mission = missions.getValue(missionId)
    val updated = mission.copy(variants = mission.variants + (result.difficulty to result.variant))
    missions[missionId] = updated
    writeCanonicalFile(root, missionFiles.getValue(missionId), MissionDto.serializer(), updated)
}

internal fun battlefieldOf(
    set: ContentSet,
    admirals: List<pt.tiago.nightfleet.core.content.AdmiralDto>,
    terrains: Map<String, List<pt.tiago.nightfleet.core.grid.Terrain>>,
    mission: MissionDto,
    difficulty: Difficulty,
    variant: VariantDto,
    layouts: List<List<pt.tiago.nightfleet.core.content.PlacementDto>>,
): Battlefield {
    val seaId = "s%02d".format(mission.sea)
    val sea = set.seas[seaId]?.value ?: throw IllegalStateException("sea $seaId is missing")
    val admiral = admirals.firstOrNull { it.id == sea.admiralId }
        ?: throw IllegalStateException("admiral ${sea.admiralId} is not in admirals.json")
    val profile = Admirals.ALL.getOrNull(admiral.number - 1)
        ?: throw IllegalStateException("no AdmiralProfile for admiral ${admiral.id}")
    return Battlefield(
        kind = mission.type,
        difficulty = difficulty,
        rules = variant.toRuleSet(terrains),
        firstPlayer = variant.firstPlayer,
        layouts = layouts.map { layout -> layout.map { it.toPlacement() } },
        admiral = profile,
        levelSeed = variant.levelSeed,
        ai = variant.ai,
    )
}

/** Calibrates one variant. Pure: it reads the job and returns a result, touching no file. */
internal fun calibrateVariant(job: CalibrationJob, catalog: pt.tiago.nightfleet.core.content.CatalogDto, games: Int): CalibrationResult {
    val label = "${job.missionId} ${job.difficulty.name}"
    return try {
        require(games >= 1) { "--games must be at least 1" }
        val bf = job.battlefield
        val band = bandOf(job.kind, job.difficulty)
        when (job.kind) {
            MissionKind.HUNT -> calibrateHunt(job, catalog, band, games)
            MissionKind.DUEL, MissionKind.CONVOY, MissionKind.BOSS -> {
                val search = tuneEscalating(bf, job.kind, band, games)
                val round = checkNotNull(search.best).payload
                val knobs = round.knobs
                val convoy = job.variant.convoy
                val aiConfig = job.variant.ai
                val variant = job.variant.copy(
                    ai = aiConfig.copy(
                        tier = round.tier,
                        blunder = knobs.blunder,
                        followUpMiss = knobs.followUp,
                        weaponDelay = knobs.weaponDelay,
                        admiralWeapons = if (knobs.weaponsOff) emptyList() else aiConfig.admiralWeapons,
                    ),
                    convoy = convoy?.copy(
                        surviveRounds = knobs.surviveRounds ?: convoy.surviveRounds,
                        minFreighters = knobs.minFreighters ?: convoy.minFreighters,
                    ),
                )
                val tuning = TuningDto(
                    measurements = search.measurements,
                    adjustments = search.adjustments,
                    winPermille = round.batch.winPermille,
                    blunder = knobs.blunder,
                    followUpMiss = knobs.followUp,
                    weaponDelay = knobs.weaponDelay,
                    surviveRounds = knobs.surviveRounds,
                    minFreighters = knobs.minFreighters,
                    tier = round.tier,
                    admiralWeapons = variant.ai.admiralWeapons,
                )
                finish(job, catalog, label, variant, round.batch, tuning, search.inBand)
            }
            MissionKind.PUZZLE -> error("puzzles are not calibrated")
        }
    } catch (e: RuntimeException) {
        CalibrationResult.Failed(label, e.message ?: e.toString())
    }
}

/** One admiral measurement of the search: its knobs, the admiral tier it was played at and the batch they produced. */
private class KnobRound(val knobs: Knobs, val tier: Difficulty, val batch: Batch)

/** One tuning search of an admiral at the tier of [bf] (duel, boss or convoy levers). */
private fun tuneAt(bf: Battlefield, kind: MissionKind, band: Band, games: Int): TuningSearch<Knobs, KnobRound> {
    val measure = { knobs: Knobs ->
        val batch = runBatch(games) { i ->
            if (kind == MissionKind.BOSS) bf.seriesGame(i, knobs) else bf.duelGame(i, knobs)
        }
        batch.winPermille to KnobRound(knobs, bf.ai.tier, batch)
    }
    return if (kind == MissionKind.CONVOY) {
        tuneConvoy(bf.startKnobs(), band, measure)
    } else {
        tuneAdmiral(bf.startKnobs(), band, measure)
    }
}

/**
 * Tuning with the tier escalation. A variant still above its band with no blunder
 * and no follow-up miss has its admiral raised one tier (Easy to Medium, Medium to Hard) and is tuned again. A tier is
 * never lowered below the difficulty's base tier. The closest result of all the tiers is kept.
 */
private fun tuneEscalating(base: Battlefield, kind: MissionKind, band: Band, games: Int): TuningSearch<Knobs, KnobRound> {
    var current = base
    var best: TuningSearch<Knobs, KnobRound>? = null
    while (true) {
        val search = tuneAt(current, kind, band, games)
        val kept = checkNotNull(search.best)
        val closest = best?.best
        if (closest == null || band.distance(kept.winPermille) < band.distance(closest.winPermille)) best = search
        val history = search.history.map { it.knobs to it.winPermille }
        val next = escalationTarget(kept.winPermille, history, band, current.ai.tier) ?: return checkNotNull(best)
        current = current.escalatedTo(next)
    }
}

private class HuntRound(val factor: Int, val budget: Int, val clear: Int, val batch: Batch)

/**
 * HUNT: the HARD admiral fires alone at every layout to give the mean shots; the budget is
 * `ceil(mean × factor)`, and the HARD admiral must clear at least 97 % of the pool within it, which is
 * reported as a flag when it does not. Then the simulated human plays with that budget, and the factor
 * is tuned by binary search.
 */
private fun calibrateHunt(
    job: CalibrationJob,
    catalog: pt.tiago.nightfleet.core.content.CatalogDto,
    band: Band,
    games: Int,
): CalibrationResult {
    val bf = job.battlefield
    val reference = bf.hardReference()
    val sum = reference.sumOf { it.toLong() }
    val search = tuneFactor(baseBudgetFactorPermille(job.difficulty), band) { factor ->
        val budget = huntBudget(sum, reference.size, factor)
        val batch = runBatch(games) { i -> bf.huntGame(i, budget) }
        batch.winPermille to HuntRound(factor, budget, clearPermille(reference, budget), batch)
    }
    val round = checkNotNull(search.best).payload
    val variant = job.variant.copy(shotBudget = round.budget)
    val tuning = TuningDto(
        measurements = search.measurements,
        adjustments = search.adjustments,
        budgetFactorPermille = round.factor,
        hardClearPermille = round.clear,
        winPermille = round.batch.winPermille,
    )
    return finish(job, catalog, "${job.missionId} ${job.difficulty.name}", variant, round.batch, tuning, search.inBand)
}

/**
 * Builds the certificate and the calibrated variant from the kept batch. Fails when no game was won,
 * because there is then no witness and no shot sample to set the stars from.
 */
private fun finish(
    job: CalibrationJob,
    catalog: pt.tiago.nightfleet.core.content.CatalogDto,
    label: String,
    tunedVariant: VariantDto,
    batch: Batch,
    tuning: TuningDto,
    inBand: Boolean,
): CalibrationResult {
    val witness = batch.witness
    if (batch.wins == 0 || witness == null) {
        return CalibrationResult.Failed(label, "no won game in ${batch.games} games, the variant cannot be calibrated")
    }
    val quantiles = quantilesOf(batch.samples)
    val stars = starsOf(quantiles)
    val variant = tunedVariant.copy(calibrated = true, stars = stars)
    val certificate = Calibration(
        id = job.certificateId,
        missionId = job.missionId,
        difficulty = job.difficulty,
        variantHash = canonicalHash(VariantDto.serializer(), variant),
        poolHash = job.poolHash,
        engineVersion = catalog.engineVersion,
        aiVersion = catalog.aiVersion,
        contentVersion = catalog.contentVersion,
        sample = SampleDto(games = batch.games, wins = batch.wins, shots = quantiles),
        thresholds = stars,
        witness = WitnessDto(
            seedHex = "%016x".format(witness.seed),
            firstPlayer = witness.firstPlayer,
            enemyLayout = witness.enemyLayout,
            actions = witness.actions.map { BattleCodec.encodeAction(it) },
            finalHash = "%016x".format(witness.finalHash),
            decoys = witness.decoys.map { it.label() },
        ),
        outOfBand = !inBand,
        tuning = tuning,
    )
    val flags = buildString {
        if (!inBand) append(" OUT-OF-BAND")
        if (tuning.budgetFactorPermille != null) append(" budget ${tunedVariant.shotBudget}")
        val clear = tuning.hardClearPermille
        if (clear != null && clear < HUNT_CLEAR_MIN_PERMILLE) append(" HARD-CLEAR ${clear / 10}%")
    }
    val line = "%s %s win %d%% stars %d/%d [tuned %d]%s".format(
        job.missionId, job.difficulty.name, batch.winPermille / 10, stars.two, stars.three, tuning.adjustments, flags,
    )
    return CalibrationResult.Done(job.missionId, job.seaNumber, job.difficulty, variant, certificate, line)
}
