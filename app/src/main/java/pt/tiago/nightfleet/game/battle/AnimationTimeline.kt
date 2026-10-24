package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import pt.tiago.nightfleet.ui.board.EffectEvent
import pt.tiago.nightfleet.ui.board.EffectKind
import pt.tiago.nightfleet.ui.board.ShakeLevel

/** Words the battle screen shows in its log line when something happens that has no picture of its own. */
enum class NoticeKind {
    /** Sonar: [TimelineNotice.count] enemy squares in the 3 by 3. */
    SONAR,
    /** Reconnaissance found a surface ship in the line. */
    RECON_PRESENT,
    /** Reconnaissance found nothing in the line. */
    RECON_EMPTY,
    /** A torpedo stopped at an island. */
    TORPEDO_BLOCKED,
    /** The player hit a mine: loses the next turn. */
    MINE_PLAYER,
    /** The admiral hit a mine: loses its next turn. */
    MINE_ADMIRAL,
    /** A side lost its turn (mine or timeout). [TimelineNotice.own] tells whose. */
    TURN_SKIPPED,
    /** A decoy was revealed. */
    DECOY_REVEALED,
    /** The enemy repaired a square. */
    ENEMY_REPAIRED,
    /** The enemy moved a ship (no position). */
    ENEMY_MOVED,
    /** The player moved a ship. */
    OWN_MOVED,
    /** A submerged submarine was touched. */
    SUBMERGED_CONTACT,
    /** The submarines dived. */
    SUBMARINES_DIVED,
    /** The submarines surfaced. */
    SUBMARINES_SURFACED,
    /** A charge was earned: [TimelineNotice.weapon] pulses. */
    CHARGE_EARNED,
    /** A cross shell went off. */
    CROSS,
}

/**
 * A line of the log for one moment. [own] is true when the player is the side concerned, [weapon] names the
 * weapon of a charge, [count] is the sonar reading and [shipClass] a sunk class, when there is one.
 */
data class TimelineNotice(
    val kind: NoticeKind,
    val own: Boolean = false,
    val weapon: Weapon? = null,
    val count: Int? = null,
)

/**
 * What happens at one moment of an animation. The effects of [enemyEffects] belong to the enemy sea
 * and [ownEffects] to the player's sea; their `startMillis` are 0 because each step starts its own clock.
 * [ownSeaLarge] is set on the two swap steps of the admiral's turn.
 * [fadingCells] are misses of the player's enemy sea that fade out during this step (fog).
 */
data class TimelineStep(
    val durationMillis: Long,
    val enemyEffects: List<EffectEvent> = emptyList(),
    val ownEffects: List<EffectEvent> = emptyList(),
    val sounds: List<SoundEffect> = emptyList(),
    val haptics: List<HapticPattern> = emptyList(),
    val shake: ShakeLevel? = null,
    val ownSeaLarge: Boolean? = null,
    val notices: List<TimelineNotice> = emptyList(),
    val fadingCells: List<Int> = emptyList(),
)

/** The steps of one engine action, played in order. The input is locked until the plan ends. */
data class AnimationPlan(val steps: List<TimelineStep>) {
    val totalMillis: Long get() = steps.sumOf { it.durationMillis }
}

/**
 * Converts the events of one [pt.tiago.nightfleet.core.engine.Engine.apply] into an [AnimationPlan]
 *. Pure: the caller plays the steps, skips them
 * on "tap to skip" and throttles the sounds.
 *
 * A cross shell arrives as five ordinary shots, so the plan takes the [action] that produced them to draw
 * the barrage in front of them.
 */
object AnimationTimeline {
    const val SWAP_MS = 250L
    const val SWAP_REDUCED_MS = 150L
    /** Reduced motion: every effect becomes a fade of this length. */
    const val REDUCED_STEP_MS = 150L
    const val END_MS = 900L
    const val END_REDUCED_MS = 300L
    const val NOTICE_MS = 600L
    const val NOTICE_REDUCED_MS = 300L
    const val FADE_MS = 240L
    const val REPAIR_MS = 500L
    const val MOVE_MS = 500L
    const val CHARGE_MS = 400L
    const val CONTACT_MS = 360L
    const val DECOY_MS = 500L

