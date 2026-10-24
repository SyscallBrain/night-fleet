package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.core.engine.BattleResult
import pt.tiago.nightfleet.core.engine.EndReason
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.ui.board.EffectEvent
import pt.tiago.nightfleet.ui.board.EffectKind
import pt.tiago.nightfleet.ui.board.ShakeLevel

class AnimationTimelineTest {

    private val player = 0
    private val admiral = 1
    private fun c(row: Int, col: Int) = Cell(row, col)

    @Test
    fun missIsOneSplashOnTheEnemySea() {
        val plan = AnimationTimeline.plan(listOf(Event.Miss(player, admiral, c(0, 0))), 10, player, reduced = false)
        assertEquals(1, plan.steps.size)
        val step = plan.steps.single()
        assertEquals(EffectEvent.SPLASH_MS, step.durationMillis)
        assertEquals(EffectKind.SPLASH, step.enemyEffects.single().kind)
        assertTrue(step.ownEffects.isEmpty())
        assertEquals(listOf(SoundEffect.SPLASH), step.sounds)
        assertTrue(step.haptics.isEmpty())
    }

    @Test
    fun admiralHitIsAnExplosionOnMyseaWrappedInTheSeaSwap() {
        val events = listOf(Event.Hit(admiral, player, c(3, 3)))
        val plan = AnimationTimeline.plan(events, 10, player, reduced = false, admiralTurn = true)
        assertEquals(3, plan.steps.size)
        assertEquals(true, plan.steps.first().ownSeaLarge)
        assertEquals(false, plan.steps.last().ownSeaLarge)
        assertEquals(AnimationTimeline.SWAP_MS, plan.steps.first().durationMillis)
        val hit = plan.steps[1]
        assertEquals(EffectEvent.EXPLOSION_MS, hit.durationMillis)
        assertEquals(EffectKind.EXPLOSION, hit.ownEffects.single().kind)
        assertEquals(listOf(33), hit.ownEffects.single().cells)
        assertEquals(listOf(HapticPattern.OWN_SHIP_HIT), hit.haptics)
        assertEquals(ShakeLevel.HIT, hit.shake)
    }

    @Test
    fun playerHitShowsTheExplosionOnTheEnemySeaWithoutShake() {
        val plan = AnimationTimeline.plan(listOf(Event.Hit(player, admiral, c(1, 1))), 10, player, reduced = false)
        val step = plan.steps.single()
        assertEquals(EffectKind.EXPLOSION, step.enemyEffects.single().kind)
        assertEquals(listOf(HapticPattern.HIT), step.haptics)
        assertNull(step.shake)
    }

    @Test
    fun aHitThatSinksIsOneSinkStepWithItsAutoWater() {
        val events = listOf(
            Event.Hit(player, admiral, c(1, 1)),
            Event.Sunk(player, admiral, ShipClass.DESTROYER, listOf(c(1, 1), c(1, 2))),
            Event.AutoWater(player, admiral, listOf(c(0, 0), c(0, 1), c(0, 2))),
        )
        val plan = AnimationTimeline.plan(events, 10, player, reduced = false)
        assertEquals(1, plan.steps.size)
        val effect = plan.steps.single().enemyEffects.single()
        assertEquals(EffectKind.SINK, effect.kind)
        assertEquals(listOf(11, 12), effect.cells)
        assertEquals(listOf(0, 1, 2), effect.autoWater)
        assertEquals(EffectEvent.SINK_MS + 3 * EffectEvent.AUTO_WATER_STEP_MS, plan.steps.single().durationMillis)
        assertEquals(listOf(SoundEffect.SINK), plan.steps.single().sounds)
    }

    @Test
    fun admiralSinkOfMyShipShakesTheScreenWithTheSunkLevel() {
        val events = listOf(
            Event.Hit(admiral, player, c(4, 4)),
            Event.Sunk(admiral, player, ShipClass.DESTROYER, listOf(c(4, 4), c(4, 5))),
        )
        val plan = AnimationTimeline.plan(events, 10, player, reduced = false, admiralTurn = true)
        val sink = plan.steps[1]
        assertEquals(ShakeLevel.SUNK, sink.shake)
        assertEquals(listOf(HapticPattern.OWN_SHIP_HIT), sink.haptics)
        assertEquals(1, plan.steps.count { it.enemyEffects.isNotEmpty() || it.ownEffects.isNotEmpty() })
    }

    @Test
    fun swapCanBeTurnedOff() {
        val plan = AnimationTimeline.plan(
            listOf(Event.Miss(admiral, player, c(0, 0))),
            10,
            player,
            reduced = false,
            admiralTurn = true,
            swapSeas = false,
        )
        assertTrue(plan.steps.none { it.ownSeaLarge != null })
    }

    @Test
    fun reducedMotionUsesShortFadesAndNoShake() {
        val plan = AnimationTimeline.plan(
            listOf(Event.Hit(admiral, player, c(2, 2))),
            10,
            player,
            reduced = true,
            admiralTurn = true,
        )
        assertEquals(AnimationTimeline.SWAP_REDUCED_MS, plan.steps.first().durationMillis)
        val hit = plan.steps[1]
        assertEquals(AnimationTimeline.REDUCED_STEP_MS, hit.durationMillis)
        assertNull(hit.shake)
        assertEquals(listOf(SoundEffect.HIT), hit.sounds)
    }

    @Test
    fun shakeIsOffWhenTheSettingIsOff() {
        val plan = AnimationTimeline.plan(
            listOf(Event.Hit(admiral, player, c(2, 2))),
            10,
            player,
            reduced = false,
            admiralTurn = true,
            screenShake = false,
        )
        assertNull(plan.steps[1].shake)
    }

    @Test
    fun endOfBattlePlaysVictoryForThePlayerAndDefeatOtherwise() {
        val won = AnimationTimeline.plan(listOf(Event.BattleEnded(BattleResult(player, EndReason.FLEET_SUNK))), 10, player, reduced = false)
        assertEquals(listOf(SoundEffect.VICTORY), won.steps.single().sounds)
        assertEquals(AnimationTimeline.END_MS, won.steps.single().durationMillis)
        val lost = AnimationTimeline.plan(listOf(Event.BattleEnded(BattleResult(admiral, EndReason.FLEET_SUNK))), 10, player, reduced = false)
        assertEquals(listOf(SoundEffect.DEFEAT), lost.steps.single().sounds)
    }

    @Test
    fun turnChangesAddNoSteps() {
        val plan = AnimationTimeline.plan(listOf(Event.TurnStarted(player, 1)), 10, player, reduced = false)
        assertTrue(plan.steps.isEmpty())
        assertEquals(0L, plan.totalMillis)
    }

    @Test
    fun totalIsTheSumOfTheSteps() {
        val plan = AnimationTimeline.plan(
            listOf(Event.Miss(player, admiral, c(0, 0)), Event.Miss(player, admiral, c(0, 1))),
            10,
            player,
            reduced = false,
        )
        assertEquals(2 * EffectEvent.SPLASH_MS, plan.totalMillis)
    }
}
