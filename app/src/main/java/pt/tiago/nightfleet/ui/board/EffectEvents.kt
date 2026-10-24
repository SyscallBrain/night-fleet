package pt.tiago.nightfleet.ui.board

import kotlin.random.Random

/** The board effects. */
enum class EffectKind { SPLASH, EXPLOSION, SINK, SONAR_PULSE, TORPEDO_TRAIL, RECON_SHADOW, CROSS, MINE_FLASH }

/**
 * One effect to play on the board. [startMillis] is measured from the moment the list of events is
 * handed to [SeaBoard]; the battle screen can queue several events with increasing offsets.
 *
 * - [cells]: the cells involved (the impact, the ship's hull, the torpedo path, the recon line, the cross).
 * - [autoWater]: for [EffectKind.SINK], the automatic water cells that appear one by one after the hull.
 * - [isRow]: for [EffectKind.RECON_SHADOW], whether the line is a row (true) or a column (false).
 * - [seed]: seed of the pre-computed particles, so the same event always looks the same.
 */
data class EffectEvent(
    val kind: EffectKind,
    val cells: List<Int>,
    val startMillis: Long = 0L,
    val seed: Long = 0L,
    val autoWater: List<Int> = emptyList(),
    val isRow: Boolean = true,
) {
    init {
        require(startMillis >= 0L) { "startMillis must not be negative" }
        require(cells.isNotEmpty()) { "an effect needs at least one cell" }
    }

    /** Full length of the effect in ms (full motion), including the smoke and the auto-water steps. */
    val durationMillis: Long
        get() = when (kind) {
            EffectKind.SPLASH -> SPLASH_MS
            EffectKind.EXPLOSION -> EXPLOSION_MS + SMOKE_MS
            EffectKind.SINK -> SINK_MS + AUTO_WATER_STEP_MS * autoWater.size
            EffectKind.SONAR_PULSE -> SONAR_MS
            EffectKind.TORPEDO_TRAIL -> TRAIL_STEP_MS * cells.size
            EffectKind.RECON_SHADOW -> RECON_MS
            EffectKind.CROSS -> CROSS_STEP_MS * cells.size
            EffectKind.MINE_FLASH -> MINE_MS
        }

    companion object {
        const val SPLASH_MS = 420L
        const val EXPLOSION_MS = 480L
        const val SMOKE_MS = 2000L
        const val SINK_MS = 900L
        const val AUTO_WATER_STEP_MS = 30L
        const val SONAR_MS = 600L
        const val TRAIL_STEP_MS = 55L
        const val RECON_MS = 500L
        const val CROSS_STEP_MS = 90L
        const val MINE_MS = 500L

        /** At most three mine flashes per second. */
        const val MINE_MIN_GAP_MS = 334L
    }
}

/** One pre-computed explosion particle: direction in radians, distance in cell fractions, size in cell fractions. */
data class Particle(val angle: Float, val distance: Float, val size: Float, val hot: Boolean)

/** One smoke puff: horizontal drift and rise in cell fractions, radius in cell fractions. */
data class SmokePuff(val drift: Float, val rise: Float, val radius: Float, val delayFraction: Float)

/**
 * Deterministic particle generator: the same seed always gives the same particles, and the
 * particles are computed once, not per frame.
 */
object EffectParticles {

    /** Maximum number of explosion particles. */
    const val MAX_PARTICLES = 24

    fun explosion(seed: Long, count: Int = MAX_PARTICLES): List<Particle> {
        require(count in 1..MAX_PARTICLES) { "count must be between 1 and $MAX_PARTICLES, got $count" }
        val random = Random(seed)
        return List(count) {
            Particle(
                angle = random.nextFloat() * TWO_PI,
                distance = 0.25f + random.nextFloat() * 0.55f,
                size = 0.05f + random.nextFloat() * 0.07f,
                hot = random.nextBoolean(),
            )
        }
    }

    fun smoke(seed: Long, count: Int = 3): List<SmokePuff> {
        require(count in 1..6) { "count must be between 1 and 6, got $count" }
        val random = Random(seed xor SMOKE_SALT)
        return List(count) {
            SmokePuff(
                drift = (random.nextFloat() - 0.5f) * 0.4f,
                rise = 0.4f + random.nextFloat() * 0.5f,
                radius = 0.22f + random.nextFloat() * 0.18f,
                delayFraction = random.nextFloat() * 0.3f,
            )
        }
    }

    private const val TWO_PI = 6.2831855f
    private const val SMOKE_SALT = 0x5DEECE66DL
}

/**
 * Keeps at most three mine flashes per second: a mine flash that starts
 * less than [EffectEvent.MINE_MIN_GAP_MS] after the last accepted one is dropped. Other effects are kept.
 */
fun limitMineFlashes(events: List<EffectEvent>): List<EffectEvent> {
    var lastAccepted: Long? = null
    return events.sortedBy { it.startMillis }.filter { event ->
        if (event.kind != EffectKind.MINE_FLASH) return@filter true
        val previous = lastAccepted
        if (previous != null && event.startMillis - previous < EffectEvent.MINE_MIN_GAP_MS) return@filter false
        lastAccepted = event.startMillis
        true
    }
}
