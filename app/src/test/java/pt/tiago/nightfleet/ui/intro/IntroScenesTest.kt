package pt.tiago.nightfleet.ui.intro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntroScenesTest {

    @Test
    fun everyMechanicHasASceneOnASmallSampleSea() {
        for (mechanic in MechanicIntro.entries) {
            val scene = IntroScenes.of(mechanic)
            assertEquals(mechanic.name, 6, scene.model.width)
            assertEquals(mechanic.name, 6, scene.model.height)
            assertTrue(mechanic.name, scene.effects.isNotEmpty())
        }
    }

    @Test
    fun everyAnimationEndsWithinThreeSeconds() {
        for (mechanic in MechanicIntro.entries) {
            val scene = IntroScenes.of(mechanic)
            assertTrue(
                "${mechanic.name} runs for ${scene.animationMillis} ms",
                scene.animationMillis <= IntroScene.MAX_ANIMATION_MILLIS,
            )
        }
    }

    @Test
    fun loopsPauseBrieflyAfterTheAnimation() {
        for (mechanic in MechanicIntro.entries) {
            val scene = IntroScenes.of(mechanic)
            assertEquals(scene.animationMillis + IntroScene.LOOP_PAUSE_MILLIS, scene.loopMillis)
        }
    }

    @Test
    fun theTorpedoSceneShowsTheTrailThenTheHit() {
        val scene = IntroScenes.of(MechanicIntro.TORPEDO)
        val trail = scene.effects.first()
        assertEquals(pt.tiago.nightfleet.ui.board.EffectKind.TORPEDO_TRAIL, trail.kind)
        assertTrue(scene.effects.last().startMillis >= trail.durationMillis)
    }
}
