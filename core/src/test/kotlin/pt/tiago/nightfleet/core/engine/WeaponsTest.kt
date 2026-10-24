package pt.tiago.nightfleet.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.M2.SONAR_ONCE
import pt.tiago.nightfleet.core.engine.M2.TORPEDO_ONCE
import pt.tiago.nightfleet.core.engine.M2.RECON_ONCE
import pt.tiago.nightfleet.core.engine.M2.CROSS_ONCE
import pt.tiago.nightfleet.core.engine.M2.cell
import pt.tiago.nightfleet.core.engine.M2.cellTarget
import pt.tiago.nightfleet.core.engine.M2.dest
import pt.tiago.nightfleet.core.engine.M2.eventsOf
import pt.tiago.nightfleet.core.engine.M2.fire
import pt.tiago.nightfleet.core.engine.M2.line
import pt.tiago.nightfleet.core.engine.M2.pass
import pt.tiago.nightfleet.core.engine.M2.passRounds
import pt.tiago.nightfleet.core.engine.M2.reason
import pt.tiago.nightfleet.core.engine.M2.rules
import pt.tiago.nightfleet.core.engine.M2.start
import pt.tiago.nightfleet.core.engine.M2.sub
import pt.tiago.nightfleet.core.engine.M2.terrain
import pt.tiago.nightfleet.core.engine.M2.weapon
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.observation.KnownCell
import pt.tiago.nightfleet.core.observation.Observation
import pt.tiago.nightfleet.core.observation.ReconReadingView
import pt.tiago.nightfleet.core.observation.SonarReadingView
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget

/** Special weapons with their edge cases, and the charges they consume and earn. */
class WeaponsTest {

    private fun battle(
        weapons: Map<Weapon, WeaponConfig>,
        enemy: List<pt.tiago.nightfleet.core.fleet.Placement> = listOf(dest("A4")),
        mines: List<List<Int>> = listOf(emptyList(), emptyList()),
        terrainLabels: Pair<Terrain, List<String>>? = null,
    ) = start(
        rules(weapons = weapons, mines = mines, terrain = terrainLabels?.let { terrain(it.first, *it.second.toTypedArray()) } ?: emptyList()),
        listOf(M2.dest("G8")),
        enemy,
    )

    // ---- sonar -------------------------------------------------------------------------------

