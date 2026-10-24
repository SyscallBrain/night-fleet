package pt.tiago.nightfleet.core.engine

/**
 * Shots of [side] for the star count: every fired square is one shot, a salvo of n is n,
 * a cross is the squares it actually fired, and a torpedo, a sonar or a reconnaissance is one each.
 */
fun shotsForStars(state: BattleState, side: Int): Int = state.sides[side].starShots

/**
 * Hits of [side] for the precision figure. A decoy hit counts as a hit until the decoy is revealed,
 * and as a miss afterwards. Torpedo hits count like any other hit.
 */
fun hitsForPrecision(state: BattleState, side: Int): Int {
    val enemy = state.sides[1 - side]
    return enemy.incoming.entries.count { record ->
        if (record.attacker != side) return@count false
        when (record.result) {
            is ShotResult.Hit, is ShotResult.Sunk -> true
            is ShotResult.DecoyHit ->
                enemy.decoys.none { it.cell == record.cell && it.revealed }
            else -> false
        }
    }
}
