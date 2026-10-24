package pt.tiago.nightfleet.ui.intro

/**
 * Decides which intro cards a mission shows: the mechanics of the mission that the player has not seen yet,
 * in the order of [MechanicIntro]. [onSeen] receives the whole set after every change, so the caller can persist it.
 */
class IntroCardTracker(
    seen: Set<MechanicIntro> = emptySet(),
    private val onSeen: (Set<MechanicIntro>) -> Unit = {},
) {
    private var seenCards: Set<MechanicIntro> = seen.toSet()

    /** The cards already seen. */
    val seen: Set<MechanicIntro> get() = seenCards

    /** The cards to show before a mission with these [tags], in enum order. Empty when all are seen. */
    fun pendingFor(tags: Collection<MechanicIntro>): List<MechanicIntro> =
        tags.toSet().filter { it !in seenCards }.sortedBy { it.ordinal }

    /** Records that the player has seen [card]. */
    fun markSeen(card: MechanicIntro) {
        if (card in seenCards) return
        seenCards = seenCards + card
        onSeen(seenCards)
    }
}
