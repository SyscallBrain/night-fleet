package pt.tiago.nightfleet.core.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.puzzle.Grade
import pt.tiago.nightfleet.core.puzzle.PlayerGrid
import pt.tiago.nightfleet.core.puzzle.PuzzleGrade
import pt.tiago.nightfleet.core.weapons.EarnOn

/** Difficulty of a mission variant. Also the AI tier of the variant. */
@Serializable
enum class Difficulty { EASY, MEDIUM, HARD }

/** Mission types as they appear in content. */
@Serializable
enum class MissionKind { DUEL, HUNT, PUZZLE, CONVOY, BOSS }

/** Tags. Each rule tag must agree with the rules of the variants (checked by leveltool). */
@Serializable
enum class MissionTag {
    ISLANDS, REEFS, MINES, TIMED, SALVO, DIVING, FOG, MOVING, SCHOOL, LARGE,
    SONAR, TORPEDO, RECON, CROSS, DECOY, REPAIR,
}

/** Who fires first in a variant. */
@Serializable
enum class FirstPlayer { PLAYER, ADMIRAL, ALTERNATE }

/** Kinds of the daily pool. */
@Serializable
enum class DailyKind { PUZZLE, HUNT }

/** `catalog.json`: versions, the ordered mission list and the SHA-256 of every other published file. */
@Serializable
data class CatalogDto(
    val contentVersion: Int,
    val engineVersion: Int,
    val aiVersion: Int,
    /** Sea ids (`s01` … `s12`), in order. Each names `seas/<id>.json`. */
    val seas: List<String>,
    /** Mission ids in campaign order. Each names `missions/<sea>/<id>.json`. */
    val missions: List<String>,
    /** Path relative to `content/` -> SHA-256 of that file. Never lists `catalog.json` itself. */
    val files: Map<String, String>,
)

/** `seas/sNN.json`. */
@Serializable
data class SeaDto(
    val number: Int,
    val nameKey: String,
    val admiralId: String,
    val weather: String,
    val musicTrack: String,
    val introCards: List<String>,
)

/** `admirals.json`. */
@Serializable
data class AdmiralsDto(val admirals: List<AdmiralDto>)

@Serializable
data class AdmiralDto(
    val id: String,
    val number: Int,
    val nameKey: String,
    /** Placement style name. */
    val placementStyle: String,
    /** Search style name. */
    val search: String,
    /** Weapon policy name. */
    val weaponPolicy: String,
)

/** `terrains.json`. */
@Serializable
data class TerrainsDto(val terrains: List<TerrainDto>)

/** A terrain map. Rows use `.` water, `#` island and `~` reef; every row has the same length. */
@Serializable
data class TerrainDto(val id: String, val rows: List<String>)

/** `layouts/<id>.json`: certified enemy layouts of one fleet on one terrain. */
@Serializable
data class LayoutPoolDto(
    val id: String,
    /** Fleet preset name. */
    val fleet: String,
    /** Terrain id, or null for open water. */
    val terrain: String?,
    /** [canonicalHash] of the [TerrainDto] named by [terrain], or null when [terrain] is null. */
    val terrainHash: String?,
    val layouts: List<List<PlacementDto>>,
)

/** One ship of a layout. [rotation] indexes the distinct orientations of [ship]. */
@Serializable
data class PlacementDto(
    val ship: ShipClass,
    val row: Int,
    val col: Int,
    val rotation: Int,
)

/** `missions/<sea>/<id>.json`: one base mission with its three variants. */
@Serializable
data class MissionDto(
    val id: String,
    val sea: Int,
    val ordinal: Int,
    val type: MissionKind,
    val family: String,
    val tags: List<MissionTag>,
    val tutorial: String?,
    val variants: Map<Difficulty, VariantDto>,
)

@Serializable
data class VariantDto(
    val levelSeed: Long,
    val rules: RulesDto,
    val ai: AiConfigDto,
    /** Weapon charges by `Weapon` name (core.weapons). Weapons not listed cannot be used. */
    val weapons: Map<String, WeaponConfigDto> = emptyMap(),
    /** Pool of enemy layouts, `layouts/<id>.json`. Null for PUZZLE. */
    val layoutPool: String? = null,
    /** Puzzle id, `puzzles/<id>.json`. Only for PUZZLE. */
    val puzzleId: String? = null,
    val firstPlayer: FirstPlayer,
    /**
     * False while the variant waits for calibration: [stars] and [shotBudget] are then
     * null and `validate --strict` reports the variant as UNCALIBRATED.
     */
    val calibrated: Boolean = true,
    /** Star thresholds. Null until the variant is calibrated. */
    val stars: StarsDto? = null,
    /** Shots allowed in HUNT. Null otherwise, and null for an uncalibrated HUNT. */
    val shotBudget: Int? = null,
    /** Convoy parameters. Required for CONVOY, null otherwise. */
    val convoy: ConvoyDto? = null,
)

/**
 * Rules of a variant. Mirrors `RuleSet` (core/rules) field by field, except [terrain] and [mines],
 * which are content references (a terrain id and square labels such as `B7`).
 */
