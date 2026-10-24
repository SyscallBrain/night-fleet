package pt.tiago.nightfleet.game.battle

import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.engine.Event
import pt.tiago.nightfleet.core.engine.SetupResult
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/** Engine-backed battles for the weapon tests: the fixed layout of [BattleFixtures] on a 10 by 10 board. */
object WeaponFixtures {

    fun rules(
        weapons: Map<Weapon, WeaponConfig> = emptyMap(),
        salvo: Boolean = false,
        movingShips: Boolean = false,
        fog: FogRule? = null,
        mines: List<List<Int>> = listOf(emptyList(), emptyList()),
        shotClockSeconds: Int? = null,
    ): RuleSet = RuleSet(
        weapons = weapons,
        salvo = salvo,
        movingShips = movingShips,
        fog = fog,
        mines = mines,
        shotClockSeconds = shotClockSeconds,
    )

    fun match(
        rules: RuleSet,
        firstPlayer: Int = 0,
        decoys: List<List<Cell>> = listOf(emptyList(), emptyList()),
        mission: MissionType = MissionType.DUEL,
    ): BattleMatch {
        val setup = MatchSetup(
            rules = rules,
            mission = mission,
            attemptSeed = BattleFixtures.SEED,
            placements = listOf(BattleFixtures.fixedPlacements(), BattleFixtures.fixedPlacements()),
            decoys = decoys,
            firstPlayer = firstPlayer,
        ).create()
        return BattleMatch((setup as SetupResult.Created).state)
    }

    /** Applies [action] and insists it was accepted. */
    fun BattleMatch.act(action: Action): BattleMatch {
        val step = apply(action)
        check(step.accepted) { "Action refused: ${step.events.filterIsInstance<Event.IllegalAction>()}" }
        return step.match
    }

    fun cell(row: Int, col: Int) = Cell(row, col)
}