    /**
     * @param viewer the side that sees the animation (the player is side 0).
     * @param admiralTurn true when the events come from the admiral's action: enables the sea swap.
     * @param swapSeas the setting "Trocar mares no turno do almirante".
     * @param screenShake the setting "Ecrã treme"; the shake is also off with [reduced] motion.
     * @param height board height; defaults to [width] for square boards.
     * @param action the action that produced [events], when known (a cross shell draws its barrage).
     */
    fun plan(
        events: List<Event>,
        width: Int,
        viewer: Int,
        reduced: Boolean,
        admiralTurn: Boolean = false,
        swapSeas: Boolean = true,
        screenShake: Boolean = true,
        seed: Long = 0L,
        height: Int = width,
        action: Action? = null,
    ): AnimationPlan {
        val steps = ArrayList<TimelineStep>()
        val swap = admiralTurn && swapSeas && events.any { it.isShot() }
        val swapMs = if (reduced) SWAP_REDUCED_MS else SWAP_MS
        if (swap) steps += TimelineStep(durationMillis = swapMs, ownSeaLarge = true)

        val shake = screenShake && !reduced
        val noticeMs = if (reduced) NOTICE_REDUCED_MS else NOTICE_MS
        val cross = (action as? Action.UseWeapon)?.takeIf { it.weapon == Weapon.CROSS_SHELL }
        if (cross != null) {
            val centre = (cross.target as? WeaponTarget.CellTarget)?.cell
            val firstShot = events.firstOrNull { it is Event.Miss || it is Event.Hit }
            val target = when (firstShot) {
                is Event.Miss -> firstShot.target
                is Event.Hit -> firstShot.target
                else -> null
            }
            if (centre != null && target != null) {
                val cells = crossCells(centre, width, height)
                steps += placed(
                    target = target,
                    viewer = viewer,
                    effect = EffectEvent(EffectKind.CROSS, cells.map { it.index(width) }, seed = seed),
                    duration = if (reduced) REDUCED_STEP_MS else EffectEvent.CROSS_STEP_MS * cells.size,
                    sounds = listOf(SoundEffect.CROSS_BARRAGE),
                    notices = listOf(TimelineNotice(NoticeKind.CROSS, own = target == viewer)),
                )
            }
        }

        var i = 0
        var index = 0L
        while (i < events.size) {
            val event = events[i]
            val next = events.getOrNull(i + 1)
            val stepSeed = seed + index * 31L
            when (event) {
                is Event.Miss -> {
                    val cell = event.cell.index(width)
                    steps += placed(
                        target = event.target,
                        viewer = viewer,
                        effect = EffectEvent(EffectKind.SPLASH, listOf(cell), seed = stepSeed),
                        duration = if (reduced) REDUCED_STEP_MS else EffectEvent.SPLASH_MS,
                        sounds = listOf(SoundEffect.SPLASH),
                    )
                }
                is Event.Hit -> {
                    // A hit that sinks is shown by the sink step alone.
                    if (next is Event.Sunk && next.target == event.target) {
                        i++
                        continue
                    }
                    val cell = event.cell.index(width)
                    steps += hitStep(event.target, viewer, cell, stepSeed, reduced, shake)
                }
                is Event.Sunk -> {
                    val hulls = event.cells.map { it.index(width) }
                    val water = (next as? Event.AutoWater)?.takeIf { it.target == event.target }
                    val waterCells = water?.cells?.map { it.index(width) }.orEmpty()
                    if (water != null) i++
                    val effect = EffectEvent(
                        kind = EffectKind.SINK,
                        cells = hulls,
                        seed = stepSeed,
                        autoWater = waterCells,
                    )
                    steps += sinkStep(event.target, viewer, effect, reduced, shake)
                }
                is Event.SonarReading -> steps += placed(
                    target = event.target,
                    viewer = viewer,
                    effect = EffectEvent(EffectKind.SONAR_PULSE, listOf(event.center.index(width)), seed = stepSeed),
                    duration = if (reduced) REDUCED_STEP_MS else EffectEvent.SONAR_MS,
                    sounds = listOf(SoundEffect.SONAR_PING),
                    notices = listOf(TimelineNotice(NoticeKind.SONAR, own = event.actor == viewer, count = event.count)),
                )
                is Event.ReconReading -> {
                    val line = lineCells(event.isRow, event.index, width, height)
                    steps += placed(
                        target = event.target,
                        viewer = viewer,
                        effect = EffectEvent(EffectKind.RECON_SHADOW, line, seed = stepSeed, isRow = event.isRow),
                        duration = if (reduced) REDUCED_STEP_MS else EffectEvent.RECON_MS,
                        sounds = listOf(SoundEffect.RECON_PLANE),
                        notices = listOf(
                            TimelineNotice(if (event.present) NoticeKind.RECON_PRESENT else NoticeKind.RECON_EMPTY, own = event.actor == viewer),
                        ),
                    )
                }
                is Event.TorpedoLaunched -> steps += TimelineStep(durationMillis = 0L, sounds = listOf(SoundEffect.TORPEDO_RUN))
                is Event.TorpedoStep -> steps += placed(
                    target = event.target,
                    viewer = viewer,
                    effect = EffectEvent(EffectKind.TORPEDO_TRAIL, listOf(event.cell.index(width)), seed = stepSeed),
                    duration = if (reduced) REDUCED_STEP_MS else EffectEvent.TRAIL_STEP_MS,
                )
                is Event.TorpedoBlocked -> steps += placed(
                    target = event.target,
                    viewer = viewer,
                    effect = EffectEvent(EffectKind.SPLASH, listOf(event.cell.index(width)), seed = stepSeed),
                    duration = if (reduced) REDUCED_STEP_MS else EffectEvent.SPLASH_MS,
                    notices = listOf(TimelineNotice(NoticeKind.TORPEDO_BLOCKED, own = event.actor == viewer)),
                )
                // The trail is drawn cell by cell by the torpedo steps above.
                is Event.TorpedoTrail -> Unit
                is Event.MineHit -> steps += placed(
                    target = event.target,
                    viewer = viewer,
                    effect = EffectEvent(EffectKind.MINE_FLASH, listOf(event.cell.index(width)), seed = stepSeed),
                    duration = if (reduced) REDUCED_STEP_MS else EffectEvent.MINE_MS,
                    sounds = listOf(SoundEffect.MINE),
                    haptics = listOf(HapticPattern.MINE),
                    notices = listOf(
                        TimelineNotice(if (event.actor == viewer) NoticeKind.MINE_PLAYER else NoticeKind.MINE_ADMIRAL, own = event.actor == viewer),
                    ),
                )
                is Event.TurnSkipped -> steps += TimelineStep(
                    durationMillis = noticeMs,
                    notices = listOf(TimelineNotice(NoticeKind.TURN_SKIPPED, own = event.side == viewer)),
                )
                is Event.DecoyRevealed -> steps += TimelineStep(
                    durationMillis = if (reduced) REDUCED_STEP_MS else DECOY_MS,
                    sounds = listOf(SoundEffect.DECOY_REVEALED),
                    notices = listOf(TimelineNotice(NoticeKind.DECOY_REVEALED, own = event.actor == viewer)),
                )
                is Event.EnemyRepaired -> steps += TimelineStep(
                    durationMillis = if (reduced) REDUCED_STEP_MS else REPAIR_MS,
                    sounds = listOf(SoundEffect.REPAIR),
                    notices = listOf(TimelineNotice(NoticeKind.ENEMY_REPAIRED, own = event.actor == viewer)),
                )
                is Event.EnemyShipMoved -> {
                    val own = event.actor == viewer
                    steps += TimelineStep(
                        durationMillis = if (reduced) REDUCED_STEP_MS else MOVE_MS,
                        sounds = listOf(SoundEffect.SHIP_MOVE),
                        notices = listOf(TimelineNotice(if (own) NoticeKind.OWN_MOVED else NoticeKind.ENEMY_MOVED, own = own)),
                    )
                }
                is Event.SubmergedContact -> steps += placed(
                    target = event.target,
                    viewer = viewer,
                    effect = null,
                    duration = if (reduced) REDUCED_STEP_MS else CONTACT_MS,
                    sounds = listOf(SoundEffect.SONAR_PING),
                    notices = listOf(TimelineNotice(NoticeKind.SUBMERGED_CONTACT, own = event.actor == viewer)),
                )
                is Event.SubmarinesDived -> steps += TimelineStep(
                    durationMillis = 0L,
                    notices = listOf(TimelineNotice(NoticeKind.SUBMARINES_DIVED, own = event.side == viewer)),
                )
                is Event.SubmarinesSurfaced -> steps += TimelineStep(
                    durationMillis = 0L,
                    notices = listOf(TimelineNotice(NoticeKind.SUBMARINES_SURFACED, own = event.side == viewer)),
                )
                is Event.MissFaded -> if (event.side == viewer) {
                    steps += TimelineStep(
                        durationMillis = if (reduced) REDUCED_STEP_MS else FADE_MS,
                        fadingCells = event.cells.map { it.index(width) },
                    )
                }
                is Event.ChargeEarned -> if (event.side == viewer) {
                    steps += TimelineStep(
                        durationMillis = if (reduced) REDUCED_STEP_MS else CHARGE_MS,
                        notices = listOf(TimelineNotice(NoticeKind.CHARGE_EARNED, own = true, weapon = event.weapon)),
                    )
                }
                is Event.BattleEnded -> {
                    val won = event.result.winner == viewer
                    steps += TimelineStep(
                        durationMillis = if (reduced) END_REDUCED_MS else END_MS,
                        sounds = listOf(if (won) SoundEffect.VICTORY else SoundEffect.DEFEAT),
                    )
                }
                // Turn changes, refusals and the ones that need no picture add no step.
                else -> Unit
            }
            i++
            index++
        }
        if (swap) steps += TimelineStep(durationMillis = swapMs, ownSeaLarge = false)
        return AnimationPlan(steps)
    }