@Serializable
data class RulesDto(
    val width: Int = 10,
    val height: Int = 10,
    val fleet: String = "CLASSIC",
    val terrain: String? = null,
    val salvo: Boolean = false,
    val diving: DivingDto? = null,
    val fog: FogDto? = null,
    val movingShips: Boolean = false,
    /** Mine labels per side. Always two lists. */
    val mines: List<List<String>> = listOf(emptyList(), emptyList()),
    /** Ships added to each side's fleet (CONVOY adds freighters to side 0). Always two lists. */
    val extraShips: List<List<ShipClass>> = listOf(emptyList(), emptyList()),
    val shotClockSeconds: Int? = null,
    val shipsMayTouch: Boolean = false,
    val maxTurns: Int = 400,
)

@Serializable
data class DivingDto(val surfaceRounds: Int, val submergedRounds: Int)

@Serializable
data class FogDto(val missFadeTurns: Int)

@Serializable
data class AiConfigDto(
    val tier: Difficulty,
    /** Blunder rate in thousandths. Used as it is: the effective per-mille of the admiral. */
    val blunder: Int,
    /**
     * Follow-up miss in thousandths: with an open hit, the admiral ignores it this turn and hunts (0 means never,
     * at most 900). Calibrated by leveltool.
     */
    val followUpMiss: Int = 0,
    /** Turns before the admiral uses a weapon. Null takes the default of the admiral profile. 999 means never. */
    val weaponDelay: Int? = null,
    /**
     * The weapons the admiral may use, by `Weapon` name, each one also in the variant's weapons. Null
     * means all of them (the same as the player). An empty list means the admiral never fires a weapon.
     */
    val admiralWeapons: List<String>? = null,
)

/** Charges and earning rules of one weapon. Maps to `core.weapons.WeaponConfig`. */
@Serializable
data class WeaponConfigDto(
    val initial: Int,
    val max: Int,
    val earn: List<EarnDto> = emptyList(),
)

@Serializable
data class EarnDto(val on: EarnOn, val n: Int = 1)

/** Star thresholds in shots: `three <= two`. */
@Serializable
data class StarsDto(val two: Int, val three: Int)

@Serializable
data class ConvoyDto(val surviveRounds: Int, val minFreighters: Int)

/**
 * `certificates/<missionId>-<E|M|H>.json`. Two kinds that are never presented as the
 * same thing: [Proof] for puzzles, [Calibration] for battles.
 */
@Serializable
sealed class CertificateDto {
    abstract val id: String

    /**
     * Output of `PuzzleCertifier` for one puzzle: the unique solution, the solution count, the
     * technique grade and the puzzle hash. Stored by `puzzles/<id>.json` identity and re-checked by
     * `verify-certificates` with `ExactSolver`.
     */
    @Serializable
    @SerialName("PROOF")
    data class Proof(
        /** The mission variant `<missionId>-<E|M|H>`, or the puzzle id for stand-alone proofs (daily, endless). */
        override val id: String,
        val puzzleId: String,
        val puzzleHash: String,
        val solution: PlayerGrid,
        val solutionCount: Int,
        val difficulty: PuzzleGrade,
        val grade: Grade,
        val puzzleVersion: Int,
        val engineVersion: Int,
        val contentVersion: Int,
    ) : CertificateDto()

    @Serializable
    @SerialName("CALIBRATION")
    data class Calibration(
        override val id: String,
        val missionId: String,
        val difficulty: Difficulty,
        /** [canonicalHash] of the [VariantDto]. */
        val variantHash: String,
        /** [canonicalHash] of the [LayoutPoolDto]. */
        val poolHash: String,
        val engineVersion: Int,
        val aiVersion: Int,
        val contentVersion: Int,
        val sample: SampleDto,
        val thresholds: StarsDto,
        val witness: WitnessDto,
        /** True when the simulated win rate stayed outside its band after the tuning rounds. */
        val outOfBand: Boolean = false,
        /** How the kept values were reached. Null on certificates written before tuning existed. */
        val tuning: TuningDto? = null,
    ) : CertificateDto()
}

/** Record of the calibration search that produced the kept values of a variant. */
@Serializable
data class TuningDto(
    /** Measurements run, the first one included. */
    val measurements: Int,
    /** Adjustments that led to the kept values: 0 when the first measurement is kept. */
    val adjustments: Int,
    /** HUNT: budget factor of the kept budget, in per-mille (1600 is 1.60). Null otherwise. */
    val budgetFactorPermille: Int? = null,
    /** HUNT: share of the layout pool that the HARD reference clears within the kept budget, per-mille. */
    val hardClearPermille: Int? = null,
    /** Simulated win rate of the kept values, in per-mille. */
    val winPermille: Int,
    /** DUEL, CONVOY and BOSS: the kept blunder rate, in per-mille (null for HUNT). */
    val blunder: Int? = null,
    /** DUEL, CONVOY and BOSS: the kept follow-up miss, in per-mille (null for HUNT). */
    val followUpMiss: Int? = null,
    /** CONVOY: the kept survive rounds (null for the other kinds). */
    val surviveRounds: Int? = null,
    /** CONVOY: the kept freighters that must stay afloat (null for the other kinds). */
    val minFreighters: Int? = null,
    /** DUEL, CONVOY and BOSS: the kept weapon delay in turns (null for HUNT). */
    val weaponDelay: Int? = null,
    /** DUEL, CONVOY and BOSS: the effective admiral tier after any escalation (null for HUNT). */
    val tier: Difficulty? = null,
    /** DUEL, CONVOY and BOSS: the kept admiral weapons, when they were restricted (null: all of them). */
    val admiralWeapons: List<String>? = null,
)

