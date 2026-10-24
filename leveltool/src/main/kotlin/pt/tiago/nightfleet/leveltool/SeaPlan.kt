package pt.tiago.nightfleet.leveltool

import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.random.SplitMix64

/**
 * One planned mission of a sea: its type, the modifiers that apply to it and the weapon
 * mechanics it has. [mechs] holds only modifiers (mines, timer, salvo, diving, fog, moving, school
 * fleet); [weapons] holds weapon mechanics only.
 */
internal data class Planned(
    val sea: Int,
    /** 1..50 inside the sea. 50 is the boss. */
    val index: Int,
    val kind: MissionKind,
    val mechs: Set<Mech>,
    val weapons: Set<Mech>,
    val tutorial: String?,
)

/** Modifiers that replace a share of the duels, hunts and escorts. */
private val HALF_MODIFIERS: List<Mech> = listOf(Mech.SCHOOL, Mech.SALVO, Mech.DIVING, Mech.FOG, Mech.MOVING)

/** Modifiers that, when introduced, sit on their introduction mission (mines and the half modifiers). */
private val MODIFIER_INTROS: Set<Mech> = setOf(Mech.MINES, Mech.SCHOOL, Mech.SALVO, Mech.DIVING, Mech.FOG, Mech.MOVING)

/** Weapons introduced by a sea and then carried by every later mission. */
private val WEAPON_MECHS: Set<Mech> = setOf(Mech.SONAR, Mech.RECON, Mech.TORPEDO, Mech.DECOY, Mech.REPAIR, Mech.CROSS)

/** Keys of the ten guided tutorials of sea 1. Index 8 is the training shot (a hunt). */
internal val TUTORIAL_KEYS: List<String> = listOf(
    "tutorial.shoot", "tutorial.sink", "tutorial.place", "tutorial.hunt", "tutorial.admiral",
    "tutorial.stars", "tutorial.hint", "tutorial.training", "tutorial.formations", "tutorial.boss",
)

/**
 * The 50 missions of [sea] in order. Deterministic for a given [seed]: the types follow TYPE_COUNTS,
 * the ordering rules and the introductions of INTROS; the modifiers follow the
 * mines, timer and half-then-quarter rules. Modifiers never reach the boss.
 */
internal fun planSea(sea: Int, seed: Long): List<Planned> {
    require(sea in 1..SEAS_COUNT) { "sea must be 1..$SEAS_COUNT, was $sea" }
    val counts = TYPE_COUNTS.getValue(sea)
    val rng = SplitMix64(SplitMix64.split(seed, sea.toLong()))
    val intros = INTROS.getValue(sea)
    val introPos: Map<Mech, Int> = intros.mapIndexed { k, mech -> mech to INTRO_POSITIONS[k] }.toMap()

    // Introductions: a puzzle or an escort is the mission itself; a modifier introduced here is a duel
    // that carries it (the card shows on that mission).
    val forced = HashMap<Int, MissionKind>()
    if (sea == 1) for (i in 1..10) forced[i] = if (i == 8) MissionKind.HUNT else MissionKind.DUEL
    for ((k, mech) in intros.withIndex()) {
        val position = INTRO_POSITIONS[k]
        when (mech) {
            Mech.PUZZLE -> forced[position] = MissionKind.PUZZLE
            Mech.CONVOY -> forced[position] = MissionKind.CONVOY
            in MODIFIER_INTROS -> forced[position] = MissionKind.DUEL
            else -> Unit
        }
    }
    val kinds = orderKinds(sea, counts, forced, rng)

    // Index 0 is unused so that mission indices read directly.
    val mechs = List(MISSIONS_PER_SEA + 1) { mutableSetOf<Mech>() }
    val eligible = (1..MISSIONS_PER_SEA - 1).filter { kinds[it - 1] != MissionKind.PUZZLE }

    if (sea >= 5) {
        val introMines = introPos[Mech.MINES]
        val count = minedMissions(sea)
        val picked = pickSome(eligible.filter { it != introMines }, if (introMines != null) count - 1 else count, rng)
        picked.forEach { mechs[it] += Mech.MINES }
        introMines?.let { mechs[it] += Mech.MINES }
    }
    if (sea >= 4) {
        val introIndices = introPos.values.toSet()
        pickSome(eligible.filter { it !in introIndices }, 3, rng).forEach { mechs[it] += Mech.TIMED }
    }

    // Half of the missions of the sea that introduces a modifier, 25 % of the missions of later seas.
    val earlier = (1 until sea).flatMap { INTROS.getValue(it) }.toSet()
    // Modifiers introduced in this sea share one base list (from the first introduction on) and take
    // turns along it, so that two of them never fall on the same mission (fog and moving in sea 11).
    val introducedHere = HALF_MODIFIERS.filter { it in introPos }
    if (introducedHere.isNotEmpty()) {
        val base = eligible.filter { it >= introducedHere.minOf { m -> introPos.getValue(m) } }
        val turns = maxOf(2, introducedHere.size)
        introducedHere.forEachIndexed { k, modifier ->
            base.filterIndexed { i, _ -> i % turns == k }.forEach { mechs[it] += modifier }
            mechs[introPos.getValue(modifier)] += modifier
        }
    }
    for (modifier in HALF_MODIFIERS) {
        if (modifier !in introPos && modifier in earlier) {
            pickSome(eligible, Math.round(0.25 * eligible.size).toInt(), rng).forEach { mechs[it] += modifier }
        }
    }

    return (1..MISSIONS_PER_SEA).map { index ->
        val weapons = WEAPON_MECHS.filter { weapon ->
            weapon in earlier || (introPos[weapon]?.let { index >= it } ?: false)
        }.toSet()
        when (index) {
            MISSIONS_PER_SEA -> Planned(sea, index, MissionKind.BOSS, emptySet(), weapons, null)
            else -> Planned(
                sea = sea,
                index = index,
                kind = kinds[index - 1],
                mechs = mechs[index].toSet(),
                weapons = weapons,
                tutorial = if (sea == 1 && index <= 10) TUTORIAL_KEYS[index - 1] else null,
            )
        }
    }
}

