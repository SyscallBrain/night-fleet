package pt.tiago.nightfleet.core.ai

/** Version of the admirals' behaviour. Bumped on any observable change in AI decisions. */
const val AI_VERSION = 3

/** Difficulty of the admirals. */
enum class AiTier {
    /** Random hunt, sonar at random. */
    EASY,

    /** Parity hunt adjusted to the smallest live ship, simple sonar and torpedo. */
    MEDIUM,

    /** Placement density, weapon policies. */
    HARD,

    /** Sampling of consistent fleets. Only in free play. */
    RELENTLESS,
}
