package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.ai.AdmiralPlacer
import pt.tiago.nightfleet.core.ai.AdmiralProfile
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.core.ai.AiView
import pt.tiago.nightfleet.core.ai.Admirals
import pt.tiago.nightfleet.core.ai.OfficerHint
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.PlacementResult
import pt.tiago.nightfleet.core.fleet.RandomPlacer
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.hash.StateHasher
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.game.freeplay.FleetChoice
import pt.tiago.nightfleet.game.freeplay.FreePlayAdmirals
import pt.tiago.nightfleet.game.freeplay.FreePlayBuilder
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.Starter

/** The admiral adapter on real battles: legality, salvo, fallback, determinism and the restore. */
class AdmiralOpponentTest {

    /** A free play config with every variant on: salvo, islands, mines, diving, fog, moving ships and all weapons. */
    private fun allVariants(level: OpponentLevel, admiralId: String): FreePlayConfig = FreePlayConfig(
        admiralId = admiralId,
        level = level,
        fleet = FleetChoice.CLASSIC,
        islands = true,
        salvo = true,
        diving = true,
        fog = true,
        movingShips = true,
        minesPerSide = 2,
        weapons = Weapon.entries.toSet(),
        starter = Starter.PLAYER,
    )

    /** The setup of a battle for [config], from the first seed of [seed0] that gives a valid one. */
    private fun setupFor(config: FreePlayConfig, seed0: Long): MatchSetup {
        for (seed in seed0 until seed0 + 200) {
            val terrain = FreePlayBuilder.terrainFor(config, seed)
            val base = FreePlayBuilder.baseRules(config, terrain)
            val placed = RandomPlacer.place(base.boardFor(0), base.fleetFor(0), SplitMix64(seed), shipsMayTouch = base.shipsMayTouch)
            val placements = (placed as? PlacementResult.Success)?.placements ?: continue
            FreePlayBuilder.finish(config, terrain, seed, placements)?.let { return it }
        }
        error("No setup for ${config.level} from seed $seed0")
    }

    private fun startOf(setup: MatchSetup): BattleMatch =
        BattleMatch((setup.create() as SetupResult.Created).state)

    /**
     * Plays a whole battle with both sides on [brain]. Every decision must be accepted by the engine exactly as
     * proposed, so a refused proposal (and the fallback it would need) fails the test.
     */
    private fun playLegally(setup: MatchSetup, brain: OpponentBrain, maxSteps: Int = 3_000): BattleMatch {
        var match = startOf(setup)
        var steps = 0
        while (!match.finished && steps++ < maxSteps) {
            val actor = match.state.current
            val observation = Observation.of(match.state, actor)
            val action = brain.decide(observation, BattleMatch.admiralRng(setup.attemptSeed, match.state.turn))
            val step = match.apply(action)
            assertTrue("Proposed $action was refused at step $steps by side $actor", step.accepted)
            match = step.match
        }
        return match
    }

    @Test
    fun easyMediumAndHardNeverProposeAnIllegalActionInSeededBattlesWithEveryVariant() {
        for (level in listOf(OpponentLevel.EASY, OpponentLevel.MEDIUM, OpponentLevel.HARD)) {
            val brain = AdmiralOpponent.forLevel("coral", level, 0L)
            for (seed in 1L..SEEDS) {
                playLegally(setupFor(allVariants(level, "coral"), seed * 977L), brain)
            }
        }
    }

    @Test
    fun relentlessNeverProposesAnIllegalActionInSeededBattles() {
        val brain = AdmiralOpponent.forLevel("nox", OpponentLevel.RELENTLESS, 0L)
        for (seed in 1L..RELENTLESS_SEEDS) {
            playLegally(setupFor(allVariants(OpponentLevel.RELENTLESS, "nox"), seed * 131L), brain)
        }
    }

    @Test
    fun everyAdmiralPlaysHardWithoutIllegalActions() {
        for (id in FreePlayAdmirals.IDS) {
            val setup = setupFor(allVariants(OpponentLevel.HARD, id), 42L)
            playLegally(setup, AdmiralOpponent.forLevel(id, OpponentLevel.HARD, setup.attemptSeed))
        }
    }

    @Test
    fun aSalvoHasTheAfloatOrUnknownSizeAndDistinctUnknownSquares() {
        val setup = setupFor(allVariants(OpponentLevel.HARD, "coral"), 5L)
        val brain = AdmiralOpponent.forLevel("coral", OpponentLevel.HARD, setup.attemptSeed)
        var match = startOf(setup)
        var admiralSalvos = 0
        var rounds = 0
        while (!match.finished && rounds++ < 60) {
            val actor = match.state.current
            val observation = Observation.of(match.state, actor)
            val action = brain.decide(observation, SplitMix64(rounds.toLong()))
            if (action is Action.Salvo) {
                val size = minOf(
                    observation.ownSea.ships.count { !it.sunk },
                    observation.enemySea.count { it.isFireable },
                )
                assertEquals(size, action.cells.size)
                assertEquals(action.cells.size, action.cells.toSet().size)
                assertTrue(action.cells.all { observation.enemySea[it.row * observation.width + it.col].isFireable })
                if (actor == 1) admiralSalvos++
            }
            val step = match.apply(action)
            assertTrue("Proposed $action was refused", step.accepted)
            match = step.match
        }
        assertTrue("the admiral sent at least one salvo", admiralSalvos > 0)
    }

