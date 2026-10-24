package pt.tiago.nightfleet.audio

/**
 * Lets a key through at most once per [minIntervalMs]. Pure logic (the caller passes the clock),
 * so it can be unit tested on the JVM.
 */
internal class EffectThrottle<K>(private val minIntervalMs: Long) {

    private val lastAccepted = HashMap<K, Long>()

    /** Returns true and records [nowMs] if [key] may play now. */
    fun accept(key: K, nowMs: Long): Boolean {
        val last = lastAccepted[key]
        if (last != null && nowMs - last < minIntervalMs) return false
        lastAccepted[key] = nowMs
        return true
    }
}
