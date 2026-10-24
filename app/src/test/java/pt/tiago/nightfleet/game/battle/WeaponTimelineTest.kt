package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import pt.tiago.nightfleet.ui.board.EffectKind

/** The timeline of every event added with the weapons. */
class WeaponTimelineTest {

    private val width = 10
    private val viewer = 0

    /** Plays [events] for the player (side 0) and returns the steps. */
    private fun steps(vararg events: Event, action: Action? = null, reduced: Boolean = false) =
        AnimationTimeline.plan(events.toList(), width = width, viewer = viewer, reduced = reduced, action = action).steps

    @Test
    fun aSonarReadingIsAPulseOnTheEnemySeaWithItsNumber() {
        val step = steps(Event.SonarReading(actor = 0, target = 1, center = Cell(4, 4), count = 3)).single()
        assertEquals(EffectKind.SONAR_PULSE, step.enemyEffects.single().kind)
        assertEquals(listOf(44), step.enemyEffects.single().cells)
        assertTrue(step.ownEffects.isEmpty())
        assertEquals(listOf(SoundEffect.SONAR_PING), step.sounds)
        assertEquals(TimelineNotice(NoticeKind.SONAR, own = true, count = 3), step.notices.single())
    }

    @Test
    fun aSonarReadingOfTheAdmiralIsOnMySeaAndNotMine() {
        val step = steps(Event.SonarReading(actor = 1, target = 0, center = Cell(0, 0), count = 0)).single()
        assertEquals(EffectKind.SONAR_PULSE, step.ownEffects.single().kind)
        assertEquals(false, step.notices.single().own)
    }

    @Test
    fun aReconReadingShadesTheWholeLine() {
        val present = steps(Event.ReconReading(actor = 0, target = 1, isRow = true, index = 4, present = true)).single()
        val effect = present.enemyEffects.single()
        assertEquals(EffectKind.RECON_SHADOW, effect.kind)
        assertEquals((40..49).toList(), effect.cells)
        assertTrue(effect.isRow)
        assertEquals(NoticeKind.RECON_PRESENT, present.notices.single().kind)
        assertEquals(SoundEffect.RECON_PLANE, present.sounds.single())

        val empty = steps(Event.ReconReading(actor = 0, target = 1, isRow = false, index = 2, present = false)).single()
        assertEquals((2..92 step 10).toList(), empty.enemyEffects.single().cells)
        assertEquals(NoticeKind.RECON_EMPTY, empty.notices.single().kind)
    }

    @Test
    fun eachTorpedoStepLeavesATrailCell() {
        val events = listOf(
            Event.TorpedoLaunched(actor = 0, target = 1, isRow = true, index = 2, fromStart = true),
            Event.TorpedoStep(actor = 0, target = 1, cell = Cell(2, 0)),
            Event.TorpedoStep(actor = 0, target = 1, cell = Cell(2, 1)),
            Event.TorpedoTrail(actor = 0, target = 1, cells = listOf(Cell(2, 0), Cell(2, 1))),
        )
        val plan = steps(*events.toTypedArray())
        // Launch sound, two trail steps; the trail summary adds no step of its own.
        assertEquals(3, plan.size)
        assertEquals(SoundEffect.TORPEDO_RUN, plan[0].sounds.single())
        assertEquals(listOf(EffectKind.TORPEDO_TRAIL, EffectKind.TORPEDO_TRAIL), plan.drop(1).map { it.enemyEffects.single().kind })
        assertEquals(listOf(20), plan[1].enemyEffects.single().cells)
    }

    @Test
    fun aTorpedoBlockedByAnIslandStopsWithANotice() {
        val step = steps(Event.TorpedoBlocked(actor = 0, target = 1, cell = Cell(2, 3))).single()
        assertEquals(EffectKind.SPLASH, step.enemyEffects.single().kind)
        assertEquals(NoticeKind.TORPEDO_BLOCKED, step.notices.single().kind)
    }

    @Test
    fun aMineHitFlashesAndTellsThePlayerTheyLoseTheTurn() {
        val step = steps(Event.MineHit(actor = 0, target = 1, cell = Cell(5, 5))).single()
        assertEquals(EffectKind.MINE_FLASH, step.enemyEffects.single().kind)
        assertEquals(SoundEffect.MINE, step.sounds.single())
        assertEquals(HapticPattern.MINE, step.haptics.single())
        assertEquals(TimelineNotice(NoticeKind.MINE_PLAYER, own = true), step.notices.single())
    }

    @Test
    fun aMineHitByTheAdmiralIsItsOwnNotice() {
        val step = steps(Event.MineHit(actor = 1, target = 0, cell = Cell(5, 5))).single()
        assertEquals(EffectKind.MINE_FLASH, step.ownEffects.single().kind)
        assertEquals(NoticeKind.MINE_ADMIRAL, step.notices.single().kind)
    }

    @Test
    fun aSkippedTurnIsANoticeAndAPause() {
        val mine = steps(Event.TurnSkipped(side = 0)).single()
        assertEquals(NoticeKind.TURN_SKIPPED, mine.notices.single().kind)
        assertTrue(mine.notices.single().own)
        assertEquals(AnimationTimeline.NOTICE_MS, mine.durationMillis)
        assertEquals(false, steps(Event.TurnSkipped(side = 1)).single().notices.single().own)
    }

