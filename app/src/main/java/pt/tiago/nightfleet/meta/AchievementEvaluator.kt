package pt.tiago.nightfleet.meta

import pt.tiago.nightfleet.data.AchievementProgress
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import java.time.LocalDate

/** Which achievements an achievement list shows. */
enum class AchievementFilter { ALL, UNLOCKED, LOCKED }

/** An achievement with its state for the screen. [value] is the progress towards its target, never above it. */
data class AchievementView(val def: AchievementDef, val unlocked: Boolean, val value: Int) {
    val fraction: Float get() = value.toFloat() / def.target
}

/**
 * Applies one [GameEvent] to the achievements of a [Profile]. Pure: it returns the new achievement map and
 * the ids unlocked by this event, and never touches storage. The caller persists the result together with
 * the rest of the profile.
 */
object AchievementEvaluator {

    data class Result(
        /** The whole achievement map, ready to store in `Profile.achievements`. */
        val achievements: Map<String, AchievementProgress>,
        /** Ids that became unlocked with this event, in catalog order. */
        val newlyUnlocked: List<String>,
    )

    /**
     * Evaluates every achievement in [defs] for [event] on [today]. Already unlocked achievements keep their
     * unlock day. Counters grow only for [AchievementRule.Count] rules, so a replayed event is never counted twice
     * by a rule that is not designed to count it.
     */
    fun evaluate(
        profile: Profile,
        event: GameEvent,
        today: LocalDate,
        defs: List<AchievementDef> = Achievements.all,
    ): Result {
        val updated = LinkedHashMap(profile.achievements)
        val newly = mutableListOf<String>()
        val counterKey = event.difficulty ?: Difficulty.MEDIUM
        for (def in defs) {
            val before = profile.achievements[def.id] ?: AchievementProgress()
            val counters = when (val rule = def.rule) {
                is AchievementRule.Count -> {
                    val amount = rule.amount(event)
                    if (amount > 0) before.counters + (counterKey to (before.counters[counterKey] ?: 0) + amount) else before.counters
                }
                else -> before.counters
            }
            val reached = before.unlockedAtEpochDay != null || when (val rule = def.rule) {
                is AchievementRule.Once -> rule.test(event)
                is AchievementRule.Count -> counted(def, counters) >= def.target
                is AchievementRule.Record -> rule.value(profile) >= def.target
            }
            val unlockedDay = before.unlockedAtEpochDay ?: if (reached) today.toEpochDay() else null
            if (before.unlockedAtEpochDay == null && unlockedDay != null) newly += def.id
            updated[def.id] = AchievementProgress(unlockedAtEpochDay = unlockedDay, counters = counters)
        }
        return Result(updated, newly)
    }

    /** The progress of [def] for display: [AchievementView.value] never exceeds the target. */
    fun view(def: AchievementDef, profile: Profile): AchievementView {
        val progress = profile.achievements[def.id]
        val unlocked = progress?.unlockedAtEpochDay != null
        val raw = when (val rule = def.rule) {
            is AchievementRule.Once -> if (unlocked) 1 else 0
            is AchievementRule.Count -> counted(def, progress?.counters.orEmpty())
            is AchievementRule.Record -> rule.value(profile)
        }
        val value = if (unlocked) def.target else raw.coerceIn(0, def.target)
        return AchievementView(def, unlocked, value)
    }

    /** The views of [defs] in catalog order, narrowed by [filter]. */
    fun list(
        profile: Profile,
        filter: AchievementFilter = AchievementFilter.ALL,
        defs: List<AchievementDef> = Achievements.all,
    ): List<AchievementView> = defs.map { view(it, profile) }.filter {
        when (filter) {
            AchievementFilter.ALL -> true
            AchievementFilter.UNLOCKED -> it.unlocked
            AchievementFilter.LOCKED -> !it.unlocked
        }
    }

    /** Sum over the difficulties, or the largest single one for a per-difficulty achievement. */
    private fun counted(def: AchievementDef, counters: Map<Difficulty, Int>): Int =
        if (def.perDifficulty) counters.values.maxOrNull() ?: 0 else counters.values.sum()
}
