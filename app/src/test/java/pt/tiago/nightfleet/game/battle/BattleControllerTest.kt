package pt.tiago.nightfleet.game.battle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.audio.HapticPattern
import pt.tiago.nightfleet.audio.SoundEffect
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.data.AdmiralSpeed
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.core.hash.ENGINE_VERSION
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.core.rules.RuleSet

/** The battle controller on real time: the turn loop, the weapons column, the shot clock and the pause. */
class BattleControllerTest {

    private class FakePorts : BattlePorts {
        val sounds = ArrayList<SoundEffect>()

        override suspend fun settings(): Settings = Settings(
            admiralSpeed = AdmiralSpeed.FAST,
            swapSeasOnAdmiralTurn = false,
            screenShake = false,
            vibration = false,
        )

        override fun reducedMotion(settings: Settings): Boolean = true

        override suspend fun persist(saved: SavedMatch) = Unit

        override suspend fun finish(match: BattleMatch, summary: QuickBattle.Summary, ui: BattleUi) = Unit

        override fun sound(effect: SoundEffect) {
            synchronized(sounds) { sounds += effect }
        }

        override fun haptic(pattern: HapticPattern) = Unit
    }

    private fun controller(
        scope: CoroutineScope,
        rules: RuleSet,
        seconds: Int? = null,
        ports: FakePorts = FakePorts(),
    ): BattleController = BattleController(
        scope = scope,
        ports = ports,
        brain = ParityBrain,
        admiralRng = { turn -> SplitMix64(turn.toLong()) },
        saveOf = { SavedMatch(matchId = "test", kind = MatchKind.FREE_PLAY, engineVersion = ENGINE_VERSION) },
        initial = WeaponFixtures.match(rules),
        shotClockSeconds = seconds,
    )

    private suspend fun BattleController.awaitPlayerTurn() {
        ui.first { it.match.playerTurn && !it.busy }
    }

    @Test
    fun aWeaponWithoutChargeIsRefusedWithItsReasonAndNothingIsAimed() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MS) {
                    val c = controller(scope, WeaponFixtures.rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(0, 2))))
                    c.start()
                    c.awaitPlayerTurn()
                    c.pickWeapon(Weapon.SONAR)
                    assertEquals("NO_CHARGE", c.ui.value.refusal)
                    assertEquals(Targeting.Idle, c.ui.value.targeting)
                    c.stop()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSalvoFiresTheSquaresPickedAndTheAdmiralAnswers() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MS) {
                    val c = controller(scope, WeaponFixtures.rules(salvo = true))
                    c.start()
                    c.awaitPlayerTurn()
                    // Five ships afloat and a hundred free squares: a salvo of five.
                    assertEquals(5, c.ui.value.salvoRequired)
                    for (col in 0 until 5) c.tap(col)
                    assertEquals(5, (c.ui.value.targeting as Targeting.Salvo).picked.size)
                    c.fireSalvo()
                    c.ui.first { it.match.actions.size >= 2 && it.match.playerTurn && !it.busy }
                    val first = c.ui.value.match.actions.first()
                    assertTrue(first is Action.Salvo)
                    assertEquals(5, (first as Action.Salvo).cells.size)
                    assertEquals(Cell(0, 4), first.cells.last())
                    c.stop()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aShotClockThatRunsOutSendsATimeoutAndTheAdmiralPlays() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MS) {
                    val ports = FakePorts()
                    val c = controller(scope, WeaponFixtures.rules(shotClockSeconds = 1), seconds = 1, ports = ports)
                    c.start()
                    c.awaitPlayerTurn()
                    c.ui.first { it.match.actions.any { action -> action == Action.Timeout } }
                    assertTrue(ports.sounds.contains(SoundEffect.CLOCK_TICK))
                    // The admiral answers the timeout and the turn comes back with the clock restarted: it runs out
                    // a second time. Waiting for that second timeout proves the restart without reading the remaining
                    // time, which a loaded machine can see already at zero.
                    val back = c.ui.first { it.match.playerTurn && it.match.actions.size >= 2 }
                    assertEquals(1_000L, back.clock.totalMs)
                    c.ui.first { state -> state.match.actions.count { it == Action.Timeout } >= 2 }
                    c.stop()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPausedClockDoesNotRunAndResumesWhereItStopped() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MS) {
                    val c = controller(scope, WeaponFixtures.rules(), seconds = 8)
                    c.start()
                    c.awaitPlayerTurn()
                    c.setPaused(true)
                    delay(PAUSE_SETTLE_MS)
                    val frozen = c.ui.value.clock.remainingMs
                    delay(PAUSE_OBSERVE_MS)
                    assertEquals(frozen, c.ui.value.clock.remainingMs)
                    c.setPaused(false)
                    delay(PAUSE_OBSERVE_MS)
                    assertTrue((c.ui.value.clock.remainingMs ?: 0L) < (frozen ?: 0L))
                    c.stop()
                }
            }
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 20_000L
        const val PAUSE_SETTLE_MS = 150L
        const val PAUSE_OBSERVE_MS = 400L
    }
}
