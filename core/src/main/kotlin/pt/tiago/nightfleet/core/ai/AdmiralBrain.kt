package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.engine.ShotResult
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.PlacementValidator
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.random.SplitMix64
import kotlin.math.abs

/**
 * The admirals of the campaign: EASY, MEDIUM, HARD and RELENTLESS. Every decision is
 * computed from the [AiView] with integer arithmetic, a fixed candidate order and ties broken by [rng].
 */
object AdmiralBrain : AdmiralAI {

    /** Recent enemy shots that make an own ship a candidate to move. */
    private const val RECENT_TURNS = 4
    private const val RECENT_NEAR_MIN = 3
    private const val SONAR_MIN_UNKNOWN = 5
    private const val TORPEDO_MIN_UNKNOWN = 4
    private const val CROSS_MIN_UNKNOWN = 3
    private const val REPAIR_MIN_LENGTH = 4
    private const val MOVE_MIN_LENGTH = 3
    private const val ADAPTIVE_SWITCH_TURN = 20
    private const val BIAS_MAX = 15L
    private const val HALF = Density.SCALE / 2

    override fun decide(view: AiView, profile: AdmiralProfile, tier: AiTier, rng: SplitMix64, skill: AiSkill): AiIntent {
        require(view.firableCells().isNotEmpty()) { "No square left to fire at" }
        val turn = Turn(view, profile, tier, rng, forceHunt = forcesHunt(view, skill, rng), weapons = weaponsOf(profile, skill))
        if (view.turn >= skill.weaponDelayTurns) {
            turn.weapon()?.let { return it }
        }
        return AiIntent.Fire(view.cell(shot(turn, skill)))
    }

    /**
     * Whether this turn ignores the open hits: only when some exist and the skill asks for it. The roll
     * consumes the rng only in that case, so the default skill keeps every decision of earlier versions.
     */
    private fun forcesHunt(view: AiView, skill: AiSkill, rng: SplitMix64): Boolean {
        if (skill.followUpMissPermille == 0) return false
        if ((0 until view.size).none { view.kind(it) == KnownCell.HIT }) return false
        return rng.nextInt(1000) < skill.followUpMissPermille
    }

    /** The square of one shot: the tier's choice, replaced by a random unknown one with the skill's blunder chance. */
    private fun shot(turn: Turn, skill: AiSkill): Int {
        var cell = turn.chooseCell()
        val blunder = skill.blunderPermille
        if (blunder > 0 && turn.rng.nextInt(1000) < blunder) cell = turn.randomUnknown()
        return cell
    }

