package pt.tiago.nightfleet.core.ai

import pt.tiago.nightfleet.core.random.SplitMix64
import pt.tiago.nightfleet.core.weapons.Weapon
import java.util.concurrent.atomic.AtomicLong

/**
 * An admiral: decides one turn from an [AiView] only. Decisions depend on the view, the
 * profile, the tier and the [rng] alone, never on time, so the same inputs give the same intent on
 * the JVM and on a phone.
 */
interface AdmiralAI {
    /**
     * One turn. [skill] carries the effective numbers of this decision. The default is the one derived from
     * the profile and the tier, so calls without it keep the behaviour of earlier versions.
     */
    fun decide(
        view: AiView,
        profile: AdmiralProfile,
        tier: AiTier,
        rng: SplitMix64,
        skill: AiSkill = AiSkill.derived(profile, tier),
    ): AiIntent

    /**
     * Up to [shots] distinct squares for a salvo (rule Salva), best first. The default uses the admiral
     * brain; a player model can override it.
     */
    fun decideSalvo(
        view: AiView,
        profile: AdmiralProfile,
        tier: AiTier,
        rng: SplitMix64,
        shots: Int,
        skill: AiSkill = AiSkill.derived(profile, tier),
    ): List<Int> = AdmiralBrain.decideSalvo(view, profile, tier, rng, shots, skill)
}

/**
 * The effective per-mille numbers of one decision. They are passed explicitly, so the leveltool,
 * the app and the tests use the same values. [blunderPermille]: the chosen square is replaced by a random
 * unknown one. [followUpMissPermille]: when open hits exist, the admiral ignores them this turn and hunts
 * (0 means never). [weaponDelayTurns]: no weapon before this turn.
 */
data class AiSkill(
    val blunderPermille: Int,
    val followUpMissPermille: Int = 0,
    val weaponDelayTurns: Int = 0,
    /**
     * The weapons the admiral may use, intersected with the profile's own. Null means the profile's
     * set. An empty set means the admiral never uses a weapon.
     */
    val admiralWeapons: Set<Weapon>? = null,
) {
    init {
        require(blunderPermille in 0..1000) { "blunderPermille is per-mille" }
        require(followUpMissPermille in 0..900) { "followUpMissPermille is 0..900" }
        require(weaponDelayTurns >= 0) { "weaponDelayTurns must not be negative" }
    }

    companion object {
        /** The values that the admirals use when no skill is given: today's blunder scaling and the profile's delay. */
        fun derived(profile: AdmiralProfile, tier: AiTier): AiSkill = AiSkill(
            blunderPermille = AdmiralBrain.blunderPermille(profile, tier),
            followUpMissPermille = 0,
            weaponDelayTurns = profile.weaponDelayTurns,
        )
    }
}

/** Counters of the fallbacks taken by the decisions. They never change a decision. */
object AiStats {
    private val samplerFallbacks = AtomicLong()
    private val densityFallbacks = AtomicLong()

    /** Decisions where the sampler ran out of budget and the density was used instead. */
    val samplerFallbackCount: Long get() = samplerFallbacks.get()

    /** Decisions where no placement fitted and a random unknown square was fired at. */
    val densityFallbackCount: Long get() = densityFallbacks.get()

    internal fun recordSamplerFallback() {
        samplerFallbacks.incrementAndGet()
    }

    internal fun recordDensityFallback() {
        densityFallbacks.incrementAndGet()
    }

    fun reset() {
        samplerFallbacks.set(0)
        densityFallbacks.set(0)
    }
}