    @Test
    fun `sonar counts the unhit ship squares of the three by three area`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.SONAR, cellTarget("A2"))
        assertEquals(listOf(2), eventsOf<Event.SonarReading>(out).map { it.count })
    }

    @Test
    fun `sonar centre may be a square already fired at, and hit squares are not counted`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val afterFire = fire(s, "A1").state
        val out = weapon(pass(afterFire).state, Weapon.SONAR, cellTarget("A1"))
        assertEquals(1, eventsOf<Event.SonarReading>(out).single().count)
    }

    @Test
    fun `sonar counts decoys of the enemy sea`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")), decoys1 = listOf(cell("C3")))
        val out = weapon(s, Weapon.SONAR, cellTarget("B2"))
        assertEquals(3, eventsOf<Event.SonarReading>(out).single().count)
    }

    @Test
    fun `sonar does not count islands or mines`() {
        val s = battle(
            SONAR_ONCE,
            enemy = listOf(dest("A1")),
            mines = listOf(emptyList(), listOf(M2.idx("A3"))),
            terrainLabels = Terrain.ISLAND to listOf("B3"),
        )
        val out = weapon(s, Weapon.SONAR, cellTarget("A2"))
        assertEquals(2, eventsOf<Event.SonarReading>(out).single().count)
    }

    @Test
    fun `sonar at a corner without ships reads zero`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals(0, eventsOf<Event.SonarReading>(weapon(s, Weapon.SONAR, cellTarget("H1"))).single().count)
    }

    @Test
    fun `sonar spends one charge and passes the turn`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.SONAR, cellTarget("A2"))
        assertEquals(0, out.state.sides[0].charges[Weapon.SONAR])
        assertEquals(1, out.state.current)
        assertEquals(1, out.state.turn)
    }

    @Test
    fun `sonar without a charge is refused and leaves the state unchanged`() {
        val s = start(rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(initial = 0, max = 2))), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.SONAR, cellTarget("A2"))
        assertEquals("NO_CHARGE", reason(out))
        assertEquals(s, out.state)
    }

    @Test
    fun `sonar reading persists in the observation with its turn`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.SONAR, cellTarget("A2")).state
        assertEquals(listOf(SonarReadingView(cell("A2"), 2, 0)), Observation.of(out, 0).sonarReadings)
        assertEquals(emptyList<SonarReadingView>(), Observation.of(out, 1).sonarReadings)
    }

    @Test
    fun `sonar refuses a line target and an off-board centre`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals("BAD_TARGET", reason(weapon(s, Weapon.SONAR, line(true, 0))))
        assertEquals("OUT_OF_BOUNDS", reason(weapon(s, Weapon.SONAR, WeaponTarget.CellTarget(Cell(9, 9)))))
    }

    @Test
    fun `a sonar use counts one star shot`() {
        val s = start(rules(weapons = SONAR_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals(1, shotsForStars(weapon(s, Weapon.SONAR, cellTarget("A2")).state, 0))
    }

    // ---- torpedo -----------------------------------------------------------------------------

    @Test
    fun `torpedo from the left stops at the first unhit ship square`() {
        val s = start(rules(weapons = TORPEDO_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.TORPEDO, line(true, 0, fromStart = true))
        assertEquals(listOf(Event.Hit(0, 1, cell("A1"))), eventsOf<Event.Hit>(out))
        assertTrue(eventsOf<Event.TorpedoStep>(out).isEmpty())
    }

    @Test
    fun `torpedo from the far margin travels back and trails the water it passed`() {
        val s = battle(TORPEDO_ONCE, enemy = listOf(dest("A1")))
        val out = weapon(s, Weapon.TORPEDO, line(true, 0, fromStart = false))
        val trail = eventsOf<Event.TorpedoTrail>(out).single().cells
        assertEquals(6, trail.size)
        assertEquals(cell("H1"), trail.first())
        assertEquals(listOf(Event.Hit(0, 1, cell("B1"))), eventsOf<Event.Hit>(out))
    }

    @Test
    fun `torpedo stops at an island with TorpedoBlocked`() {
        val s = battle(TORPEDO_ONCE, terrainLabels = Terrain.ISLAND to listOf("C1"))
        val out = weapon(s, Weapon.TORPEDO, line(true, 0))
        assertEquals(listOf(cell("C1")), eventsOf<Event.TorpedoBlocked>(out).map { it.cell })
        assertEquals(listOf(cell("A1"), cell("B1")), eventsOf<Event.TorpedoTrail>(out).single().cells)
        assertTrue(eventsOf<Event.Hit>(out).isEmpty())
    }

    @Test
    fun `torpedo passes a reef and does not trail it`() {
        val s = battle(TORPEDO_ONCE, terrainLabels = Terrain.REEF to listOf("C1"))
        val out = weapon(s, Weapon.TORPEDO, line(true, 0))
        assertTrue(eventsOf<Event.TorpedoBlocked>(out).isEmpty())
        assertEquals(7, eventsOf<Event.TorpedoTrail>(out).single().cells.size)
        assertTrue(cell("C1") !in eventsOf<Event.TorpedoTrail>(out).single().cells)
    }

    @Test
    fun `torpedo detonates a mine and stops without losing the turn`() {
        val s = battle(TORPEDO_ONCE, mines = listOf(emptyList(), listOf(M2.idx("C1"))))
        val out = weapon(s, Weapon.TORPEDO, line(true, 0))
        assertEquals(listOf(cell("C1")), eventsOf<Event.MineHit>(out).map { it.cell })
        assertEquals(listOf(0, 0), out.state.pendingSkips)
        assertEquals(1, out.state.current)
        assertEquals(listOf(cell("A1"), cell("B1")), eventsOf<Event.TorpedoTrail>(out).single().cells)
    }

    @Test
    fun `torpedo passes squares already fired at by the same side`() {
        val s = start(rules(weapons = TORPEDO_ONCE, fleet = Fx.TWIN), listOf(dest("D8"), dest("G8")), listOf(dest("A1"), dest("A4")))
        val afterFire = fire(s, "A1").state
        val out = weapon(pass(afterFire).state, Weapon.TORPEDO, line(true, 0))
        assertEquals(listOf(Event.Hit(0, 1, cell("B1"))), eventsOf<Event.Hit>(out))
        assertTrue(eventsOf<Event.TorpedoStep>(out).any { it.cell == cell("A1") })
    }

    @Test
    fun `torpedo hits a submerged submarine`() {
        val diving = DivingRule(surfaceRounds = 2, submergedRounds = 2)
        val s = start(rules(fleet = M2.SUB, weapons = TORPEDO_ONCE, diving = diving), listOf(sub("E8")), listOf(sub("A1")))
        val submerged = passRounds(s, 2)
        val out = weapon(submerged, Weapon.TORPEDO, line(true, 0))
        assertEquals(listOf(Event.Hit(0, 1, cell("A1"))), eventsOf<Event.Hit>(out))
        assertTrue(eventsOf<Event.SubmergedContact>(out).isEmpty())
    }

    @Test
    fun `torpedo reads a decoy as a hit and stops`() {
        val s = start(
            rules(weapons = TORPEDO_ONCE),
            listOf(dest("G8")),
            listOf(dest("A4")),
            decoys1 = listOf(cell("D1")),
        )
        val out = weapon(s, Weapon.TORPEDO, line(true, 0))
        assertEquals(listOf(Event.Hit(0, 1, cell("D1"))), eventsOf<Event.Hit>(out))
        assertEquals(3, eventsOf<Event.TorpedoTrail>(out).single().cells.size)
        assertTrue(out.state.sides[1].ships[0].hits.isEmpty())
    }

    @Test
    fun `torpedo can run along a column`() {
        val s = battle(TORPEDO_ONCE, enemy = listOf(dest("A4")))
        val out = weapon(s, Weapon.TORPEDO, line(false, 0))
        assertEquals(listOf(Event.Hit(0, 1, cell("A4"))), eventsOf<Event.Hit>(out))
        assertEquals(3, eventsOf<Event.TorpedoTrail>(out).single().cells.size)
    }

    @Test
    fun `torpedo trail becomes known water for the shooter`() {
        val s = battle(TORPEDO_ONCE, enemy = listOf(dest("A4")))
        val after = weapon(s, Weapon.TORPEDO, line(true, 0)).state
        assertEquals(KnownCell.WATER, Observation.of(after, 0).enemySea[M2.idx("B1")])
    }

    @Test
    fun `torpedo with a cell target or an off-board line is refused`() {
        val s = battle(TORPEDO_ONCE)
        assertEquals("BAD_TARGET", reason(weapon(s, Weapon.TORPEDO, cellTarget("A1"))))
        assertEquals("OUT_OF_BOUNDS", reason(weapon(s, Weapon.TORPEDO, line(true, 9))))
    }

    @Test
    fun `a torpedo counts one star shot whatever it passes`() {
        val s = battle(TORPEDO_ONCE, enemy = listOf(dest("A4")))
        assertEquals(1, shotsForStars(weapon(s, Weapon.TORPEDO, line(true, 0)).state, 0))
    }

    // ---- recon -------------------------------------------------------------------------------

    @Test
    fun `recon reports a ship in the row`() {
        val s = start(rules(weapons = RECON_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.RECON, line(true, 0))
        assertEquals(listOf(Event.ReconReading(0, 1, true, 0, true)), eventsOf<Event.ReconReading>(out))
    }

    @Test
    fun `recon reports absence in an empty row`() {
        val s = start(rules(weapons = RECON_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        assertFalse(eventsOf<Event.ReconReading>(weapon(s, Weapon.RECON, line(true, 4))).single().present)
    }

    @Test
    fun `recon also sees a column`() {
        val s = start(rules(weapons = RECON_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        assertTrue(eventsOf<Event.ReconReading>(weapon(s, Weapon.RECON, line(false, 1))).single().present)
        assertFalse(eventsOf<Event.ReconReading>(weapon(s, Weapon.RECON, line(false, 5))).single().present)
    }

    @Test
    fun `recon counts decoys as surface ships`() {
        val s = start(rules(weapons = RECON_ONCE), listOf(dest("G8")), listOf(dest("A1")), decoys1 = listOf(cell("C3")))
        assertTrue(eventsOf<Event.ReconReading>(weapon(s, Weapon.RECON, line(true, 2))).single().present)
    }

    @Test
    fun `recon ignores submerged submarines`() {
        val diving = DivingRule(surfaceRounds = 2, submergedRounds = 2)
        val s = start(rules(fleet = M2.SUB, weapons = RECON_ONCE, diving = diving), listOf(sub("E8")), listOf(sub("A1")))
        val out = weapon(passRounds(s, 2), Weapon.RECON, line(true, 0))
        assertFalse(eventsOf<Event.ReconReading>(out).single().present)
    }

    @Test
    fun `recon readings persist in the observation`() {
        val s = start(rules(weapons = RECON_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val after = weapon(s, Weapon.RECON, line(true, 0)).state
        assertEquals(listOf(ReconReadingView(true, 0, true, 0)), Observation.of(after, 0).reconReadings)
    }

    // ---- cross shell -------------------------------------------------------------------------

    @Test
    fun `cross in the corner fires the three squares that exist`() {
        val s = start(rules(weapons = CROSS_ONCE), listOf(dest("G8")), listOf(dest("D4")))
        val out = weapon(s, Weapon.CROSS_SHELL, cellTarget("A1"))
        assertEquals(listOf("A1", "B1", "A2"), eventsOf<Event.Miss>(out).map { it.cell.label() })
        assertEquals(3, shotsForStars(out.state, 0))
    }

    @Test
    fun `cross in the middle fires five squares in order`() {
        val s = start(rules(weapons = CROSS_ONCE), listOf(dest("G8")), listOf(dest("A8")))
        val out = weapon(s, Weapon.CROSS_SHELL, cellTarget("C3"))
        assertEquals(listOf("C3", "C2", "D3", "C4", "B3"), eventsOf<Event.Miss>(out).map { it.cell.label() })
        assertEquals(5, shotsForStars(out.state, 0))
    }

    @Test
    fun `cross skips squares already fired`() {
        val s = start(rules(weapons = CROSS_ONCE), listOf(dest("G8")), listOf(dest("A8")))
        val fired = fire(s, "B3").state
        val out = weapon(pass(fired).state, Weapon.CROSS_SHELL, cellTarget("C3"))
        assertEquals(4, eventsOf<Event.Miss>(out).size)
        assertEquals(5, shotsForStars(out.state, 0))
    }

    @Test
    fun `cross skips islands`() {
        val s = battle(CROSS_ONCE, enemy = listOf(dest("A8")), terrainLabels = Terrain.ISLAND to listOf("C2"))
        val out = weapon(s, Weapon.CROSS_SHELL, cellTarget("C3"))
        assertEquals(4, eventsOf<Event.Miss>(out).size)
    }

    @Test
    fun `cross with a mine costs one turn and two mines still cost only one`() {
        val mines = listOf(emptyList(), listOf(M2.idx("C4"), M2.idx("B3")))
        val s = battle(CROSS_ONCE, enemy = listOf(dest("A8")), mines = mines)
        val out = weapon(s, Weapon.CROSS_SHELL, cellTarget("C3"))
        assertEquals(2, eventsOf<Event.MineHit>(out).size)
        assertEquals(listOf(1, 0), out.state.pendingSkips)
    }

    @Test
    fun `cross with no valid square is refused`() {
        val s = battle(CROSS_ONCE, enemy = listOf(dest("A8")), terrainLabels = Terrain.ISLAND to listOf("A1", "B1", "A2"))
        assertEquals("NO_TARGET", reason(weapon(s, Weapon.CROSS_SHELL, cellTarget("A1"))))
    }

    @Test
    fun `cross with an off-board centre is refused`() {
        val s = battle(CROSS_ONCE)
        assertEquals("OUT_OF_BOUNDS", reason(weapon(s, Weapon.CROSS_SHELL, WeaponTarget.CellTarget(Cell(-1, 0)))))
    }

    @Test
    fun `cross hits ships in the barrage like single shots`() {
        val s = start(rules(weapons = CROSS_ONCE), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(s, Weapon.CROSS_SHELL, cellTarget("A1"))
        assertEquals(2, eventsOf<Event.Hit>(out).size)
    }

    // ---- repair ------------------------------------------------------------------------------

    private val repairOnce = mapOf(Weapon.REPAIR to WeaponConfig(initial = 1, max = 1))

    @Test
    fun `repair makes a hit square intact and tells the opponent`() {
        val s = start(rules(weapons = repairOnce), listOf(dest("G8")), listOf(dest("A1")))
        val out = weapon(fire(s, "A1").state, Weapon.REPAIR, cellTarget("A1"))
        assertEquals(listOf(Event.EnemyRepaired(1, 0, cell("A1"))), eventsOf<Event.EnemyRepaired>(out))
        assertTrue(out.state.sides[1].ships[0].hits.isEmpty())
        assertEquals(KnownCell.REPAIRED, Observation.of(out.state, 0).enemySea[M2.idx("A1")])
    }

    @Test
    fun `a repaired square can be hit again`() {
        val s = start(rules(weapons = repairOnce), listOf(dest("G8")), listOf(dest("A1")))
        val repaired = weapon(fire(s, "A1").state, Weapon.REPAIR, cellTarget("A1")).state
        val again = fire(repaired, "A1")
        assertEquals(listOf(Event.Hit(0, 1, cell("A1"))), eventsOf<Event.Hit>(again))
        assertEquals(KnownCell.HIT, Observation.of(again.state, 0).enemySea[M2.idx("A1")])
    }

    @Test
    fun `repair works once per battle`() {
        val s = start(rules(weapons = mapOf(Weapon.REPAIR to WeaponConfig(initial = 2, max = 2))), listOf(dest("G8")), listOf(dest("A1")))
        var state = weapon(fire(s, "A1").state, Weapon.REPAIR, cellTarget("A1")).state
        state = fire(state, "B1").state
        assertEquals("REPAIR_ALREADY_USED", reason(weapon(state, Weapon.REPAIR, cellTarget("B1"))))
    }

    @Test
    fun `repair of a square that is not hit is refused`() {
        val s = start(rules(weapons = repairOnce), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals("NOT_REPAIRABLE", reason(weapon(s, Weapon.REPAIR, cellTarget("A1"))))
    }

    @Test
    fun `repair of a sunk ship is refused`() {
        val s = start(rules(weapons = repairOnce, fleet = Fx.TWIN), listOf(dest("D8"), dest("G8")), listOf(dest("A1"), dest("A4")))
        var state = pass(fire(s, "A1").state).state
        state = fire(state, "B1").state
        assertEquals("NOT_REPAIRABLE", reason(weapon(state, Weapon.REPAIR, cellTarget("A1"))))
    }

    @Test
    fun `repair action is the same as the weapon`() {
        val s = start(rules(weapons = repairOnce), listOf(dest("G8")), listOf(dest("A1")))
        val viaAction = Engine.apply(fire(s, "A1").state, Action.Repair(cell("A1")))
        assertEquals(listOf(Event.EnemyRepaired(1, 0, cell("A1"))), eventsOf<Event.EnemyRepaired>(viaAction))
    }

    @Test
    fun `repairing a square needs a charge`() {
        val s = start(rules(weapons = repairOnce), listOf(dest("G8")), listOf(dest("A1")))
        val state = fire(s, "A1").state
        val charged = state.copy(sides = state.sides.mapIndexed { i, side -> if (i == 1) side.copy(charges = side.charges + (Weapon.REPAIR to 0)) else side })
        assertEquals("NO_CHARGE", reason(weapon(charged, Weapon.REPAIR, cellTarget("A1"))))
    }

    // ---- charges and earning -----------------------------------------------------------------

    @Test
    fun `a weapon missing from the rules cannot be used`() {
        val s = start(rules(weapons = emptyMap()), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals("WEAPON_NOT_AVAILABLE", reason(weapon(s, Weapon.SONAR, cellTarget("A2"))))
    }

    @Test
    fun `the decoy is never a usable weapon`() {
        val s = start(rules(weapons = mapOf(Weapon.DECOY to WeaponConfig(1, 1))), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals("WEAPON_NOT_AVAILABLE", reason(weapon(s, Weapon.DECOY, cellTarget("A2"))))
    }

    @Test
    fun `a sink earns a charge of a weapon that earns on sinks`() {
        val config = mapOf(Weapon.TORPEDO to WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.SINK))))
        val s = start(rules(weapons = config), listOf(dest("G8")), listOf(dest("A1")))
        val hit = pass(fire(s, "A1").state).state
        val sunk = fire(hit, "B1")
        assertEquals(listOf(Event.ChargeEarned(0, Weapon.TORPEDO)), eventsOf<Event.ChargeEarned>(sunk))
        assertEquals(1, sunk.state.sides[0].charges[Weapon.TORPEDO])
    }

    @Test
    fun `a streak of hits earns a charge at each multiple`() {
        val config = mapOf(Weapon.SONAR to WeaponConfig(initial = 0, max = 2, earn = listOf(EarnRule(EarnOn.STREAK, 2))))
        val s = start(rules(weapons = config, fleet = Fleet("TWIN", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))), listOf(dest("D8"), dest("G8")), listOf(dest("A1"), dest("A4")))
        val first = pass(fire(s, "A1").state).state
        val second = fire(first, "A4")
        assertEquals(listOf(Event.ChargeEarned(0, Weapon.SONAR)), eventsOf<Event.ChargeEarned>(second))
    }

    @Test
    fun `a miss breaks the streak and earns nothing`() {
        val config = mapOf(Weapon.SONAR to WeaponConfig(initial = 0, max = 2, earn = listOf(EarnRule(EarnOn.STREAK, 2))))
        val s = start(rules(weapons = config, fleet = Fleet("TWIN", listOf(ShipClass.DESTROYER, ShipClass.DESTROYER))), listOf(dest("D8"), dest("G8")), listOf(dest("A1"), dest("A4")))
        var state = pass(fire(s, "A1").state).state
        state = pass(fire(state, "C3").state).state
        val out = fire(state, "A4")
        assertTrue(eventsOf<Event.ChargeEarned>(out).isEmpty())
    }

    @Test
    fun `every n turns earns a charge on the nth turn`() {
        val config = mapOf(Weapon.SONAR to WeaponConfig(initial = 0, max = 2, earn = listOf(EarnRule(EarnOn.EVERY, 2))))
        val s = start(rules(weapons = config), listOf(dest("G8")), listOf(dest("A1")))
        val first = fire(s, "C3")
        assertTrue(eventsOf<Event.ChargeEarned>(first).isEmpty())
        val second = fire(pass(first.state).state, "C5")
        assertEquals(listOf(Event.ChargeEarned(0, Weapon.SONAR)), eventsOf<Event.ChargeEarned>(second))
    }

    @Test
    fun `both sides earn under the same rules`() {
        val config = mapOf(Weapon.SONAR to WeaponConfig(initial = 0, max = 2, earn = listOf(EarnRule(EarnOn.EVERY, 2))))
        val s = start(rules(weapons = config), listOf(dest("G8")), listOf(dest("A1")))
        val end = passRounds(s, 2)
        assertEquals(1, end.sides[0].charges[Weapon.SONAR])
        assertEquals(1, end.sides[1].charges[Weapon.SONAR])
    }

    @Test
    fun `earned charges never exceed the maximum`() {
        val config = mapOf(Weapon.SONAR to WeaponConfig(initial = 1, max = 1, earn = listOf(EarnRule(EarnOn.EVERY, 1))))
        val s = start(rules(weapons = config), listOf(dest("G8")), listOf(dest("A1")))
        val out = pass(s)
        assertTrue(eventsOf<Event.ChargeEarned>(out).isEmpty())
        assertEquals(1, out.state.sides[0].charges[Weapon.SONAR])
    }

    @Test
    fun `initial charges come from the rules for both sides and a start above the maximum is a rule error`() {
        val config = mapOf(Weapon.SONAR to WeaponConfig(initial = 2, max = 2))
        val s = start(rules(weapons = config), listOf(dest("G8")), listOf(dest("A1")))
        assertEquals(2, s.sides[0].charges[Weapon.SONAR])
        assertEquals(2, s.sides[1].charges[Weapon.SONAR])
        assertTrue(rules(weapons = mapOf(Weapon.SONAR to WeaponConfig(initial = 3, max = 2))).problems().isNotEmpty())
        assertEquals(0, s.sides[0].charges[Weapon.TORPEDO])
    }

}
