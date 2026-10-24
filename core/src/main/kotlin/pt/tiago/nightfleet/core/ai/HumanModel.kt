package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon

/**
 * Skill of a simulated human. All chances are per-mille.
 * - [parityDeviationPermille]: a hunt shot ignores the parity and goes anywhere unknown.
 * - [unnecessaryTargetPermille]: a target shot ignores the open hit and goes anywhere unknown.
 * - [weaponDelayTurns] and [weaponPermille]: from which turn a weapon is considered, and how often.
 * - [misplacedWeaponPermille]: a sonar goes to a random square instead of the best window.
 */
data class HumanSkill(
    val name: String,
    val parityDeviationPermille: Int,
    val unnecessaryTargetPermille: Int,
    val weaponDelayTurns: Int,
    val weaponPermille: Int,
    val misplacedWeaponPermille: Int,
) {
    init {
        require(parityDeviationPermille in 0..1000 && unnecessaryTargetPermille in 0..1000) { "Chances are per-mille" }
        require(weaponPermille in 0..1000 && misplacedWeaponPermille in 0..1000) { "Chances are per-mille" }
        require(weaponDelayTurns >= 0) { "weaponDelayTurns must not be negative" }
    }
}

/**
 * A simulated human player for calibration. It is an [AdmiralAI] like any other, so the
 * leveltool can play it against the admirals. It reads only the [AiView]. Its own [skill] decides
 * everything: the profile, the tier and the [AiSkill] passed in are ignored.
 *
 * Weapons: when a weapon is considered (per [HumanSkill.weaponPermille], from its delay), the player
 * uses the first charged one in this order: repair, cross (in target mode), torpedo on the line with
 * the most unknown squares, recon on the unread line closest to half unknown, sonar on the best window.
 * Salvos are N single-shot picks, each on the view where the earlier picks are hypothetical misses.
 */
class HumanModel(val skill: HumanSkill) : AdmiralAI {

    override fun decide(view: AiView, profile: AdmiralProfile, tier: AiTier, rng: SplitMix64, skill: AiSkill): AiIntent {
        require(view.firableCells().isNotEmpty()) { "No square left to fire at" }
        if (view.turn >= this.skill.weaponDelayTurns && rng.nextInt(1000) < this.skill.weaponPermille) {
            weapon(view, rng)?.let { return it }
        }
        return AiIntent.Fire(view.cell(fireCell(view, rng)))
    }

    override fun decideSalvo(
        view: AiView,
        profile: AdmiralProfile,
        tier: AiTier,
        rng: SplitMix64,
        shots: Int,
        skill: AiSkill,
    ): List<Int> {
        require(shots >= 1) { "A salvo has at least one shot" }
        var current = view
        val picked = ArrayList<Int>()
        while (picked.size < shots && current.firableCells().isNotEmpty()) {
            val cell = fireCell(current, rng)
            picked += cell
            current = current.withHypotheticalWater(cell)
        }
        return picked
    }

    /** The squares a shot goes to: the fresh ones, or every firable one when only remembered misses are left. */
    private fun pool(view: AiView): IntArray = view.freshCells().let { if (it.isEmpty()) view.firableCells() else it }

    /** One shot without weapons: hunt on parity, or work along an open hit. */
    private fun fireCell(view: AiView, rng: SplitMix64): Int {
        val unknown = pool(view)
        val w = view.width
        val h = view.height
        val hits = (0 until view.size).filter { view.kind(it) == KnownCell.HIT }
        val targets = hits.flatMap { x -> Grid.orthogonal(x, w, h).toList() }
            .filter { view.fresh(it) }
            .distinct()
            .sorted()
        if (targets.isNotEmpty()) {
            val unnecessary = rng.nextInt(1000) < skill.unnecessaryTargetPermille
            return if (unnecessary) unknown[rng.nextInt(unknown.size)] else targets[rng.nextInt(targets.size)]
        }
        val smallest = view.enemyRemaining.minOfOrNull { it.shipClass.size } ?: 1
        val parity = unknown.filter { smallest <= 1 || ((it / w) + (it % w)) % smallest == 0 }
        val deviate = rng.nextInt(1000) < skill.parityDeviationPermille
        val pool = if (deviate || parity.isEmpty()) unknown else parity.toIntArray()
        return pool[rng.nextInt(pool.size)]
    }

