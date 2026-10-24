package pt.tiago.nightfleet.core.content

import pt.tiago.nightfleet.core.ai.AdmiralProfile
import pt.tiago.nightfleet.core.ai.AiSkill
import pt.tiago.nightfleet.core.ai.AiTier

/**
 * The effective skill of a variant's admiral: the single mapping used by the leveltool
 * and by the app, so a calibrated variant plays the same way everywhere. The blunder is the content value as it
 * is (no scaling by the profile); the weapon delay is the content value, or the profile default when it is null.
 */
fun AiConfigDto.toSkill(profile: AdmiralProfile, tier: AiTier): AiSkill = AiSkill(
    blunderPermille = blunder,
    followUpMissPermille = followUpMiss,
    weaponDelayTurns = weaponDelay ?: AiSkill.derived(profile, tier).weaponDelayTurns,
    admiralWeapons = admiralWeapons?.map { weaponNamed(it) }?.toSet(),
)

/** The tier of the admiral as the AI sees it: the effective tier of the content, after any escalation. */
fun AiConfigDto.aiTier(): AiTier = AiTier.valueOf(tier.name)
