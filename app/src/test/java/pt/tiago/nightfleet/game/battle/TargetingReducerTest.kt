package pt.tiago.nightfleet.game.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.nightfleet.core.engine.Action
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.weapons.EarnOn
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.core.weapons.WeaponTarget
import pt.tiago.nightfleet.game.battle.WeaponFixtures.act

class TargetingReducerTest {

    private val width = 10
    private val height = 10

    private fun enemy(current: Targeting, cell: Cell, open: Boolean = true, salvo: Int? = null): TargetTap =
        TargetingReducer.enemyTap(current, cell, width, height, open, salvo)

    @Test
    fun aLineWeaponSelectsTheRowOfTheTappedSquareFirst() {
        val aimed = TargetingReducer.pickWeapon(Targeting.Idle, Weapon.TORPEDO)
        val tap = enemy(aimed, Cell(3, 7)) as TargetTap.Stay
        val line = tap.targeting as Targeting.Line
        assertTrue(line.isRow)
        assertEquals(Cell(3, 7), line.anchor)
    }

    @Test
    fun aSecondTapOnTheSameLineSendsTheTorpedoWithTheChosenMargin() {
        val first = enemy(TargetingReducer.pickWeapon(Targeting.Idle, Weapon.TORPEDO), Cell(3, 7)) as TargetTap.Stay
        val send = enemy(first.targeting, Cell(3, 2)) as TargetTap.Send
        // The tap on column 7 sits in the right half, so the torpedo leaves from the right edge.
        assertEquals(
            Action.UseWeapon(Weapon.TORPEDO, WeaponTarget.LineTarget(isRow = true, index = 3, fromStart = false)),
            send.action,
        )
    }

    @Test
    fun theMarginIsTheEdgeNearestTheTappedSquare() {
        assertTrue(TargetingReducer.defaultFromStart(Cell(3, 2), isRow = true, width = 10, height = 10))
        assertTrue(TargetingReducer.defaultFromStart(Cell(3, 4), isRow = true, width = 10, height = 10))
        assertEquals(false, TargetingReducer.defaultFromStart(Cell(3, 5), isRow = true, width = 10, height = 10))
        assertTrue(TargetingReducer.defaultFromStart(Cell(2, 6), isRow = false, width = 10, height = 10))
        assertEquals(false, TargetingReducer.defaultFromStart(Cell(7, 6), isRow = false, width = 10, height = 10))
    }

    @Test
    fun theAxisToggleKeepsTheSquareAndSwitchesToItsColumn() {
        val line = Targeting.Line(Weapon.RECON, anchor = Cell(3, 7), isRow = true, fromStart = false)
        val column = TargetingReducer.flipAxis(line, width, height)
        assertEquals(false, column.isRow)
        assertEquals(Cell(3, 7), column.anchor)
        // Column 7 is in the right half of a 10 by 10 board when read from its top: the margin is the top edge.
        assertTrue(column.fromStart)
        val send = enemy(column, Cell(9, 7)) as TargetTap.Send
        assertEquals(
            Action.UseWeapon(Weapon.RECON, WeaponTarget.LineTarget(isRow = false, index = 7, fromStart = true)),
            send.action,
        )
    }

    @Test
    fun theMarginToggleFlipsTheEdge() {
        val line = Targeting.Line(Weapon.TORPEDO, anchor = Cell(0, 0), isRow = true, fromStart = true)
        assertEquals(false, TargetingReducer.flipMargin(line).fromStart)
        assertTrue(TargetingReducer.flipMargin(TargetingReducer.flipMargin(line)).fromStart)
    }

    @Test
    fun aSquareWeaponSendsOnTheFirstTapAndRefusesAKnownSquare() {
        val sonar = TargetingReducer.pickWeapon(Targeting.Idle, Weapon.SONAR)
        assertEquals(TargetTap.Stay(sonar, TargetingReducer.REFUSAL_ALREADY_FIRED), enemy(sonar, Cell(4, 4), open = false))
        val send = enemy(sonar, Cell(4, 4)) as TargetTap.Send
        assertEquals(Action.UseWeapon(Weapon.SONAR, WeaponTarget.CellTarget(Cell(4, 4))), send.action)
    }

    @Test
    fun pickingTheSameWeaponAgainCancelsAndPickingAnotherReplaces() {
        val sonar = TargetingReducer.pickWeapon(Targeting.Idle, Weapon.SONAR)
        assertEquals(Targeting.Idle, TargetingReducer.pickWeapon(sonar, Weapon.SONAR))
        assertEquals(Targeting.Square(Weapon.CROSS_SHELL), TargetingReducer.pickWeapon(sonar, Weapon.CROSS_SHELL))
    }

    @Test
    fun aWeaponReplacesAnOpenSalvo() {
        val salvo = Targeting.Salvo(picked = listOf(1, 2), required = 3)
        assertEquals(Targeting.Square(Weapon.SONAR), TargetingReducer.pickWeapon(salvo, Weapon.SONAR))
    }