    /** The weapon of this turn, or null when none is charged or useful. */
    private fun weapon(view: AiView, rng: SplitMix64): AiIntent? {
        fun charged(weapon: Weapon) = (view.ownCharges[weapon] ?: 0) > 0
        val w = view.width
        val h = view.height
        val hunting = (0 until view.size).none { view.kind(it) == KnownCell.HIT }

        if (charged(Weapon.REPAIR) && !view.repairUsed) {
            for (ship in view.ownShips) {
                if (!ship.sunk && ship.shipClass.size >= 4 && ship.hits.size == ship.shipClass.size - 1) {
                    return AiIntent.Repair(view.cell(ship.hits.first()))
                }
            }
        }
        if (!hunting && charged(Weapon.CROSS_SHELL)) {
            val best = (0 until view.size)
                .map { x -> x to Grid.cross(x, w, h).count { view.fresh(it) } }
                .maxByOrNull { it.second }
            if (best != null && best.second >= 3) return AiIntent.Cross(view.cell(best.first))
        }
        if (charged(Weapon.TORPEDO)) {
            val best = torpedoLines(view).maxByOrNull { it.unknown }
            if (best != null && best.unknown >= 3) return AiIntent.Torpedo(best.isRow, best.index, best.fromStart)
        }
        if (hunting && charged(Weapon.RECON)) {
            val read = BooleanArray(2 * (w + h))
            for (r in view.readings) if (r is ReconReading) read[if (r.isRow) r.index else h + r.index] = true
            val unread = ArrayList<Pair<Boolean, Int>>()
            for (r in 0 until h) if (!read[r]) unread += true to r
            for (c in 0 until w) if (!read[h + c]) unread += false to c
            val pick = unread.minByOrNull { (isRow, index) ->
                val line = Grid.line(isRow, index, w, h)
                kotlin.math.abs(2 * line.count { view.fresh(it) } - line.size)
            }
            if (pick != null) return AiIntent.Recon(pick.first, pick.second)
        }
        if (charged(Weapon.SONAR)) {
            val unknown = pool(view)
            val centre = if (rng.nextInt(1000) < skill.misplacedWeaponPermille) {
                rng.nextInt(view.size)
            } else {
                bestWindow(view, unknown, rng)
            }
            return AiIntent.Sonar(view.cell(centre))
        }
        return null
    }

    private class LineOption(val isRow: Boolean, val index: Int, val fromStart: Boolean, val unknown: Int)

    /** Every row and column from either margin, with the unknown squares a torpedo would reach before an island. */
    private fun torpedoLines(view: AiView): List<LineOption> {
        val w = view.width
        val h = view.height
        val out = ArrayList<LineOption>()
        for (isRow in listOf(true, false)) {
            val count = if (isRow) h else w
            for (index in 0 until count) for (fromStart in listOf(true, false)) {
                var line = Grid.line(isRow, index, w, h)
                if (!fromStart) line = line.reversedArray()
                var unknown = 0
                for (x in line) {
                    if (view.kind(x) == KnownCell.ISLAND) break
                    if (view.fresh(x)) unknown++
                }
                out += LineOption(isRow, index, fromStart, unknown)
            }
        }
        return out
    }

    /** The window centre with the most unknown squares; ties are broken by [rng]. */
    private fun bestWindow(view: AiView, unknown: IntArray, rng: SplitMix64): Int {
        val isUnknown = BooleanArray(view.size)
        for (x in unknown) isUnknown[x] = true
        var best = -1
        val ties = ArrayList<Int>()
        for (centre in 0 until view.size) {
            val count = Grid.box(centre, view.width, view.height).count { isUnknown[it] }
            if (count > best) {
                best = count
                ties.clear()
            }
            if (count == best) ties += centre
        }
        return ties[rng.nextInt(ties.size)]
    }

    companion object {
        /** Calibration players for the three campaign difficulties. */
        val EASY_PLAYER = HumanModel(
            HumanSkill("easy-player", parityDeviationPermille = 400, unnecessaryTargetPermille = 200,
                weaponDelayTurns = 6, weaponPermille = 400, misplacedWeaponPermille = 500),
        )
        val MEDIUM_PLAYER = HumanModel(
            HumanSkill("medium-player", parityDeviationPermille = 200, unnecessaryTargetPermille = 100,
                weaponDelayTurns = 3, weaponPermille = 700, misplacedWeaponPermille = 300),
        )
        val HARD_PLAYER = HumanModel(
            HumanSkill("hard-player", parityDeviationPermille = 100, unnecessaryTargetPermille = 50,
                weaponDelayTurns = 1, weaponPermille = 900, misplacedWeaponPermille = 150),
        )
    }
}