    private fun Event.isShot(): Boolean = this is Event.Miss || this is Event.Hit || this is Event.Sunk

    private fun Cell.index(width: Int): Int = row * width + col

    /** Centre, up, right, down and left: the squares of a cross shell that are on the board. */
    internal fun crossCells(centre: Cell, width: Int, height: Int): List<Cell> =
        listOf(0 to 0, -1 to 0, 0 to 1, 1 to 0, 0 to -1)
            .map { (dr, dc) -> Cell(centre.row + dr, centre.col + dc) }
            .filter { it.row in 0 until height && it.col in 0 until width }

    /** The squares of a whole row or column, as row-major indices. */
    internal fun lineCells(isRow: Boolean, index: Int, width: Int, height: Int): List<Int> =
        if (isRow) (0 until width).map { index * width + it } else (0 until height).map { it * width + index }

    private fun hitStep(
        target: Int,
        viewer: Int,
        cell: Int,
        seed: Long,
        reduced: Boolean,
        shake: Boolean,
    ): TimelineStep {
        val own = target == viewer
        return placed(
            target = target,
            viewer = viewer,
            effect = EffectEvent(EffectKind.EXPLOSION, listOf(cell), seed = seed),
            duration = if (reduced) REDUCED_STEP_MS else EffectEvent.EXPLOSION_MS,
            sounds = listOf(SoundEffect.HIT),
            haptics = if (own) listOf(HapticPattern.OWN_SHIP_HIT) else listOf(HapticPattern.HIT),
            shake = if (own && shake) ShakeLevel.HIT else null,
        )
    }

