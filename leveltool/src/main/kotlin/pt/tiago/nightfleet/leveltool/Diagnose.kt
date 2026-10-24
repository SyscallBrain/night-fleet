package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.toTerrains
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleState
import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import java.io.File

/** The rules of a diagnostic battle that can be switched off one at a time (`--ablate`). */
internal val ABLATABLE_RULES = listOf("fog", "diving", "moving", "salvo", "mines", "decoys", "weapons")

/**
 * A copy of [bf] with the named rules switched off. The layouts stay the same, so the same enemy fleets are
 * played; only the rule (and the admiral's use of its weapons, for "weapons") changes.
 */
internal fun ablated(bf: Battlefield, rules: Set<String>): Battlefield {
    var r = bf.rules
    var ai = bf.ai
    for (rule in rules) {
        r = when (rule) {
            "fog" -> r.copy(fog = null)
            "diving" -> r.copy(diving = null)
            "moving" -> r.copy(movingShips = false)
            "salvo" -> r.copy(salvo = false)
            "mines" -> r.copy(mines = listOf(emptyList(), emptyList()))
            "decoys" -> r.copy(weapons = r.weapons - Weapon.DECOY)
            "weapons" -> {
                ai = ai.copy(admiralWeapons = emptyList())
                r.copy(weapons = emptyMap())
            }
            else -> throw IllegalArgumentException("unknown rule to ablate: $rule (use ${ABLATABLE_RULES.joinToString()})")
        }
    }
    return Battlefield(bf.kind, bf.difficulty, r, bf.firstPlayer, bf.layouts, bf.admiral, bf.levelSeed, ai)
}

/** What the simulated human did in one game, counted from its own actions and the shots they produced. */
internal class GameDiagnosis(
    val index: Int,
    val winner: Int?,
    val reason: String,
    val turns: Int,
    /** Turns of the human: fire, salvo, weapon, pass and timeout. */
    val humanTurns: Int,
    /** Single-square fire actions and salvo squares: the cells the human aimed at. */
    val aimedCells: Int,
    /** Aimed cells the human had already fired at before (any result). */
    val refires: Int,
    /** Refires at a miss that had faded from the human's view (fog). */
    val fadedRefires: Int,
    /** Refires at a submerged contact. */
    val contactRefires: Int,
    /** Turns with a weapon, and turns in which the human passed with no unknown square left. */
    val weaponTurns: Int,
    val passTurns: Int,
    /** Hits, misses and contacts the human made, with its weapons included. */
    val hits: Int,
    val misses: Int,
    val contacts: Int,
    val mineHits: Int,
    /** Actions the rules refused and the fallback replaced (human side). */
    val fallbacks: Int,
    val admiralTurns: Int,
) {
    val won: Boolean get() = winner == 0
}