/**
 * Types of missions 1..49 of a sea. Forced positions are fixed first; the rest is drawn at random
 * among the types still available, never with more than three equal types in a row (the tutorial of
 * sea 1 is exempt) and never two puzzles in a row in seas 2–5. Restarts a few thousand times at most.
 */
private fun orderKinds(sea: Int, counts: TypeCounts, forced: Map<Int, MissionKind>, rng: SplitMix64): List<MissionKind> {
    val total = MISSIONS_PER_SEA - 1
    val runLimit = if (sea == 1) Int.MAX_VALUE else 3
    val noPuzzlePairs = sea in 2..5
    repeat(5000) {
        val left = linkedMapOf(
            MissionKind.DUEL to counts.duel,
            MissionKind.PUZZLE to counts.puzzle,
            MissionKind.HUNT to counts.hunt,
            MissionKind.CONVOY to counts.convoy,
        )
        for (kind in forced.values) left[kind] = left.getValue(kind) - 1
        check(left.values.none { it < 0 }) { "sea $sea: forced missions exceed the counts" }
        val seq = ArrayList<MissionKind>(total)
        var ok = true
        for (position in 1..total) {
            val fixed = forced[position]
            if (fixed != null) {
                seq += fixed
            } else {
                val options = left.filter { (kind, n) -> n > 0 && allowed(seq, kind, runLimit, noPuzzlePairs) }.keys.toList()
                if (options.isEmpty()) {
                    ok = false
                    break
                }
                val pick = options[rng.nextInt(options.size)]
                left[pick] = left.getValue(pick) - 1
                seq += pick
            }
        }
        if (ok) return seq
    }
    error("sea $sea: cannot order the missions with the counts $counts")
}

private fun allowed(seq: List<MissionKind>, kind: MissionKind, runLimit: Int, noPuzzlePairs: Boolean): Boolean {
    if (noPuzzlePairs && kind == MissionKind.PUZZLE && seq.lastOrNull() == MissionKind.PUZZLE) return false
    if (seq.size >= runLimit && (1..runLimit).all { seq[seq.size - it] == kind }) return false
    return true
}

/** [n] distinct items of [items], drawn with [rng] (partial Fisher–Yates). */
private fun pickSome(items: List<Int>, n: Int, rng: SplitMix64): List<Int> {
    val pool = items.toMutableList()
    val take = minOf(n, pool.size)
    for (i in 0 until take) {
        val j = i + rng.nextInt(pool.size - i)
        val tmp = pool[i]
        pool[i] = pool[j]
        pool[j] = tmp
    }
    return pool.take(take)
}