    @Test
    fun theFallbackIsAFireOnTheBestUnknownSquare() {
        val setup = setupFor(allVariants(OpponentLevel.HARD, "coral"), 9L)
        val match = startOf(setup)
        val obs = Observation.of(match.state, 1)
        val brain = AdmiralOpponent.forLevel("coral", OpponentLevel.HARD, 0L)
        val fallback = brain.fallback(obs, SplitMix64(1))
        assertTrue("fallback is a Fire, got $fallback", fallback is Action.Fire)
        val cell = (fallback as Action.Fire).cell
        val index = cell.row * obs.width + cell.col
        assertEquals(KnownCell.UNKNOWN, obs.enemySea[index])
        assertEquals(OfficerHint.topCells(AiView.from(obs), 1).single(), index)
        assertTrue("the fallback is accepted", match.apply(fallback).accepted)
    }

    @Test
    fun aDecisionIsDeterministicForTheSameRandomStream() {
        val setup = setupFor(allVariants(OpponentLevel.HARD, "wolf"), 3L)
        val obs = Observation.of(startOf(setup).state, 1)
        val brain = AdmiralOpponent.forLevel("wolf", OpponentLevel.HARD, 0L)
        assertEquals(brain.decide(obs, SplitMix64(11)), brain.decide(obs, SplitMix64(11)))
    }

    @Test
    fun aRestoredBattleHasTheSameHashAsTheOriginal() {
        val setup = setupFor(allVariants(OpponentLevel.HARD, "iron"), 17L)
        val brain = AdmiralOpponent.forLevel("iron", OpponentLevel.HARD, setup.attemptSeed)
        val played = playLegally(setup, brain, maxSteps = 400)
        val saved = SetupSave.toSaved(MatchKind.FREE_PLAY, SetupSave.freeMatchId(setup.attemptSeed), setup, played, 0L)
        val restored = SetupSave.restore(saved)
        assertTrue("restore must succeed: $restored", restored is RestoreResult.Restored)
        assertEquals(
            StateHasher.hash(played.state),
            StateHasher.hash((restored as RestoreResult.Restored).match.state),
        )
    }

    @Test
    fun profilesAndTiersMapFromTheFreePlayChoices() {
        for (id in FreePlayAdmirals.IDS) {
            assertNotNull(AdmiralOpponent.profileOf(id, 0L))
        }
        assertEquals(AiTier.EASY, AdmiralOpponent.tierOf(OpponentLevel.EASY))
        assertEquals(AiTier.MEDIUM, AdmiralOpponent.tierOf(OpponentLevel.MEDIUM))
        assertEquals(AiTier.HARD, AdmiralOpponent.tierOf(OpponentLevel.HARD))
        assertEquals(AiTier.RELENTLESS, AdmiralOpponent.tierOf(OpponentLevel.RELENTLESS))
        assertEquals("shade", AdmiralOpponent.profileOf("shade", 4L).id)
        assertEquals(Admirals.CORAL, AdmiralOpponent.profileOf("unknown-id", 0L))
    }

    @Test
    fun theFreePlayFleetOfTheAdmiralIsPlacedWithItsStyle() {
        val config = allVariants(OpponentLevel.HARD, "coral")
        val base = FreePlayBuilder.baseRules(config, emptyList())
        val profile: AdmiralProfile = AdmiralOpponent.profileOf("coral", 0L)
        val placed = FreePlayBuilder.admiralPlacements(base, 99L, profile)
        assertNotNull(placed)
        val rng = SplitMix64(SplitMix64.mix(99L xor 0x4C41594FL))
        val direct = AdmiralPlacer.place(base.boardFor(1), base.fleetFor(1), profile, rng, base.shipsMayTouch)
        assertEquals((direct as PlacementResult.Success).placements, placed)
    }

    @Test
    fun theFreePlayAdmiralsAreTheTwelveOfContentSection3() {
        assertEquals(12, FreePlayAdmirals.IDS.size)
        assertEquals(Admirals.ALL.map { it.id }.toSet(), FreePlayAdmirals.IDS.toSet())
    }

    @Test
    fun theSaveKeepsTheNumberOfHintsUsed() {
        val setup = setupFor(allVariants(OpponentLevel.MEDIUM, "coral"), 21L)
        val match = startOf(setup).copy(hintsUsed = 2)
        val saved = SetupSave.toSaved(MatchKind.FREE_PLAY, "fp-test", setup, match, 0L)
        assertEquals(2, saved.hintsUsed)
        val restored = SetupSave.restore(saved)
        assertEquals(2, (restored as RestoreResult.Restored).match.hintsUsed)
    }

    companion object {
        /** Seeds of the legality test for each of EASY, MEDIUM and HARD. */
        const val SEEDS = 300L

        /** Seeds of the RELENTLESS legality test: its sampler is much slower than the density. */
        const val RELENTLESS_SEEDS = 4L
    }
}
