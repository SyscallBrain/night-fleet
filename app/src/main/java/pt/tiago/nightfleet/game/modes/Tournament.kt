package pt.tiago.nightfleet.game.modes

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.FleetChoice
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.Starter
import pt.tiago.nightfleet.meta.Admirals

/**
 * A mechanic that a tournament duel can draw. [introSea] is the sea where the campaign introduces it
 *. [apply] turns the mechanic into the free play setup of one duel.
 */
enum class Mechanic(val introSea: Int) {
    ISLANDS(3),
    SONAR(4),
    MINES(5),
    RECON(5),
    SCHOOL(6),
    DECOY(7),
    TORPEDO(8),
    DIVING(9),
    REPAIR(9),
    SALVO(10),
    CROSS_SHELL(10),
    FOG(11),
    MOVING_SHIPS(11),
    ;

    fun apply(config: FreePlayConfig): FreePlayConfig = when (this) {
        ISLANDS -> config.copy(islands = true)
        SONAR -> config.withWeapon(Weapon.SONAR)
        MINES -> config.copy(minesPerSide = MINES_PER_SIDE)
        RECON -> config.withWeapon(Weapon.RECON)
        SCHOOL -> config.copy(fleet = FleetChoice.SCHOOL)
        DECOY -> config.withWeapon(Weapon.DECOY)
        TORPEDO -> config.withWeapon(Weapon.TORPEDO)
        DIVING -> config.copy(diving = true)
        REPAIR -> config.withWeapon(Weapon.REPAIR)
        SALVO -> config.copy(salvo = true)
        CROSS_SHELL -> config.withWeapon(Weapon.CROSS_SHELL)
        FOG -> config.copy(fog = true)
        MOVING_SHIPS -> config.copy(movingShips = true)
    }

    private fun FreePlayConfig.withWeapon(weapon: Weapon): FreePlayConfig = copy(weapons = weapons + weapon)

    companion object {
        const val MINES_PER_SIDE = 2

        /** Mechanics the campaign has introduced by [sea] (1..12), in the order of the enum. */
        fun introducedBy(sea: Int): List<Mechanic> = entries.filter { it.introSea <= sea }
    }
}

/** The rules of the Admirals' Tournament. Pure functions over ids and seeds. */
object TournamentRules {
    /** Admirals in a ladder. The player chooses them among those already defeated. */
    const val LADDER_SIZE = 8

    /** The admirals the player may choose: defeated in any difficulty, weakest (earliest sea) first. */
    fun eligible(profile: Profile): List<String> = profile.defeatedAdmiralIds()
        .filter { Admirals.byId(it) != null }
        .sortedBy { Admirals.byId(it)?.sea ?: Int.MAX_VALUE }

    /** How many more admirals must be defeated before a ladder can be filled. Zero when there are enough. */
    fun missing(eligibleCount: Int): Int = (LADDER_SIZE - eligibleCount).coerceAtLeast(0)

    /** The ladder of [chosen] ids, ordered weakest to strongest. Unknown or repeated ids are dropped. */
    fun ladderOf(chosen: Collection<String>): List<String> = chosen
        .toSet()
        .filter { Admirals.byId(it) != null }
        .sortedBy { Admirals.byId(it)?.sea ?: Int.MAX_VALUE }

    /**
     * The mechanics a duel may draw: the ones introduced by the strongest admiral of the ladder, plus the classic duel
     * (no mechanic), which is always possible.
     */
    fun pool(ladder: List<String>): List<Mechanic?> {
        val strongest = ladder.mapNotNull { Admirals.byId(it)?.sea }.maxOrNull() ?: 1
        return listOf<Mechanic?>(null) + Mechanic.introducedBy(strongest)
    }

    /** The rule of every round, drawn from [pool] by the tournament seed. Same seed and ladder, same rules. */
    fun drawRules(seed: Long, ladder: List<String>): List<Mechanic?> {
        val options = pool(ladder)
        return List(ladder.size) { round ->
            options[SplitMix64(SplitMix64.mix(seed xor (DRAW_SALT + round))).nextInt(options.size)]
        }
    }

    /** The seed of the duel of [round] (0-based): its layouts, the islands and who starts. */
    fun duelSeed(seed: Long, round: Int): Long = SplitMix64.mix(seed xor (DUEL_SALT + round))

    /** The free play setup of the duel of [round] of [run]: the admiral of the round, the difficulty and the rule. */
    fun duelConfig(run: TournamentRun, round: Int): FreePlayConfig {
        val rule = Mechanic.entries.firstOrNull { it.name == run.rules[round] }
        val base = FreePlayConfig(
            admiralId = run.ladder[round],
            level = levelOf(run.difficulty),
            grid = 10,
            fleet = FleetChoice.CLASSIC,
            starter = if (run.difficulty == Difficulty.HARD) Starter.RANDOM else Starter.PLAYER,
        )
        return rule?.apply(base) ?: base
    }

    fun levelOf(difficulty: Difficulty): OpponentLevel = PatrolSea.levelOf(difficulty)

    private const val DRAW_SALT = 0x44524157L
    private const val DUEL_SALT = 0x4455454CL
}

/**
 * A tournament in progress, kept between duels. [ladder] and [rules] are fixed when the tournament starts, so a later
 * change of the profile never reorders it. [round] is the index of the next duel. A loss ends the tournament.
 */
@Serializable
data class TournamentRun(
    val seed: Long,
    val difficulty: Difficulty,
    val ladder: List<String>,
    /** The rule of each round, as [Mechanic] names, or "" for the classic duel. */
    val rules: List<String>,
    val round: Int = 0,
    val wins: Int = 0,
    val ended: Boolean = false,
) {
    init {
        require(ladder.size == TournamentRules.LADDER_SIZE) { "a tournament has ${TournamentRules.LADDER_SIZE} admirals" }
        require(rules.size == ladder.size) { "one rule per round" }
        require(round in 0..ladder.size) { "round out of range" }
        require(wins in 0..round) { "wins cannot exceed the rounds played" }
    }

    /** The round to play now (1-based), or null when the tournament is over. */
    val currentRound: Int? get() = if (ended || round >= ladder.size) null else round + 1

    val champion: Boolean get() = !ended && wins == ladder.size

    /** The duel of the current round is won or lost: a win goes on, a loss ends the tournament. */
    fun afterDuel(won: Boolean): TournamentRun = when {
        !won -> copy(ended = true)
        else -> copy(round = round + 1, wins = wins + 1)
    }

    /** The id of the duel of the current round. */
    val matchId: String get() = matchId(seed, round)

    companion object {
        fun start(difficulty: Difficulty, seed: Long, chosen: Collection<String>): TournamentRun {
            val ladder = TournamentRules.ladderOf(chosen)
            return TournamentRun(
                seed = seed,
                difficulty = difficulty,
                ladder = ladder,
                rules = TournamentRules.drawRules(seed, ladder).map { it?.name.orEmpty() },
            )
        }

        /** The id of the duel of [round] (0-based) of the tournament [seed]. */
        fun matchId(seed: Long, round: Int): String = "tn-" + PatrolRun.hex(seed) + "-" + round
    }
}

/** The ids of the admirals of a profile's defeats, for the ladder screen. Empty when nobody was defeated. */
fun Profile.defeatedAdmiralIds(): Set<String> = defeatedAdmirals.values.flatten().toSet()
