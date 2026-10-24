package pt.tiago.nightfleet.core.engine

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.mission.MissionType
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** Helpers for the M2 engine tests: 8 by 8 seas, labels such as "B7", and short action wrappers. */
internal object M2 {
    val SUB = Fleet("SUB", listOf(ShipClass.SUBMARINE))

    fun cell(label: String): Cell = Cell.parse(label) ?: throw AssertionError("bad label $label")

    fun idx(label: String, width: Int = 8): Int = cell(label).let { it.row * width + it.col }

    /** A sea of [Terrain.WATER] with the given squares changed to [kind]. */
    fun terrain(kind: Terrain, vararg labels: String, size: Int = 8): List<Terrain> {
        val out = MutableList(size * size) { Terrain.WATER }
        for (label in labels) out[idx(label, size)] = kind
        return out
    }

    fun dest(label: String, rot: Int = 0): Placement = Placement(ShipClass.DESTROYER, cell(label), rot)

    fun sub(label: String, rot: Int = 0): Placement = Placement(ShipClass.SUBMARINE, cell(label), rot)

    fun freighter(label: String, rot: Int = 0): Placement = Placement(ShipClass.FREIGHTER, cell(label), rot)

    fun rules(
        fleet: Fleet = Fx.PAIR,
        weapons: Map<Weapon, WeaponConfig> = emptyMap(),
        salvo: Boolean = false,
        diving: DivingRule? = null,
        fog: FogRule? = null,
        movingShips: Boolean = false,
        mines: List<List<Int>> = listOf(emptyList(), emptyList()),
        terrain: List<Terrain> = emptyList(),
        extraShips: List<List<ShipClass>> = listOf(emptyList(), emptyList()),
        convoy: pt.tiago.nightfleet.core.rules.ConvoyRule? = null,
        maxTurns: Int = 400,
    ): RuleSet = RuleSet(
        width = 8,
        height = 8,
        fleet = fleet,
        terrain = terrain,
        salvo = salvo,
        diving = diving,
        fog = fog,
        movingShips = movingShips,
        mines = mines,
        weapons = weapons,
        extraShips = extraShips,
        convoy = convoy,
        maxTurns = maxTurns,
    )

    /** Builds a started battle. Fails the test when the setup is rejected. */
    fun start(
        rules: RuleSet,
        p0: List<Placement>?,
        p1: List<Placement>?,
        decoys0: List<Cell> = emptyList(),
        decoys1: List<Cell> = emptyList(),
        first: Int = 0,
        mission: MissionType = MissionType.DUEL,
        budget: Int? = null,
    ): BattleState {
        val result = BattleSetup.create(
            rules = rules,
            mission = mission,
            placements = listOf(p0, p1),
            firstPlayer = first,
            attemptSeed = 42L,
            shotBudget = budget,
            decoys = listOf(decoys0, decoys1),
        )
        return (result as? SetupResult.Created)?.state
            ?: throw AssertionError("setup rejected: ${(result as SetupResult.Rejected).reasons}")
    }

    fun setupReasons(
        rules: RuleSet,
        p0: List<Placement>?,
        p1: List<Placement>?,
        decoys: List<List<Cell>> = listOf(emptyList(), emptyList()),
        mission: MissionType = MissionType.DUEL,
    ): List<String> {
        val result = BattleSetup.create(rules, mission, listOf(p0, p1), 0, 42L, decoys = decoys)
        return (result as? SetupResult.Rejected)?.reasons ?: emptyList()
    }

    /** The standard duel: side 0 destroyer at G8-H8, side 1 destroyer at A1-B1. */
    fun duel(rules: RuleSet = rules()): BattleState = start(rules, listOf(Fx.BOTTOM_RIGHT), listOf(Fx.TOP_LEFT))

    fun fire(state: BattleState, label: String): Outcome = Engine.apply(state, Action.Fire(cell(label)))

    fun pass(state: BattleState): Outcome = Engine.apply(state, Action.Timeout)

    /** Both sides pass [n] times each (n turns for each side). */
    fun passRounds(state: BattleState, n: Int): BattleState {
        var s = state
        repeat(2 * n) { s = pass(s).state }
        return s
    }

    fun weapon(state: BattleState, weapon: Weapon, target: WeaponTarget): Outcome =
        Engine.apply(state, Action.UseWeapon(weapon, target))

    fun cellTarget(label: String): WeaponTarget = WeaponTarget.CellTarget(cell(label))

    fun line(isRow: Boolean, index: Int, fromStart: Boolean = true): WeaponTarget =
        WeaponTarget.LineTarget(isRow, index, fromStart)

    fun reason(outcome: Outcome): String? = Fx.illegalReason(outcome)

    inline fun <reified T : Event> eventsOf(outcome: Outcome): List<T> = outcome.events.filterIsInstance<T>()

    val SONAR_ONCE = mapOf(Weapon.SONAR to WeaponConfig(initial = 1, max = 2))
    val TORPEDO_ONCE = mapOf(Weapon.TORPEDO to WeaponConfig(initial = 1, max = 1))
    val RECON_ONCE = mapOf(Weapon.RECON to WeaponConfig(initial = 1, max = 1))
    val CROSS_ONCE = mapOf(Weapon.CROSS_SHELL to WeaponConfig(initial = 1, max = 1))
}