    private fun sinkStep(
        target: Int,
        viewer: Int,
        effect: EffectEvent,
        reduced: Boolean,
        shake: Boolean,
    ): TimelineStep {
        val own = target == viewer
        return placed(
            target = target,
            viewer = viewer,
            effect = effect,
            duration = if (reduced) REDUCED_STEP_MS else effect.durationMillis,
            sounds = listOf(SoundEffect.SINK),
            haptics = if (own) listOf(HapticPattern.OWN_SHIP_HIT) else listOf(HapticPattern.SINK),
            shake = if (own && shake) ShakeLevel.SUNK else null,
        )
    }

    /**
     * Puts [effect] on the sea of [target]: the enemy sea for the player's viewpoint, the own sea otherwise.
     * A null effect is a step with no picture (the notice and the sound carry it).
     */
    private fun placed(
        target: Int,
        viewer: Int,
        effect: EffectEvent? = null,
        duration: Long,
        sounds: List<SoundEffect> = emptyList(),
        haptics: List<HapticPattern> = emptyList(),
        shake: ShakeLevel? = null,
        notices: List<TimelineNotice> = emptyList(),
    ): TimelineStep {
        val onOwnSea = target == viewer
        return TimelineStep(
            durationMillis = duration,
            enemyEffects = if (onOwnSea || effect == null) emptyList() else listOf(effect),
            ownEffects = if (onOwnSea && effect != null) listOf(effect) else emptyList(),
            sounds = sounds,
            haptics = haptics,
            shake = shake,
            notices = notices,
        )
    }
}
