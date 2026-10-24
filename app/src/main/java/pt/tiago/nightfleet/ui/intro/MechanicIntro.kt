package pt.tiago.nightfleet.ui.intro

/**
 * The 17 mechanics that get an intro card the first time a mission uses them.
 * Each card has a title and exactly two sentences, in values/ and values-pt/ under the keys below.
 */
enum class MechanicIntro(val tag: String) {
    LIGHTHOUSE_PUZZLE("lighthouse_puzzle"),
    ISLANDS_REEFS("islands_reefs"),
    SONAR("sonar"),
    SHOT_CLOCK("shot_clock"),
    MINES("mines"),
    AIR_RECON("air_recon"),
    SCHOOL_RULES("school_rules"),
    CONVOY("convoy"),
    DECOY("decoy"),
    TORPEDO("torpedo"),
    DIVING_SUBS("diving_subs"),
    REPAIR("repair"),
    SALVO("salvo"),
    CROSS_BARRAGE("cross_barrage"),
    LARGE_GRID("large_grid"),
    FOG("fog"),
    MOVING_SHIPS("moving_ships");

    /** String resource name of the card title. */
    val titleKey: String get() = "intro_${tag}_title"

    /** String resource name of the first sentence. */
    val firstSentenceKey: String get() = "intro_${tag}_line1"

    /** String resource name of the second sentence. */
    val secondSentenceKey: String get() = "intro_${tag}_line2"

    /** Every string resource name the card uses. */
    val stringKeys: List<String> get() = listOf(titleKey, firstSentenceKey, secondSentenceKey)
}