/** Simulated-player sample. [shots] are quantiles of the shots in won games. */
@Serializable
data class SampleDto(
    val games: Int,
    val wins: Int,
    val shots: QuantilesDto,
)

@Serializable
data class QuantilesDto(val p10: Int, val p35: Int, val p50: Int, val p70: Int, val p90: Int)

/**
 * Winning witness game. [actions] are `BattleCodec` JSON encodings of `Action`, one per
 * turn, in order (every action type is replayed through `Engine.apply`). [finalHash] is the
 * StateHasher value of the state after the last action, as 16 hex digits.
 */
@Serializable
data class WitnessDto(
    val seedHex: String,
    /** 0 is the player, 1 the admiral. */
    val firstPlayer: Int,
    /** Index of the enemy layout in the pool. */
    val enemyLayout: Int,
    val actions: List<String>,
    val finalHash: String,
    /** Decoys (Iscos) of the admiral on its own sea, as cell labels. Empty when the rules have no decoy weapon. */
    val decoys: List<String> = emptyList(),
)

/** `daily/pool.json`. */
@Serializable
data class DailyPoolDto(
    val version: Int,
    val entries: List<DailyEntryDto>,
)

@Serializable
data class DailyEntryDto(
    val id: String,
    val kind: DailyKind,
    val difficulty: Difficulty,
    /** Puzzle id for PUZZLE, `<missionId>-<E|M|H>` for HUNT. */
    val ref: String,
)

/**
 * `daily/hunts.json`: the HUNT variants of the daily pool, one file for every difficulty. Each
 * hunt carries its own terrain, rules, enemy layouts and CALIBRATION certificate, so the daily pool needs no
 * mission, layout or certificate file of its own. `daily/pool.json` refers to a hunt by [DailyHuntDto.id].
 */
@Serializable
data class DailyHuntsDto(
    val version: Int,
    val hunts: List<DailyHuntDto>,
)

/** One daily hunt. [terrain] holds the rows of the terrain named by [RulesDto.terrain]. */
@Serializable
data class DailyHuntDto(
    val id: String,
    val difficulty: Difficulty,
    val levelSeed: Long,
    val terrain: List<String>,
    val rules: RulesDto,
    val layouts: List<List<PlacementDto>>,
    val calibrated: Boolean = false,
    val stars: StarsDto? = null,
    val shotBudget: Int? = null,
    val certificate: CertificateDto.Calibration? = null,
)

/**
 * The hunt as a variant of the campaign model, so the same rules, certificate and witness code apply: a HUNT
 * with no weapons, started by the player. The admiral is not used by a hunt, so its blunder is 0.
 */
fun DailyHuntDto.asVariant(): VariantDto = VariantDto(
    levelSeed = levelSeed,
    rules = rules,
    ai = AiConfigDto(tier = difficulty, blunder = 0),
    weapons = emptyMap(),
    layoutPool = null,
    puzzleId = null,
    firstPlayer = FirstPlayer.PLAYER,
    calibrated = calibrated,
    stars = stars,
    shotBudget = shotBudget,
    convoy = null,
)

/** `endless/pNN.json`: puzzles of one size, ordered by grade and steps. */
@Serializable
data class EndlessPackDto(
    val size: Int,
    val puzzles: List<String>,
)

/** `patrol/config.json`. */
@Serializable
data class PatrolConfigDto(
    val stages: List<PatrolStageDto>,
    val growthAfter: Int,
    val extraMinesPerGrowth: Int,
)

/**
 * One sea of the patrol. [terrain] is null for open water or [PatrolDefaults.ISLANDS] for islands generated from the
 * run seed. [reloadBudget] is reserved for calibration and does not change the v1 rules (see [PatrolDefaults]).
 */
@Serializable
data class PatrolStageDto(
    val ordinal: Int,
    val width: Int,
    val height: Int,
    val fleet: String,
    val terrain: String?,
    val mines: Int,
    val reloadBudget: Int,
)

/** `manifest.json` written by export-assets and read by the app: SHA-256 of every exported file. */
@Serializable
data class CatalogManifestDto(
    val format: String,
    val contentVersion: Int,
    val engineVersion: Int,
    val aiVersion: Int,
    val files: Map<String, String>,
) {
    companion object {
        const val FORMAT = "nightfleet-assets-1"
    }
}
