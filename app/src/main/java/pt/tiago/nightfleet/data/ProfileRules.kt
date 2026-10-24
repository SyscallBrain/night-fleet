package pt.tiago.nightfleet.data

import java.time.LocalDate

/** Pure rules over [Profile]. No I/O, no Android, fully covered by JVM tests. */

fun Profile.totalStars(): Int = variants.values.sumOf { it.stars.coerceIn(0, 3) }

fun Profile.hardBossesWon(): Int = defeatedAdmirals[Difficulty.HARD]?.size ?: 0

/** The derived rank (never stored). */
fun Profile.rank(): Rank = Ranks.rankFor(totalStars(), hardBossesWon())

/**
 * Starts the campaign again. Clears variants, sea unlocks, boss series, defeated and met admirals.
 * Keeps settings (not in this model), formations, daily history, records, statistics, achievements,
 * paints and the logbook (a separate file), as required for "repor campanha".
 */
fun Profile.resetCampaign(): Profile = copy(
    variants = emptyMap(),
    unlockedSeas = Difficulty.entries.associateWith { 1 },
    bossSeries = emptyMap(),
    defeatedAdmirals = emptyMap(),
    metAdmirals = emptySet(),
)

/**
 * Records the stars of a daily challenge. Keeps the best result of the day and prunes days that
 * fall outside the retention window of [DAILY_RETENTION_DAYS] ending at [date].
 */
fun Profile.withDailyResult(date: LocalDate, difficulty: Difficulty, kind: ChallengeKind, stars: Int): Profile {
    val key = date.toString()
    val day = dailyHistory[key] ?: DailyDay()
    val byKind = day.best[difficulty].orEmpty()
    val best = maxOf(byKind[kind] ?: 0, stars.coerceIn(0, 3))
    val updatedDay = DailyDay(day.best + (difficulty to (byKind + (kind to best))))
    val earliest = date.minusDays(DAILY_RETENTION_DAYS - 1L)
    val kept = (dailyHistory + (key to updatedDay)).filterKeys { storedKey ->
        val storedDate = parseDailyKey(storedKey)
        storedDate != null && !storedDate.isBefore(earliest)
    }
    return copy(dailyHistory = kept)
}

fun parseDailyKey(key: String): LocalDate? = runCatching { LocalDate.parse(key) }
    .getOrNull()
    ?.takeIf { it.toString() == key }

/** Clamps impossible values to the nearest valid one. Invalid data is repaired, never rejected. */
object ProfileValidator {
    private const val MAX_LIST = 200
    private const val MAX_BOARD = 12

    fun clamp(profile: Profile): Profile {
        val seas = Difficulty.entries.associateWith { (profile.unlockedSeas[it] ?: 1).coerceIn(1, SEAS_TOTAL) }
        val paints = profile.unlockedPaints
        val selected = if (profile.selectedPaint == DEFAULT_PAINT || profile.selectedPaint in paints) {
            profile.selectedPaint
        } else {
            DEFAULT_PAINT
        }
        return profile.copy(
            variants = profile.variants.mapValues { (_, v) ->
                v.copy(
                    stars = v.stars.coerceIn(0, 3),
                    bestShots = v.bestShots?.coerceAtLeast(0),
                    wins = v.wins.coerceAtLeast(0),
                    losses = v.losses.coerceAtLeast(0),
                    attemptIndex = v.attemptIndex.coerceAtLeast(0),
                )
            },
            unlockedSeas = seas,
            bossSeries = profile.bossSeries.mapValues { (_, b) ->
                b.copy(
                    wins = b.wins.coerceAtLeast(0),
                    losses = b.losses.coerceAtLeast(0),
                    shotsPerDuel = b.shotsPerDuel.takeLast(MAX_LIST).map { it.coerceAtLeast(0) },
                )
            },
            achievements = profile.achievements.mapValues { (_, a) ->
                AchievementProgress(
                    unlockedAtEpochDay = a.unlockedAtEpochDay?.takeIf { it >= 0 },
                    counters = a.counters.mapValues { it.value.coerceAtLeast(0) },
                )
            },
            stats = profile.stats.mapValues { (_, s) -> clampStats(s) },
            formations = profile.formations.mapValues { (_, list) ->
                list.takeLast(MAX_FORMATIONS_PER_KEY).map { formation ->
                    Formation(formation.placements.map { p ->
                        p.copy(
                            row = p.row.coerceIn(0, MAX_BOARD - 1),
                            col = p.col.coerceIn(0, MAX_BOARD - 1),
                            rotation = p.rotation.coerceIn(0, 3),
                        )
                    })
                }
            },
            selectedPaint = selected,
            dailyHistory = profile.dailyHistory
                .filterKeys { parseDailyKey(it) != null }
                .mapValues { (_, day) ->
                    DailyDay(day.best.mapValues { (_, byKind) -> byKind.mapValues { it.value.coerceIn(0, 3) } })
                },
            patrolBest = profile.patrolBest.mapValues { it.value.coerceAtLeast(0) },
            tournamentBest = profile.tournamentBest.mapValues { it.value.coerceAtLeast(0) },
            endlessSolved = profile.endlessSolved.mapValues { it.value.coerceAtLeast(0) },
            freePlayRecord = profile.freePlayRecord.mapValues { (_, r) ->
                RecordCount(r.wins.coerceAtLeast(0), r.losses.coerceAtLeast(0))
            },
            duoSessions = profile.duoSessions.takeLast(DUO_HISTORY).map { d ->
                d.copy(
                    firstWins = d.firstWins.coerceAtLeast(0),
                    secondWins = d.secondWins.coerceAtLeast(0),
                    draws = d.draws.coerceAtLeast(0),
                )
            },
            committedMatchIds = profile.committedMatchIds.takeLast(COMMITTED_MATCH_HISTORY),
        )
    }

    private fun clampStats(s: DifficultyStats): DifficultyStats {
        val battles = s.battles.coerceAtLeast(0)
        val shots = s.shots.coerceAtLeast(0)
        return DifficultyStats(
            battles = battles,
            wins = s.wins.coerceIn(0, battles),
            shots = shots,
            hits = s.hits.coerceIn(0, shots),
            longestHitStreak = s.longestHitStreak.coerceAtLeast(0),
            shipsSunkByClass = s.shipsSunkByClass.mapValues { it.value.coerceAtLeast(0) },
            weaponsUsed = s.weaponsUsed.mapValues { it.value.coerceAtLeast(0) },
            puzzlesSolved = s.puzzlesSolved.coerceAtLeast(0),
            playTimeSeconds = s.playTimeSeconds.coerceAtLeast(0),
            shotHeatmap = List(HEATMAP_CELLS) { i -> s.shotHeatmap.getOrElse(i) { 0 }.coerceAtLeast(0) },
        )
    }
}
