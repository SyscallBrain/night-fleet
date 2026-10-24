package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.fleet.PlacementStyle
import pt.tiago.nightfleet.core.random.SplitMix64

/** Preferred hunting pattern when there are no open hits. */
enum class HuntBias {
    /** Chequerboard, offset by [AdmiralProfile.parityPhase]. */
    PARITY,

    /** Centre of the grid first, then outwards. */
    CENTRE_SPIRAL,

    /** Border squares first. */
    EDGES_FIRST,

    /** The two diagonals first. */
    DIAGONALS,

    /** Centre early in the battle, border later. */
    ADAPTIVE,

    /** No preference. */
    RANDOM,
}

/**
 * One admiral. Per-mille values are integers. [blunderBase] is the chance that
 * the chosen square is replaced by a random unknown one, before the tier scales it. [weaponAggression]
 * is the chance that a weapon policy that triggers is actually used. [sonarThreshold] and
 * [torpedoThreshold] are shares of the total density, in per-mille.
 */
data class AdmiralProfile(
    val id: String,
    val placementStyle: PlacementStyle,
    val huntBias: HuntBias,
    val parityPhase: Int = 0,
    val blunderBase: Int,
    val weaponAggression: Int,
    val weaponsAllowed: Set<Weapon>,
    val weaponDelayTurns: Int,
    val sonarThreshold: Int,
    val torpedoThreshold: Int,
    val tauntKeys: List<String>,
) {
    init {
        require(id.isNotBlank()) { "Admiral id must not be blank" }
        require(parityPhase in 0..1) { "Parity phase must be 0 or 1" }
        require(blunderBase in 0..1000) { "blunderBase is per-mille" }
        require(weaponAggression in 0..1000) { "weaponAggression is per-mille" }
        require(sonarThreshold in 0..1000 && torpedoThreshold in 0..1000) { "Thresholds are per-mille" }
        require(weaponDelayTurns >= 0) { "weaponDelayTurns must not be negative" }
    }
}

/** The twelve admirals. */
object Admirals {
    private val ALL_WEAPONS: Set<Weapon> = Weapon.entries.toSet()

    val IRIS = AdmiralProfile(
        id = "iris",
        placementStyle = PlacementStyle(edges = -2, spread = 3),
        huntBias = HuntBias.RANDOM,
        blunderBase = 300,
        weaponAggression = 50,
        weaponsAllowed = setOf(Weapon.SONAR),
        weaponDelayTurns = 4,
        sonarThreshold = 200,
        torpedoThreshold = 250,
        tauntKeys = listOf("taunt_iris_1", "taunt_iris_2"),
    )

    val HAZE = AdmiralProfile(
        id = "haze",
        placementStyle = PlacementStyle(edges = 3),
        huntBias = HuntBias.PARITY,
        blunderBase = 150,
        weaponAggression = 300,
        weaponsAllowed = setOf(Weapon.SONAR),
        weaponDelayTurns = 6,
        sonarThreshold = 150,
        torpedoThreshold = 200,
        tauntKeys = listOf("taunt_haze_1", "taunt_haze_2"),
    )

    val CORAL = AdmiralProfile(
        id = "coral",
        placementStyle = PlacementStyle(nearIslands = 3),
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 200,
        weaponAggression = 400,
        weaponsAllowed = setOf(Weapon.SONAR, Weapon.TORPEDO),
        weaponDelayTurns = 3,
        sonarThreshold = 150,
        torpedoThreshold = 150,
        tauntKeys = listOf("taunt_coral_1", "taunt_coral_2"),
    )

    val FROST = AdmiralProfile(
        id = "frost",
        placementStyle = PlacementStyle(centre = 2),
        huntBias = HuntBias.CENTRE_SPIRAL,
        blunderBase = 80,
        weaponAggression = 600,
        weaponsAllowed = setOf(Weapon.SONAR),
        weaponDelayTurns = 1,
        sonarThreshold = 120,
        torpedoThreshold = 200,
        tauntKeys = listOf("taunt_frost_1", "taunt_frost_2"),
    )

    val IRON = AdmiralProfile(
        id = "iron",
        placementStyle = PlacementStyle(cluster = 3),
        huntBias = HuntBias.EDGES_FIRST,
        blunderBase = 150,
        weaponAggression = 300,
        weaponsAllowed = setOf(Weapon.RECON),
        weaponDelayTurns = 3,
        sonarThreshold = 200,
        torpedoThreshold = 200,
        tauntKeys = listOf("taunt_iron_1", "taunt_iron_2"),
    )

    val ATOLL = AdmiralProfile(
        id = "atoll",
        placementStyle = PlacementStyle(edges = 2, spread = 1),
        huntBias = HuntBias.DIAGONALS,
        blunderBase = 100,
        weaponAggression = 400,
        weaponsAllowed = setOf(Weapon.SONAR, Weapon.CROSS_SHELL),
        weaponDelayTurns = 2,
        sonarThreshold = 150,
        torpedoThreshold = 200,
        tauntKeys = listOf("taunt_atoll_1", "taunt_atoll_2"),
    )