    @Test
    fun aSalvoTogglesSquaresAndStopsAtTheRequiredSize() {
        val first = enemy(Targeting.Idle, Cell(0, 0), salvo = 2) as TargetTap.Stay
        assertEquals(Targeting.Salvo(listOf(0), 2), first.targeting)
        val second = enemy(first.targeting, Cell(0, 1), salvo = 2) as TargetTap.Stay
        assertEquals(Targeting.Salvo(listOf(0, 1), 2), second.targeting)
        val full = enemy(second.targeting, Cell(0, 2), salvo = 2) as TargetTap.Stay
        assertEquals(TargetingReducer.REFUSAL_SALVO_FULL, full.refusal)
        assertEquals(Targeting.Salvo(listOf(0, 1), 2), full.targeting)
        val removed = enemy(second.targeting, Cell(0, 0), salvo = 2) as TargetTap.Stay
        assertEquals(Targeting.Salvo(listOf(1), 2), removed.targeting)
    }

    @Test
    fun aSalvoRefusesAKnownSquare() {
        val refused = enemy(Targeting.Salvo(emptyList(), 3), Cell(5, 5), open = false, salvo = 3) as TargetTap.Stay
        assertEquals(TargetingReducer.REFUSAL_ALREADY_FIRED, refused.refusal)
    }

    @Test
    fun withoutTheSalvoRuleATapIsASingleShot() {
        assertEquals(TargetTap.Fire(44), enemy(Targeting.Idle, Cell(4, 4), salvo = null))
    }

    @Test
    fun repairAimsAtTheOwnSeaOnly() {
        val repair = TargetingReducer.pickWeapon(Targeting.Idle, Weapon.REPAIR)
        assertEquals(repair, (enemy(repair, Cell(2, 2)) as TargetTap.Stay).targeting)
        val own = TargetingReducer.ownTap(repair, Cell(2, 2), shipAt = { null }, legalDeltas = { emptyList() })
        assertEquals(TargetTap.Send(Action.Repair(Cell(2, 2))), own)
    }

    @Test
    fun aMoveOffersOnlyTheLegalStepsOfTheShipUnderTheFinger() {
        val rules = WeaponFixtures.rules(movingShips = true)
        val match = WeaponFixtures.match(rules)
        val state = match.state
        // The destroyer is the fifth ship of the fixed layout, on row 8, columns 0 and 1. Column -1 is off the board.
        val destroyer = state.sides[0].ships.indexOfFirst { it.shipClass == ShipClass.DESTROYER }
        val moving = TargetingReducer.pickMove(Targeting.Idle)
        assertEquals(Targeting.Move(null, emptyList()), moving)

        val picked = TargetingReducer.ownTap(
            moving,
            Cell(8, 0),
            shipAt = { cell -> if (cell == Cell(8, 0)) destroyer else null },
            legalDeltas = { MovePlanner.legalDeltas(state, it) },
        ) as TargetTap.Stay
        assertEquals(Targeting.Move(destroyer, listOf(1)), picked.targeting)

        assertEquals(TargetTap.Send(Action.MoveShip(destroyer, 1)), TargetingReducer.moveStep(picked.targeting, 1))
        val blocked = TargetingReducer.moveStep(picked.targeting, -1) as TargetTap.Stay
        assertEquals("MOVE_BLOCKED", blocked.refusal)
    }

    @Test
    fun theLegalStepsComeFromTheEngine() {
        val match = WeaponFixtures.match(WeaponFixtures.rules(movingShips = true))
        val carrier = 0
        // The carrier lies on row 0 from column 0: it can step right, not left off the board.
        assertEquals(listOf(1), MovePlanner.legalDeltas(match.state, carrier))
        assertEquals(setOf(0, 1, 2, 3, 4), MovePlanner.movableShips(match.state).toSet())
    }

    @Test
    fun noShipCanMoveWhenTheRuleIsOff() {
        val match = WeaponFixtures.match(WeaponFixtures.rules(movingShips = false))
        assertTrue(MovePlanner.movableShips(match.state).isEmpty())
    }

    @Test
    fun aWeaponWithoutAButtonDoesNothing() {
        // The decoy has no button: picking it leaves the column closed.
        assertEquals(Targeting.Idle, TargetingReducer.pickWeapon(Targeting.Idle, Weapon.DECOY))
    }

    @Test
    fun theDefaultConfigsOfTheCatalogAreValidRules() {
        val weapons = Weapon.entries.associateWith { WeaponCatalog.defaultConfig(it) }
        assertTrue(WeaponFixtures.rules(weapons = weapons).problems().isEmpty())
        assertEquals(EarnRule(EarnOn.STREAK, 3), weapons.getValue(Weapon.SONAR).earn.single())
        assertEquals(WeaponConfig(initial = 0, max = 1, earn = listOf(EarnRule(EarnOn.SINK))), weapons.getValue(Weapon.TORPEDO))
    }
}
