package pt.tiago.nightfleet.meta

import pt.tiago.nightfleet.data.LogEntry
import pt.tiago.nightfleet.data.LogResult
import pt.tiago.nightfleet.data.MatchKind

/** The filters of the logbook: campaign, modes, wins and pinned entries. */
enum class LogFilter { ALL, CAMPAIGN, MODES, WINS, PINNED }

/** Pure filtering, newest first. Modes are every kind outside the campaign. Wins count victories and solved puzzles. */
object LogbookFilter {
    fun matches(entry: LogEntry, filter: LogFilter): Boolean = when (filter) {
        LogFilter.ALL -> true
        LogFilter.CAMPAIGN -> entry.kind == MatchKind.CAMPAIGN
        LogFilter.MODES -> entry.kind != MatchKind.CAMPAIGN
        LogFilter.WINS -> entry.result == LogResult.WIN || entry.result == LogResult.SOLVED
        LogFilter.PINNED -> entry.pinned
    }

    fun apply(entries: List<LogEntry>, filter: LogFilter): List<LogEntry> =
        entries.filter { matches(it, filter) }.sortedByDescending { it.epochMillis }
}
