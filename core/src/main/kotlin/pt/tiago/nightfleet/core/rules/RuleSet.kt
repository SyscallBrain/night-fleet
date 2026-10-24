package pt.tiago.nightfleet.core.rules

import kotlinx.serialization.Serializable
import pt.tiago.nightfleet.core.fleet.Fleet
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Board
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig

/** Submarines that dive. */
@Serializable
data class DivingRule(val surfaceRounds: Int = 2, val submergedRounds: Int = 2)

/** Fog of war: misses fade from the attacker's view after [missFadeTurns] turns. */
@Serializable
data class FogRule(val missFadeTurns: Int = 4)

/** CONVOY: survive [surviveRounds] rounds with at least [minFreighters] freighters afloat. */
@Serializable
data class ConvoyRule(val surviveRounds: Int = 10, val minFreighters: Int = 1)

/**
 * Variant of a battle. Every field has the classic default. Enabled rules are played by
 * the engine; [problems] reports the malformed configurations.
 */
@Serializable
data class RuleSet(
    val width: Int = 10,
    val height: Int = 10,
    val fleet: Fleet = Fleet.CLASSIC,
    /** Terrain of both seas, row-major. Empty means all water. */
    val terrain: List<Terrain> = emptyList(),
    val salvo: Boolean = false,
    val diving: DivingRule? = null,
    val fog: FogRule? = null,
    val movingShips: Boolean = false,
    /** Mine squares per side (index 0 and 1). Empty lists mean no mines. */
    val mines: List<List<Int>> = listOf(emptyList(), emptyList()),
    /** App-level modifier only; the engine never reads the clock. */
    val shotClockSeconds: Int? = null,
    val shipsMayTouch: Boolean = false,
    val maxTurns: Int = 400,
    /** Charges per weapon, the same for both sides. Weapons missing from the map cannot be used. */
    val weapons: Map<Weapon, WeaponConfig> = emptyMap(),
    /** Ships added to the fleet of each side, on top of [fleet] (CONVOY adds freighters to side 0). */
    val extraShips: List<List<ShipClass>> = listOf(emptyList(), emptyList()),
    /** Set by CONVOY missions only. */
    val convoy: ConvoyRule? = null,
) {
    /** The fleet that [side] must place: [fleet] plus that side's [extraShips]. */
    fun fleetFor(side: Int): Fleet {
        val extra = extraShips.getOrElse(side) { emptyList() }
        return if (extra.isEmpty()) fleet else Fleet(fleet.name, fleet.ships + extra)
    }

    /** Human-readable configuration errors. Empty when the rules are well formed. */
    fun problems(): List<String> {
        val problems = mutableListOf<String>()
        if (width !in MIN_SIDE..MAX_SIDE || height !in MIN_SIDE..MAX_SIDE) {
            problems += "GRID_SIZE: each side must be $MIN_SIDE..$MAX_SIDE"
            return problems
        }
        val squares = width * height
        if (terrain.isNotEmpty() && terrain.size != squares) problems += "TERRAIN_SIZE: expected $squares entries"
        if (mines.size != 2) problems += "MINES_PER_SIDE: expected two lists"
        mines.forEachIndexed { side, list ->
            if (list.any { it !in 0 until squares }) problems += "MINES_OUT_OF_RANGE: side $side"
            if (terrain.isNotEmpty() && list.any { it in 0 until squares && terrain[it] != Terrain.WATER }) {
                problems += "MINE_ON_LAND: side $side"
            }
        }
        if (extraShips.size != 2) problems += "EXTRA_SHIPS_PER_SIDE: expected two lists"
        if (maxTurns < 1) problems += "MAX_TURNS: must be at least 1"
        if (shotClockSeconds != null && shotClockSeconds <= 0) problems += "SHOT_CLOCK: must be positive"
        diving?.let {
            if (it.surfaceRounds < 1 || it.submergedRounds < 1) problems += "DIVING: rounds must be at least 1"
        }
        fog?.let { if (it.missFadeTurns < 1) problems += "FOG: missFadeTurns must be at least 1" }
        weapons.forEach { (weapon, config) ->
            if (config.max < 0 || config.initial < 0 || config.initial > config.max) {
                problems += "WEAPON_CHARGES: $weapon"
            }
            if (config.earn.any { it.n < 1 }) problems += "WEAPON_EARN: $weapon needs n >= 1"
        }
        convoy?.let {
            if (it.surviveRounds < 1 || it.minFreighters < 0) problems += "CONVOY: invalid parameters"
            if (fleetFor(0).ships.count { ship -> ship == ShipClass.FREIGHTER } < it.minFreighters) {
                problems += "CONVOY_FREIGHTERS: side 0 has fewer freighters than the minimum"
            }
        }
        return problems
    }

    /** The sea of [side] for these rules. Requires [problems] to be empty. */
    fun boardFor(side: Int): Board = Board(
        width = width,
        height = height,
        terrain = if (terrain.isEmpty()) List(width * height) { Terrain.WATER } else terrain,
        mines = mines.getOrElse(side) { emptyList() }.sorted(),
    )

    companion object {
        const val MIN_SIDE = 8
        const val MAX_SIDE = 12
    }
}