/** Plays [games] duels of the human against the admiral of [missionId] and prints one line per game. */
internal fun diagnoseMission(
    root: File,
    missionId: String,
    difficulty: Difficulty,
    games: Int,
    ablate: Set<String>,
    blunder: Int?,
    weaponsOff: Boolean,
    tier: Difficulty?,
    out: (String) -> Unit,
): Int {
    val set = loadContent(root)
    val terrains = set.terrains?.value?.terrains.orEmpty().associate { it.id to it.toTerrains() }
    val admirals = set.admirals?.value?.admirals.orEmpty()
    val mission = set.missions[missionId]?.value ?: throw IllegalStateException("mission $missionId is missing")
    val variant = mission.variants[difficulty] ?: throw IllegalStateException("$missionId has no ${difficulty.name} variant")
    val pool = set.pools[variant.layoutPool]?.value ?: throw IllegalStateException("layout pool ${variant.layoutPool} is missing")
    var bf = battlefieldOf(set, admirals, terrains, mission, difficulty, variant, pool.layouts)
    if (tier != null) bf = bf.escalatedTo(tier)
    bf = ablated(bf, ablate)
    val start = bf.startKnobs()
    val knobs = start.copy(blunder = blunder ?: start.blunder, weaponsOff = weaponsOff)
    val rules = bf.rulesFor(knobs)
    out(
        "diagnose: $missionId ${difficulty.name} kind=${bf.kind} grid=${rules.width}x${rules.height} fleet=${rules.fleet.name} " +
            "salvo=${rules.salvo} diving=${rules.diving != null} fog=${rules.fog?.missFadeTurns} moving=${rules.movingShips} " +
            "mines=${rules.mines.map { it.size }} weapons=${rules.weapons.keys.map { it.name }.sorted()} " +
            "tier=${bf.ai.tier} blunder=${knobs.blunder} weaponsOff=$weaponsOff ablated=${ablate.sorted()} " +
            "layouts=${bf.layouts.size} games=$games",
    )
    val diagnoses = (0 until games).map { index ->
        val seed = SplitMix64.split(bf.levelSeed, index.toLong())
        val layout = index % bf.layouts.size
        val enemy = bf.layouts[layout]
        val decoys = admiralDecoys(rules, enemy, bf.admiral, seed)
        val c = Counts()
        val trace = { before: BattleState, action: Action, after: BattleState, fellBack: Boolean ->
            c.turns++
            if (before.current == 0) c.human(before, action, after, fellBack) else c.admiralTurns++
            Unit
        }
        val record = playBattle(
            rules, MissionType.DUEL, listOf(null, enemy), firstPlayerOf(bf.firstPlayer, seed), seed, null,
            bf.duelSeats(bf.skillOf(knobs)), decoys, trace,
        )
        GameDiagnosis(
            index = index, winner = record.winner, reason = record.reason.name, turns = c.turns,
            humanTurns = c.humanTurns, aimedCells = c.aimed, refires = c.refires, fadedRefires = c.faded,
            contactRefires = c.contactRefires, weaponTurns = c.weapons, passTurns = c.passes, hits = c.hits,
            misses = c.misses, contacts = c.contacts, mineHits = c.mines, fallbacks = c.fallbacks, admiralTurns = c.admiralTurns,
        ).also { out(it.line()) }
    }
    val wins = diagnoses.count { it.won }
    val reasons = diagnoses.groupingBy { it.reason }.eachCount().toSortedMap()
    val winTurns = diagnoses.filter { it.won }.map { it.humanTurns }
    fun mean(values: (GameDiagnosis) -> Int): Int = diagnoses.sumOf(values) / games
    out(
        "diagnose summary: human won $wins/$games (${wins * 100 / games}%), end reasons $reasons, " +
            "human turns in wins ${if (winTurns.isEmpty()) "-" else (winTurns.sum() / winTurns.size).toString()}, " +
            "mean turns ${mean { it.turns }}, mean human turns ${mean { it.humanTurns }}, mean aimed ${mean { it.aimedCells }}, " +
            "mean refires ${mean { it.refires }}, mean faded refires ${mean { it.fadedRefires }}, " +
            "mean contact refires ${mean { it.contactRefires }}, mean passes ${mean { it.passTurns }}, " +
            "mean weapon turns ${mean { it.weaponTurns }}, mean fallbacks ${mean { it.fallbacks }}",
    )
    return wins
}

/** Mutable counters of one game, filled by the trace of [playBattle]. */
private class Counts {
    var turns = 0
    var humanTurns = 0
    var admiralTurns = 0
    var aimed = 0
    var refires = 0
    var faded = 0
    var contactRefires = 0
    var weapons = 0
    var passes = 0
    var hits = 0
    var misses = 0
    var contacts = 0
    var mines = 0
    var fallbacks = 0

    fun human(before: BattleState, action: Action, after: BattleState, fellBack: Boolean) {
        humanTurns++
        if (fellBack) fallbacks++
        val prior = before.sides[1].incoming
        val fog = before.rules.fog
        val targets = when (action) {
            is Action.Fire -> listOf(action.cell)
            is Action.Salvo -> action.cells
            else -> emptyList()
        }
        when (action) {
            is Action.UseWeapon -> weapons++
            is Action.Timeout -> passes++
            else -> Unit
        }
        for (cell in targets) {
            aimed++
            val index = before.sides[1].board.index(cell)
            val earlier = prior.entries.filter { it.attacker == 0 && it.cell == index }
            if (earlier.isEmpty()) continue
            refires++
            val last = earlier.last()
            if (last.result == ShotResult.Contact) contactRefires++
            val wasMiss = last.result == ShotResult.Miss || last.result == ShotResult.Trail
            if (fog != null && wasMiss && before.sides[0].turnsTaken - last.ownTurn - 1 >= fog.missFadeTurns) faded++
        }
        for (record in after.sides[1].incoming.entries.drop(prior.entries.size)) {
            if (record.attacker != 0) continue
            when (record.result) {
                ShotResult.Hit -> hits++
                is ShotResult.Sunk -> hits++
                ShotResult.Miss, ShotResult.Trail -> misses++
                ShotResult.Contact -> contacts++
                ShotResult.MineHit -> mines++
                else -> Unit
            }
        }
    }
}

private fun GameDiagnosis.line(): String = (
    "game %3d winner %-6s reason %-13s turns %3d human turns %3d aimed %3d refires %3d faded %3d contact-refire %2d " +
        "weapons %2d passes %2d | hits %2d misses %2d contacts %2d mines %d | fallbacks %d admiral turns %3d"
    ).format(
        index, winner?.let { if (it == 0) "human" else "admiral" } ?: "draw", reason, turns, humanTurns, aimedCells,
        refires, fadedRefires, contactRefires, weaponTurns, passTurns, hits, misses, contacts, mineHits, fallbacks, admiralTurns,
    )
