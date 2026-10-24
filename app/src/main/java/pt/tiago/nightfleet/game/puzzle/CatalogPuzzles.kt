package pt.tiago.nightfleet.game.puzzle

import pt.tiago.nightfleet.catalog.CatalogRepository
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.DailyEntryDto
import pt.tiago.nightfleet.core.content.DailyKind
import pt.tiago.nightfleet.core.puzzle.PuzzleDefinition
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.game.campaign.CampaignSetup
import pt.tiago.nightfleet.game.daily.DailyChallenge
import java.time.LocalDate

/**
 * The certified puzzles of the catalog for the campaign and the daily challenge. A puzzle
 * is its definition (`puzzles/<id>.json`) plus its PROOF certificate, which holds the unique solution. A proof whose
 * puzzle id does not match the definition is refused.
 */
object CatalogPuzzles {

    /** The puzzle id of a campaign PUZZLE variant, or null when the mission has none in [difficulty]. */
    fun campaignPuzzleId(catalog: CatalogRepository, missionId: String, difficulty: Difficulty): String? {
        val mission = (catalog.mission(missionId) as? CatalogResult.Ok)?.value ?: return null
        return mission.variants[CampaignSetup.difficultyOf(difficulty)]?.puzzleId
    }

    /** The loaded state of a [PuzzleRequest.Campaign] or [PuzzleRequest.Daily]; null when the catalog cannot give it. */
    fun load(catalog: CatalogRepository, request: PuzzleRequest): PuzzleUiState? = when (request) {
        is PuzzleRequest.Campaign -> {
            val puzzleId = campaignPuzzleId(catalog, request.missionId, request.difficulty)
            val certificateId = "${request.missionId}-${letter(request.difficulty)}"
            puzzleId?.let { build(catalog, it, certificateId) }
        }
        is PuzzleRequest.Daily -> dailyEntry(catalog, request.date, request.difficulty)?.let { entry ->
            build(catalog, entry.ref, entry.ref)
        }
        PuzzleRequest.Resume, is PuzzleRequest.New -> null
    }

    /** The daily puzzle entry of [date] in [difficulty], from `daily/pool.json`. */
    fun dailyEntry(catalog: CatalogRepository, date: LocalDate, difficulty: Difficulty): DailyEntryDto? {
        val pool = (catalog.dailyPool() as? CatalogResult.Ok)?.value ?: return null
        return DailyChallenge.pick(pool, date, DailyKind.PUZZLE, difficulty)
    }

    private fun build(catalog: CatalogRepository, puzzleId: String, certificateId: String): PuzzleUiState? {
        val definition: PuzzleDefinition = (catalog.puzzle(puzzleId) as? CatalogResult.Ok)?.value ?: return null
        val proof = (catalog.certificate(certificateId) as? CatalogResult.Ok)?.value as? CertificateDto.Proof ?: return null
        // A proof of another puzzle, or one whose solution is not unique, is never shown.
        if (proof.puzzleId != puzzleId || proof.solutionCount != 1) return null
        return PuzzleReducer.initial(definition, proof.solution, proof.difficulty)
    }

    private fun letter(difficulty: Difficulty): String = when (difficulty) {
        Difficulty.EASY -> "E"
        Difficulty.MEDIUM -> "M"
        Difficulty.HARD -> "H"
    }
}
