package pt.tiago.nightfleet.game.campaign

import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.MatchKind
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.SavedMatch
import pt.tiago.nightfleet.meta.Seas

/**
 * Where "Continuar" of the Home goes: the unfinished campaign play when there is one, otherwise the
 * sheet of the next mission in [difficulty]. Pure, so the routing is tested on the JVM.
 */
object CampaignContinue {

    sealed interface Target {
        /** The saved play of [mission] in [difficulty]: the mission opens and resumes it. */
        data class Resume(val mission: Int, val difficulty: Difficulty) : Target

        /** No saved play: the sheet of the next mission. */
        data class NextMission(val mission: Int, val difficulty: Difficulty) : Target

        /** Every mission of [difficulty] is done. */
        data object None : Target
    }

    /** [saved] is the campaign slot as the store returns it (already checked against [profile]). */
    fun target(profile: Profile, saved: SavedMatch?, difficulty: Difficulty): Target {
        if (saved != null && saved.kind == MatchKind.CAMPAIGN) {
            val mission = saved.missionId?.let(Seas::parseMissionId)?.mission
            val savedDifficulty = saved.difficulty
            if (mission != null && savedDifficulty != null) return Target.Resume(mission, savedDifficulty)
        }
        val next = CampaignRules.currentMission(profile, difficulty) ?: return Target.None
        return Target.NextMission(next, difficulty)
    }
}