    val WOLF = AdmiralProfile(
        id = "wolf",
        placementStyle = PlacementStyle(centre = 1, cluster = 3),
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 60,
        weaponAggression = 800,
        weaponsAllowed = setOf(Weapon.TORPEDO, Weapon.SONAR),
        weaponDelayTurns = 0,
        sonarThreshold = 150,
        torpedoThreshold = 120,
        tauntKeys = listOf("taunt_wolf_1", "taunt_wolf_2"),
    )

    val WITCH = AdmiralProfile(
        id = "witch",
        placementStyle = PlacementStyle(cluster = 1),
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 40,
        weaponAggression = 900,
        weaponsAllowed = ALL_WEAPONS,
        weaponDelayTurns = 0,
        sonarThreshold = 120,
        torpedoThreshold = 120,
        tauntKeys = listOf("taunt_witch_1", "taunt_witch_2"),
    )

    val TEMPEST = AdmiralProfile(
        id = "tempest",
        placementStyle = PlacementStyle(spread = 2),
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 60,
        weaponAggression = 600,
        weaponsAllowed = setOf(Weapon.CROSS_SHELL, Weapon.SONAR),
        weaponDelayTurns = 1,
        sonarThreshold = 150,
        torpedoThreshold = 200,
        tauntKeys = listOf("taunt_tempest_1", "taunt_tempest_2"),
    )

    val LEVIATHAN = AdmiralProfile(
        id = "leviathan",
        placementStyle = PlacementStyle(edges = -3, spread = 3),
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 0,
        weaponAggression = 1000,
        weaponsAllowed = ALL_WEAPONS,
        weaponDelayTurns = 0,
        sonarThreshold = 120,
        torpedoThreshold = 120,
        tauntKeys = listOf("taunt_leviathan_1", "taunt_leviathan_2"),
    )

    /** The style and hunting pattern of the other admirals, which Captain Shade copies by seed. */
    private val SHADE_SOURCES: List<AdmiralProfile> =
        listOf(IRIS, HAZE, CORAL, FROST, IRON, ATOLL, WOLF, WITCH, TEMPEST, LEVIATHAN)

    /**
     * Captain Shade changes style every battle: the seed picks one of the other styles.
     * Its own weapons (repair) and taunts stay.
     */
    fun captainShade(seed: Long): AdmiralProfile {
        val source = SHADE_SOURCES[SplitMix64(seed).nextInt(SHADE_SOURCES.size)]
        return AdmiralProfile(
            id = "shade",
            placementStyle = source.placementStyle,
            huntBias = HuntBias.ADAPTIVE,
            blunderBase = 60,
            weaponAggression = 700,
            weaponsAllowed = setOf(Weapon.REPAIR),
            weaponDelayTurns = 2,
            sonarThreshold = 150,
            torpedoThreshold = 200,
            tauntKeys = listOf("taunt_shade_1", "taunt_shade_2"),
        )
    }

    /**
     * Grand Admiral Nox places ships in the style you lost most against. [playerHistoryStyle] is that
     * style, computed locally by the caller; null means no history yet (balanced).
     */
    fun grandAdmiralNox(playerHistoryStyle: PlacementStyle? = null): AdmiralProfile = AdmiralProfile(
        id = "nox",
        placementStyle = playerHistoryStyle ?: PlacementStyle.BALANCED,
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 0,
        weaponAggression = 1000,
        weaponsAllowed = ALL_WEAPONS,
        weaponDelayTurns = 0,
        sonarThreshold = 120,
        torpedoThreshold = 120,
        tauntKeys = listOf("taunt_nox_1", "taunt_nox_2"),
    )

    /** A profile without personality: balanced placement, no blunders, no weapons. Used by benchmarks. */
    val NEUTRAL = AdmiralProfile(
        id = "neutral",
        placementStyle = PlacementStyle.BALANCED,
        huntBias = HuntBias.ADAPTIVE,
        blunderBase = 0,
        weaponAggression = 0,
        weaponsAllowed = emptySet(),
        weaponDelayTurns = 0,
        sonarThreshold = 150,
        torpedoThreshold = 150,
        tauntKeys = emptyList(),
    )

    /** The twelve admirals, in campaign order, with Shade on seed 0 and Nox with no history. */
    val ALL: List<AdmiralProfile> = listOf(
        IRIS, HAZE, CORAL, FROST, IRON, ATOLL, WOLF, WITCH, captainShade(0L), TEMPEST, LEVIATHAN, grandAdmiralNox(),
    )

    fun byId(id: String): AdmiralProfile? = ALL.firstOrNull { it.id == id }
}