    /**
     * A salvo of up to [shots] distinct squares (rule Salva). Each pick is the tier's own shot
     * choice on the view where the earlier picks are hypothetical misses (conditional density), so the
     * salvo spreads out instead of piling onto one ship.
     */
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
            val turn = Turn(current, profile, tier, rng, forceHunt = forcesHunt(current, skill, rng), weapons = weaponsOf(profile, skill))
            val cell = shot(turn, skill)
            picked += cell
            current = current.withHypotheticalWater(cell)
        }
        return picked
    }

    /** The weapons of a decision: the profile's set, restricted to the skill's set when it has one. */
    internal fun weaponsOf(profile: AdmiralProfile, skill: AiSkill): Set<Weapon> =
        skill.admiralWeapons?.let { allowed -> profile.weaponsAllowed.filter { it in allowed }.toSet() } ?: profile.weaponsAllowed

    /** Chance, in per-mille, that a chosen square is replaced by a random unknown one. */
    internal fun blunderPermille(profile: AdmiralProfile, tier: AiTier): Int = when (tier) {
        AiTier.EASY -> profile.blunderBase
        AiTier.MEDIUM -> profile.blunderBase * 60 / 100
        AiTier.HARD -> profile.blunderBase * 25 / 100
        AiTier.RELENTLESS -> 0
    }

    /** Chance that a triggered weapon policy is used. The free-play admiral never holds back. */
    private fun aggression(profile: AdmiralProfile, tier: AiTier): Int =
        if (tier == AiTier.RELENTLESS) 1000 else profile.weaponAggression

    /**
     * Preference score of a square when there are no better reasons, in 0..[BIAS_MAX].
     * It only breaks ties between squares that the density (or the sampler) rates equally.
     */
    internal fun biasScore(view: AiView, bias: HuntBias, parityPhase: Int, x: Int): Long {
        val w = view.width
        val h = view.height
        val r = x / w
        val c = x % w
        return when (bias) {
            HuntBias.PARITY -> if ((r + c + parityPhase) % 2 == 0) BIAS_MAX else 0L
            HuntBias.CENTRE_SPIRAL -> maxOf(0, BIAS_MAX.toInt() - Grid.centreDistance(x, w, h) / 2).toLong()
            HuntBias.EDGES_FIRST -> if (Grid.isEdge(x, w, h)) BIAS_MAX else 0L
            HuntBias.DIAGONALS -> if (r == c || r + c == w - 1) BIAS_MAX else 0L
            HuntBias.ADAPTIVE -> biasScore(
                view,
                if (view.turn < ADAPTIVE_SWITCH_TURN) HuntBias.CENTRE_SPIRAL else HuntBias.EDGES_FIRST,
                parityPhase,
                x,
            )
            HuntBias.RANDOM -> 0L
        }
    }

    /** The state of one decision: the knowledge, the candidates and the lazily computed estimates. */
    private class Turn(
        val view: AiView,
        val profile: AdmiralProfile,
        val tier: AiTier,
        val rng: SplitMix64,
        /** The admiral ignores the open hits this turn (follow-up miss): it hunts as if none existed. */
        val forceHunt: Boolean = false,
        /** The weapons this admiral may use this turn: the profile's, restricted by the skill. */
        val weapons: Set<Weapon> = profile.weaponsAllowed,
    ) {
        val k = Knowledge(view)
        val n = k.n
        val w = view.width
        val h = view.height
        val unknown: IntArray = (0 until n).filter { k.unknown[it] }.toIntArray().let { if (it.isEmpty()) view.firableCells() else it }
        val hunting = forceHunt || k.openHits.isEmpty()
        private var densityCache: Density? = null

        fun randomUnknown(): Int = unknown[rng.nextInt(unknown.size)]

        fun density(): Density = densityCache ?: Density.forKnowledge(k, targetMode = !hunting).also { densityCache = it }

        private fun charged(weapon: Weapon): Boolean = (view.ownCharges[weapon] ?: 0) > 0

        private fun allowed(weapon: Weapon): Boolean = weapon in weapons && charged(weapon)

        private fun roll(): Boolean = rng.nextInt(1000) < aggression(profile, tier)

        private fun bias(x: Int): Long = biasScore(view, profile.huntBias, profile.parityPhase, x)

        /** Picks the candidate with the highest value; ties are broken by [rng] over ascending candidates. */
        private fun pickBest(candidates: IntArray, value: (Int) -> Long): Int {
            var best = Long.MIN_VALUE
            val ties = IntArray(candidates.size)
            var count = 0
            for (x in candidates) {
                val v = value(x)
                if (v > best) {
                    best = v
                    count = 0
                }
                if (v == best) ties[count++] = x
            }
            return ties[rng.nextInt(count)]
        }

        fun chooseCell(): Int = when (tier) {
            AiTier.EASY -> easyCell()
            AiTier.MEDIUM -> mediumCell()
            AiTier.HARD -> densityCell()
            AiTier.RELENTLESS -> sampledCell()
        }

        private fun targetCandidates(): IntArray {
            val mark = BooleanArray(n)
            for (x in k.openHits) for (y in Grid.orthogonal(x, w, h)) if (k.unknown[y]) mark[y] = true
            return (0 until n).filter { mark[it] }.toIntArray()
        }

        private fun easyCell(): Int {
            val targets = if (hunting) IntArray(0) else targetCandidates()
            return if (targets.isNotEmpty()) targets[rng.nextInt(targets.size)] else randomUnknown()
        }

        private fun mediumCell(): Int {
            if (!hunting) {
                // Extend a line of two hits first (score 2), then any orthogonal neighbour (score 1).
                val score = LongArray(n)
                for (x in k.openHits) {
                    val r = x / w
                    val c = x % w
                    for ((dr, dc) in DIRECTIONS) {
                        val rr = r + dr
                        val cc = c + dc
                        if (rr !in 0 until h || cc !in 0 until w) continue
                        val y = rr * w + cc
                        if (!k.unknown[y]) continue
                        val br = r - dr
                        val bc = c - dc
                        val behindHit = br in 0 until h && bc in 0 until w && k.hit[br * w + bc]
                        score[y] = maxOf(score[y], if (behindHit) 2L else 1L)
                    }
                }
                return pickBest(unknown) { score[it] }
            }
            val smallest = view.enemyRemaining.minOfOrNull { it.shipClass.size } ?: 1
            val phase = profile.parityPhase
            val parity = unknown.filter { smallest <= 1 || ((it / w) + (it % w) + phase) % smallest == 0 }.toIntArray()
            val pool = if (parity.isEmpty()) unknown else parity
            val preferred = pool.filter { bias(it) == BIAS_MAX }.toIntArray()
            val choices = if (preferred.isEmpty()) pool else preferred
            return choices[rng.nextInt(choices.size)]
        }

        private fun densityCell(): Int {
            val d = density()
            if (d.total == 0L) {
                AiStats.recordDensityFallback()
                return randomUnknown()
            }
            return pickBest(unknown) { d.score[it] * 16 + bias(it) }
        }

        private fun sampledCell(): Int {
            if (forceHunt) return densityCell()
            // The sampler draws from its own stream, so a fallback to the density consumes the decision rng exactly as HARD does.
            val fleets = FleetSampler.sample(k, SplitMix64(SplitMix64.mix(rng.state)))
            if (fleets == null) {
                AiStats.recordSamplerFallback()
                return densityCell()
            }
            return pickBest(unknown) { fleets.freq[it] * 16 + bias(it) }
        }

        // ---- Weapons ------------------------------------------------------------

        fun weapon(): AiIntent? = when (tier) {
            AiTier.EASY -> easyWeapon()
            AiTier.MEDIUM -> mediumWeapon()
            AiTier.HARD, AiTier.RELENTLESS -> densityWeapon()
        }

        /** EASY: sonar only, at random. */
        private fun easyWeapon(): AiIntent? {
            if (!allowed(Weapon.SONAR) || !roll()) return null
            return AiIntent.Sonar(view.cell(rng.nextInt(n)))
        }

        /** MEDIUM: sonar on the window with most unknown squares, torpedo on the line with most. */
        private fun mediumWeapon(): AiIntent? {
            val unit = LongArray(n) { if (k.unknown[it]) 1000L else 0L }
            if (allowed(Weapon.SONAR)) {
                val center = bestWindow(unit)
                if (center != null && windowSum(unit, center) >= SONAR_MIN_UNKNOWN * 1000L && roll()) {
                    return AiIntent.Sonar(view.cell(center))
                }
            }
            if (allowed(Weapon.TORPEDO)) {
                val line = bestLine(unit)
                if (line != null && line.sum >= TORPEDO_MIN_UNKNOWN * 1000L && roll()) {
                    return AiIntent.Torpedo(line.isRow, line.index, line.fromStart)
                }
            }
            return null
        }

        private fun densityWeapon(): AiIntent? {
            ownSeaWeapon()?.let { return it }
            val d = density()
            if (d.total == 0L) return null
            val total = d.total

            if (!hunting && allowed(Weapon.CROSS_SHELL)) {
                val center = bestCross(d.score)
                if (center != null && roll()) return AiIntent.Cross(view.cell(center))
            }
            if (hunting && allowed(Weapon.SONAR)) {
                val center = bestWindow(d.score)
                if (center != null && windowSum(d.score, center) * 1000 >= profile.sonarThreshold * total && roll()) {
                    return AiIntent.Sonar(view.cell(center))
                }
            }
            if (allowed(Weapon.TORPEDO)) {
                val line = bestLine(d.score)
                if (line != null && line.sum * 1000 >= profile.torpedoThreshold * total && roll()) {
                    return AiIntent.Torpedo(line.isRow, line.index, line.fromStart)
                }
            }
            if (hunting && allowed(Weapon.RECON)) {
                val line = reconLine(d)
                if (line != null && roll()) return AiIntent.Recon(line.first, line.second)
            }
            return null
        }

        /** Repair (a ship one hit from sunk, length 4 or 5) and move, which both act on the own sea. */
        private fun ownSeaWeapon(): AiIntent? {
            if (allowed(Weapon.REPAIR) && !view.repairUsed) {
                for (ship in view.ownShips) {
                    if (ship.sunk || ship.shipClass.size < REPAIR_MIN_LENGTH) continue
                    if (ship.hits.size == ship.shipClass.size - 1) {
                        return AiIntent.Repair(view.cell(ship.hits.first()))
                    }
                }
            }
            if (view.rules.movingShips && !view.moveUsed) return moveIntent()
            return null
        }

        private fun moveIntent(): AiIntent? {
            val allShots = BooleanArray(n)
            val recentShots = BooleanArray(n)
            for (record in view.ownIncoming.entries) {
                if (record.result == ShotResult.AutoWater) continue
                allShots[record.cell] = true
                if (record.turn >= view.turn - RECENT_TURNS) recentShots[record.cell] = true
            }
            val order = view.ownShips.indices
                .filter { i ->
                    val ship = view.ownShips[i]
                    !ship.sunk && ship.hits.isEmpty() && ship.shipClass.size >= MOVE_MIN_LENGTH && lineAxis(ship.placement) != null
                }
                .sortedWith(compareBy({ -view.ownShips[it].shipClass.size }, { it }))
            for (i in order) {
                val ship = view.ownShips[i]
                val squares = ship.placement.cells().map { view.index(it) }
                val near = squares.count { x -> Grid.neighbours8(x, w, h).any { recentShots[it] } }
                if (near < RECENT_NEAR_MIN) continue
                val horizontal = lineAxis(ship.placement) ?: continue
                val options = ArrayList<Pair<Int, Int>>()
                for (delta in intArrayOf(-1, 1)) {
                    val moved = shift(ship.placement, horizontal, delta)
                    val placements = view.ownShips.mapIndexed { j, s -> if (j == i) moved else s.placement }
                    if (PlacementValidator.validate(view.ownBoard, view.rules.fleet, placements, view.shipsMayTouch).isNotEmpty()) continue
                    val exposure = moved.cells().map { view.index(it) }.count { x ->
                        allShots[x] || Grid.neighbours8(x, w, h).any { allShots[it] }
                    }
                    options += exposure to delta
                }
                if (options.isEmpty()) continue
                val least = options.minOf { it.first }
                val ties = options.filter { it.first == least }
                return AiIntent.Move(i, ties[rng.nextInt(ties.size)].second)
            }
            return null
        }

        /** True when the ship lies along a row, false along a column, null for shapes that are not a straight line. */
        private fun lineAxis(placement: Placement): Boolean? {
            val cells = placement.cells()
            if (cells.size < 2) return null
            return when {
                cells.all { it.row == cells[0].row } -> true
                cells.all { it.col == cells[0].col } -> false
                else -> null
            }
        }

        private fun shift(placement: Placement, horizontal: Boolean, delta: Int): Placement {
            val origin = placement.origin
            val moved = if (horizontal) Cell(origin.row, origin.col + delta) else Cell(origin.row + delta, origin.col)
            return Placement(placement.shipClass, moved, placement.rotationIndex)
        }

        private fun windowSum(values: LongArray, center: Int): Long = Grid.box(center, w, h).sumOf { values[it] }

        /** The 3 by 3 window centre with the largest sum, among centres not sounded before. */
        private fun bestWindow(values: LongArray): Int? {
            val sounded = BooleanArray(n)
            for (s in k.sonar) sounded[s.center] = true
            val candidates = (0 until n).filter { !sounded[it] }.toIntArray()
            if (candidates.isEmpty()) return null
            return pickBest(candidates) { windowSum(values, it) }
        }

        private fun crossSum(values: LongArray, center: Int): Long {
            val cross = Grid.cross(center, w, h)
            if (cross.count { k.unknown[it] } < CROSS_MIN_UNKNOWN) return -1L
            return cross.sumOf { values[it] }
        }

        private fun bestCross(values: LongArray): Int? {
            val all = IntArray(n) { it }
            val center = pickBest(all) { crossSum(values, it) }
            return if (crossSum(values, center) < 0L) null else center
        }

        /** A line (row or column) and the way a torpedo would travel along it, to its first island. */
        private class LineChoice(val isRow: Boolean, val index: Int, val fromStart: Boolean, val sum: Long)

        private fun lineSquares(isRow: Boolean, index: Int, fromStart: Boolean): IntArray {
            val line = Grid.line(isRow, index, w, h)
            return if (fromStart) line else line.reversedArray()
        }

        private fun torpedoSum(values: LongArray, isRow: Boolean, index: Int, fromStart: Boolean): Long {
            var sum = 0L
            for (x in lineSquares(isRow, index, fromStart)) {
                if (view.kind(x) == KnownCell.ISLAND) break
                sum += values[x]
            }
            return sum
        }

        private fun bestLine(values: LongArray): LineChoice? {
            // Every row and column, from either margin: 2 * (w + h) candidates, encoded by [encode].
            val candidates = IntArray(2 * (w + h))
            var count = 0
            for (r in 0 until h) for (start in listOf(true, false)) candidates[count++] = encode(true, r, start)
            for (c in 0 until w) for (start in listOf(true, false)) candidates[count++] = encode(false, c, start)
            val best = pickBest(candidates) { id ->
                val (isRow, index, fromStart) = decode(id)
                torpedoSum(values, isRow, index, fromStart)
            }
            val (isRow, index, fromStart) = decode(best)
            return LineChoice(isRow, index, fromStart, torpedoSum(values, isRow, index, fromStart))
        }

        /** Candidate id: `index * 4 + kind`, where kind bit 2 means row and bit 1 means from the start. */
        private fun encode(isRow: Boolean, index: Int, fromStart: Boolean): Int =
            index * 4 + (if (isRow) 2 else 0) + (if (fromStart) 1 else 0)

        private fun decode(id: Int): Triple<Boolean, Int, Boolean> =
            Triple((id and 2) != 0, id / 4, (id and 1) != 0)

        /** The line whose probability of holding a ship is closest to one half, among those not read yet. */
        private fun reconLine(d: Density): Pair<Boolean, Int>? {
            val read = BooleanArray(2 * (w + h))
            for (reading in view.readings) {
                if (reading is ReconReading) read[if (reading.isRow) reading.index else h + reading.index] = true
            }
            val ids = ArrayList<Int>()
            for (r in 0 until h) if (!read[r]) ids += r * 2
            for (c in 0 until w) if (!read[h + c]) ids += c * 2 + 1
            if (ids.isEmpty()) return null
            val best = pickBest(ids.toIntArray()) { id ->
                val p = if (id % 2 == 0) d.rowP[id / 2] else d.colP[id / 2]
                -abs(p - HALF)
            }
            return (best % 2 == 0) to (best / 2)
        }

        companion object {
            private val DIRECTIONS = arrayOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)
        }
    }
}