    @Test
    fun aRevealedDecoyPlaysItsSound() {
        val step = steps(Event.DecoyRevealed(actor = 0, target = 1, cell = Cell(1, 1))).single()
        assertEquals(SoundEffect.DECOY_REVEALED, step.sounds.single())
        assertEquals(NoticeKind.DECOY_REVEALED, step.notices.single().kind)
    }

    @Test
    fun aRepairOfTheEnemyPlaysTheRepairSound() {
        val step = steps(Event.EnemyRepaired(actor = 1, target = 0, cell = Cell(3, 3))).single()
        assertEquals(SoundEffect.REPAIR, step.sounds.single())
        assertEquals(NoticeKind.ENEMY_REPAIRED, step.notices.single().kind)
    }

    @Test
    fun theAdmiralMovingAShipIsAnnouncedWithoutPosition() {
        val step = steps(Event.EnemyShipMoved(actor = 1, target = 0)).single()
        assertEquals(SoundEffect.SHIP_MOVE, step.sounds.single())
        assertEquals(TimelineNotice(NoticeKind.ENEMY_MOVED, own = false), step.notices.single())
        assertTrue(step.enemyEffects.isEmpty() && step.ownEffects.isEmpty())
    }

    @Test
    fun aSubmergedContactHasNoDamageButANotice() {
        val step = steps(Event.SubmergedContact(actor = 0, target = 1, cell = Cell(6, 6))).single()
        assertEquals(NoticeKind.SUBMERGED_CONTACT, step.notices.single().kind)
        assertTrue(step.enemyEffects.isEmpty())
        assertEquals(SoundEffect.SONAR_PING, step.sounds.single())
    }

    @Test
    fun theSubmarinePhaseChangesAreNoticesWithoutTime() {
        assertEquals(NoticeKind.SUBMARINES_DIVED, steps(Event.SubmarinesDived(side = 0)).single().notices.single().kind)
        val surfaced = steps(Event.SubmarinesSurfaced(side = 1)).single()
        assertEquals(NoticeKind.SUBMARINES_SURFACED, surfaced.notices.single().kind)
        assertEquals(0L, surfaced.durationMillis)
    }

    @Test
    fun aFadingMissIsAStepThatFadesItsCells() {
        val step = steps(Event.MissFaded(side = 0, cells = listOf(Cell(0, 9), Cell(1, 9)))).single()
        assertEquals(listOf(9, 19), step.fadingCells)
        assertEquals(AnimationTimeline.FADE_MS, step.durationMillis)
        // A fade of the other side is nothing to show the player.
        assertTrue(steps(Event.MissFaded(side = 1, cells = listOf(Cell(0, 0)))).isEmpty())
    }

    @Test
    fun aChargeEarnedPulsesItsWeaponWithANotice() {
        val step = steps(Event.ChargeEarned(side = 0, weapon = Weapon.TORPEDO)).single()
        assertEquals(TimelineNotice(NoticeKind.CHARGE_EARNED, own = true, weapon = Weapon.TORPEDO), step.notices.single())
        assertTrue(steps(Event.ChargeEarned(side = 1, weapon = Weapon.TORPEDO)).isEmpty())
    }

    @Test
    fun aCrossShellDrawsItsBarrageBeforeItsFiveShots() {
        val centre = Cell(0, 0)
        val events = listOf(
            Event.Miss(actor = 0, target = 1, cell = Cell(0, 0)),
            Event.Miss(actor = 0, target = 1, cell = Cell(0, 1)),
            Event.Hit(actor = 0, target = 1, cell = Cell(1, 0)),
        )
        val plan = steps(
            *events.toTypedArray(),
            action = Action.UseWeapon(Weapon.CROSS_SHELL, WeaponTarget.CellTarget(centre)),
        )
        val barrage = plan.first()
        // The corner has three neighbours on the board: centre, down and right.
        assertEquals(listOf(0, 1, 10), barrage.enemyEffects.single().cells)
        assertEquals(EffectKind.CROSS, barrage.enemyEffects.single().kind)
        assertEquals(SoundEffect.CROSS_BARRAGE, barrage.sounds.single())
        assertEquals(NoticeKind.CROSS, barrage.notices.single().kind)
        // Then the five shots follow, one step each (the hit and the miss).
        assertEquals(4, plan.size)
    }

    @Test
    fun aCrossAwayFromTheEdgeHasFiveSquares() {
        assertEquals(5, AnimationTimeline.crossCells(Cell(4, 4), width = 10, height = 10).size)
    }

    @Test
    fun theTimelineIsUnchangedForTheClassicShots() {
        val plan = steps(Event.Miss(actor = 0, target = 1, cell = Cell(0, 0)))
        assertEquals(SoundEffect.SPLASH, plan.single().sounds.single())
        assertEquals(EffectKind.SPLASH, plan.single().enemyEffects.single().kind)
    }

    @Test
    fun theBattleEndHasItsOwnSound() {
        val won = steps(Event.BattleEnded(BattleResult(winner = 0, reason = EndReason.FLEET_SUNK))).single()
        assertEquals(SoundEffect.VICTORY, won.sounds.single())
        val lost = steps(Event.BattleEnded(BattleResult(winner = 1, reason = EndReason.FLEET_SUNK))).single()
        assertEquals(SoundEffect.DEFEAT, lost.sounds.single())
    }

    @Test
    fun reducedMotionMakesTheStepsShortFades() {
        val plan = steps(Event.SonarReading(actor = 0, target = 1, center = Cell(1, 1), count = 0), reduced = true).single()
        assertEquals(AnimationTimeline.REDUCED_STEP_MS, plan.durationMillis)
    }
}
