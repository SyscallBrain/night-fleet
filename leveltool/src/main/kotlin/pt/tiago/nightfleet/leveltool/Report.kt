package pt.tiago.nightfleet.leveltool

import kotlinx.serialization.decodeFromString
import pt.tiago.nightfleet.core.content.CatalogDto
import pt.tiago.nightfleet.core.content.CertificateDto
import pt.tiago.nightfleet.core.content.ContentJson
import pt.tiago.nightfleet.core.content.Difficulty
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.VariantDto
import java.io.File

/**
 * Markdown summary of the catalog: missions per sea and type, families, tags, and what is still
 * missing. Sorted everywhere, so the same content always gives the same report.
 */
fun buildReport(root: File): String {
    val set = loadContent(root)
    val catalog = set.catalog ?: ContentJson.json.decodeFromString<CatalogDto>(emptyCatalogJson())
    val missions = catalog.missions.mapNotNull { set.missions[it]?.value }
    val problems = ContentValidator(set, strict = false).run()
    val out = StringBuilder()
    out.appendLine("# Night Fleet content report")
    out.appendLine()
    out.appendLine("- Content version ${catalog.contentVersion}, engine ${catalog.engineVersion}, AI ${catalog.aiVersion}")
    out.appendLine("- Missions listed: ${catalog.missions.size}, decoded: ${missions.size}")
    out.appendLine("- Seas listed: ${catalog.seas.size}")
    out.appendLine()

    out.appendLine("## Missions per sea")
    out.appendLine()
    out.appendLine("| Sea | Total | " + MissionKind.entries.joinToString(" | ") { it.name.lowercase() } + " |")
    out.appendLine("|---|---|" + MissionKind.entries.joinToString("|") { "---:" } + "|")
    for (seaId in catalog.seas.sorted()) {
        val inSea = missions.filter { seaIdOf(it.id) == seaId }
        val counts = MissionKind.entries.joinToString(" | ") { kind -> inSea.count { it.type == kind }.toString() }
        out.appendLine("| $seaId | ${inSea.size} | $counts |")
    }
    out.appendLine()

    out.appendLine("## Mix against CONTENT section 2")
    out.appendLine()
    out.appendLine("| Sea | Duel | Puzzle | Hunt | Convoy | Boss | Mined | Timed | Salvo | Diving | Fog | Moving | Target (duel/puzzle/hunt/convoy) |")
    out.appendLine("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|")
    for (seaId in catalog.seas.sorted()) {
        val sea = seaNumberOf(seaId)
        val inSea = missions.filter { seaIdOf(it.id) == seaId }
        fun count(kind: MissionKind) = inSea.count { it.type == kind }
        fun modifier(pick: (VariantDto) -> Boolean) = inSea.count { m -> m.variants[Difficulty.MEDIUM]?.let(pick) == true }
        val target = TYPE_COUNTS[sea]?.let { "${it.duel}/${it.puzzle}/${it.hunt}/${it.convoy}" } ?: "-"
        out.appendLine(
            "| $seaId | ${count(MissionKind.DUEL)} | ${count(MissionKind.PUZZLE)} | ${count(MissionKind.HUNT)} | ${count(MissionKind.CONVOY)} " +
                "| ${count(MissionKind.BOSS)} | ${modifier { v -> v.rules.mines.any { it.isNotEmpty() } }} " +
                "| ${modifier { v -> v.rules.shotClockSeconds != null }} | ${modifier { v -> v.rules.salvo }} " +
                "| ${modifier { v -> v.rules.diving != null }} | ${modifier { v -> v.rules.fog != null }} " +
                "| ${modifier { v -> v.rules.movingShips }} | $target |",
        )
    }
    out.appendLine()

    out.appendLine("## UNCALIBRATED")
    out.appendLine()
    val pending = missions.filter { it.type != MissionKind.PUZZLE }.flatMap { mission ->
        mission.variants.filterValues { !it.calibrated }.keys.map { certificateId(mission.id, it) }
    }.sorted()
    out.appendLine("- Variants waiting for calibration: ${pending.size} (stars, shotBudget and certificate pending)")
    pending.take(MAX_LISTED).forEach { out.appendLine("  - $it") }
    out.appendLine()

    out.appendLine("## Calibration")
    out.appendLine()
    val certificates = set.certificates.values.mapNotNull { it.value as? CertificateDto.Calibration }
    val dailyHunts = set.dailyHunts?.value?.hunts?.mapNotNull { it.certificate }.orEmpty()
    out.appendLine("| Sea | Difficulty | Calibrated | Mean win | Out of band |")
    out.appendLine("|---|---|---:|---:|---:|")
    val groups = (certificates + dailyHunts).groupBy { (seaIdOf(it.missionId) ?: "daily") to it.difficulty }
    for ((key, group) in groups.toSortedMap(compareBy<Pair<String, Difficulty>> { it.first }.thenBy { it.second.ordinal })) {
        val wins = group.mapNotNull { it.tuning?.winPermille }
        val mean = if (wins.isEmpty()) 0 else wins.sum() / wins.size / 10
        val outs = group.count { it.outOfBand }
        out.appendLine("| ${key.first} | ${key.second.name} | ${group.size} | $mean% | $outs |")
    }
    out.appendLine()
    val outliers = (certificates + dailyHunts).filter { it.outOfBand }.map { "${it.id} (${(it.tuning?.winPermille ?: 0) / 10}%)" }.sorted()
    out.appendLine("- Out of band: ${outliers.size}")
    outliers.take(MAX_LISTED).forEach { out.appendLine("  - $it") }
    val clearFlags = dailyHunts.filter { (it.tuning?.hardClearPermille ?: HUNT_CLEAR_MIN_PERMILLE) < HUNT_CLEAR_MIN_PERMILLE }.map { it.id }.sorted()
    out.appendLine("- Daily hunts where HARD clears under 97% of the pool: ${clearFlags.size}")
    clearFlags.take(MAX_LISTED).forEach { out.appendLine("  - $it") }
    out.appendLine()

    out.appendLine("## Families")
    out.appendLine()
    for ((family, count) in missions.groupingBy { it.family }.eachCount().toSortedMap()) {
        out.appendLine("- $family: $count")
    }
    out.appendLine()

    out.appendLine("## Tags")
    out.appendLine()
    val tagCounts = missions.flatMap { it.tags }.groupingBy { it }.eachCount()
    for (tag in pt.tiago.nightfleet.core.content.MissionTag.entries) {
        out.appendLine("- ${tag.name}: ${tagCounts[tag] ?: 0}")
    }
    out.appendLine()

    out.appendLine("## Missing items")
    out.appendLine()
    val missingCertificates = missions.flatMap { mission ->
        Difficulty.entries.map { certificateId(mission.id, it) }
    }.filter { it !in set.certificates }.sorted()
    out.appendLine("- Certificates missing: ${missingCertificates.size}")
    missingCertificates.take(MAX_LISTED).forEach { out.appendLine("  - $it") }
    val pools = set.pools.keys.sorted()
    out.appendLine("- Layout pools on disk: ${pools.size}")
    out.appendLine()

    out.appendLine("## Validation")
    out.appendLine()
    val errors = problems.count { it.severity == Severity.ERROR }
    val warnings = problems.count { it.severity == Severity.WARNING }
    out.appendLine("- Errors: $errors, warnings: $warnings (lenient mode)")
    problems.sortedBy { it.toString() }.take(MAX_LISTED).forEach { out.appendLine("  - $it") }
    return out.toString()
}

private const val MAX_LISTED = 50

private fun emptyCatalogJson(): String =
    """{"contentVersion":0,"engineVersion":0,"aiVersion":0,"seas":[],"missions":[],"files":{}}"""
