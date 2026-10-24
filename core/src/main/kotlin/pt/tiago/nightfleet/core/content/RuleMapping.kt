package pt.tiago.nightfleet.core.content

import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.Placement
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.rules.ConvoyRule
import pt.tiago.nightfleet.core.rules.DivingRule
import pt.tiago.nightfleet.core.rules.FogRule
import pt.tiago.nightfleet.core.rules.RuleSet
import pt.tiago.nightfleet.core.weapons.EarnRule
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/**
 * Maps content to engine types. Every function throws [IllegalArgumentException] with a readable
 * message on content the engine cannot represent; leveltool reports those as validation problems.
 */

/** Fleet preset by name (`CLASSIC`, `SMALL`, …). */
fun fleetPreset(name: String): Fleet =
    Fleet.PRESETS.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown fleet preset $name")

/** Cells of a terrain map. Rows: `.` water, `#` island, `~` reef. */
fun TerrainDto.toTerrains(): List<Terrain> {
    require(rows.isNotEmpty()) { "terrain $id has no rows" }
    val width = rows.first().length
    require(rows.all { it.length == width }) { "terrain $id has rows of different lengths" }
    return rows.flatMap { row ->
        row.map { symbol ->
            when (symbol) {
                '.' -> Terrain.WATER
                '#' -> Terrain.ISLAND
                '~' -> Terrain.REEF
                else -> throw IllegalArgumentException("terrain $id has unknown symbol '$symbol'")
            }
        }
    }
}

/** Weapon by its name in content (`SONAR`, `CROSS_SHELL`, …). */
fun weaponNamed(name: String): Weapon =
    Weapon.entries.firstOrNull { it.name == name } ?: throw IllegalArgumentException("unknown weapon $name")

/** Converts the rules of a variant to a [RuleSet]. Maps every field of the engine rules. */
fun RulesDto.toRuleSet(
    terrains: Map<String, List<Terrain>> = emptyMap(),
    weapons: Map<String, WeaponConfigDto> = emptyMap(),
    convoy: ConvoyDto? = null,
): RuleSet {
    val cells = terrain?.let { id -> terrains[id] ?: throw IllegalArgumentException("unknown terrain $id") } ?: emptyList()
    val squares = width * height
    val mineIndices = mines.map { side ->
        side.map { label ->
            val cell = Cell.parse(label) ?: throw IllegalArgumentException("malformed mine label $label")
            require(cell.row in 0 until height && cell.col in 0 until width) { "mine $label is outside the grid" }
            cell.row * width + cell.col
        }.sorted()
    }
    require(cells.isEmpty() || cells.size == squares) { "terrain $terrain does not match ${width}x$height" }
    return RuleSet(
        width = width,
        height = height,
        fleet = fleetPreset(fleet),
        terrain = cells,
        salvo = salvo,
        diving = diving?.let { DivingRule(surfaceRounds = it.surfaceRounds, submergedRounds = it.submergedRounds) },
        fog = fog?.let { FogRule(missFadeTurns = it.missFadeTurns) },
        movingShips = movingShips,
        mines = mineIndices,
        shotClockSeconds = shotClockSeconds,
        shipsMayTouch = shipsMayTouch,
        maxTurns = maxTurns,
        weapons = weapons.mapKeys { weaponNamed(it.key) }.mapValues { (_, config) -> config.toWeaponConfig() },
        extraShips = extraShips.map { side -> side.toList() },
        convoy = convoy?.let { ConvoyRule(surviveRounds = it.surviveRounds, minFreighters = it.minFreighters) },
    )
}

/** The rules of a whole variant: its [RulesDto] with the weapon charges and the convoy parameters. */
fun VariantDto.toRuleSet(terrains: Map<String, List<Terrain>> = emptyMap()): RuleSet =
    rules.toRuleSet(terrains, weapons, convoy)

fun WeaponConfigDto.toWeaponConfig(): WeaponConfig = WeaponConfig(
    initial = initial,
    max = max,
    earn = earn.map { EarnRule(on = it.on, n = it.n) },
)

fun PlacementDto.toPlacement(): Placement = Placement(shipClass = ship, origin = Cell(row, col), rotationIndex = rotation)
