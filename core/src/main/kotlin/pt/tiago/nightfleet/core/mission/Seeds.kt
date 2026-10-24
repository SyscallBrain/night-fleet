package pt.tiago.nightfleet.core.mission

import pt.tiago.nightfleet.core.random.SplitMix64

/** Named sub-streams of an attempt. Each stream is independent of the others. */
object SeedStreams {
    enum class Stream(val id: Long) {
        /** Enemy and own layouts when they are not given explicitly. */
        LAYOUT(1),

        /** Admiral decisions. */
        AI(2),

        /** Who starts when the mission leaves it to the attempt. */
        FIRST_PLAYER(3),

        /** Engine-side randomness (none in the classic rules). */
        ENGINE(4),
    }

    /** Sub-seed for [stream] (and [index], e.g. the side) of an attempt. */
    fun seed(attemptSeed: Long, stream: Stream, index: Int = 0): Long =
        SplitMix64.split(attemptSeed, (stream.id shl 32) or index.toLong())

    fun rng(attemptSeed: Long, stream: Stream, index: Int = 0): SplitMix64 =
        SplitMix64(seed(attemptSeed, stream, index))

    /** Seed of attempt number [attemptIndex] of a level: `mix(levelSeed xor attemptIndex)`. */
    fun attemptSeed(levelSeed: Long, attemptIndex: Int): Long =
        SplitMix64.mix(levelSeed xor attemptIndex.toLong())

    /** Side (0 or 1) that starts, drawn from the FIRST_PLAYER stream. */
    fun firstPlayer(attemptSeed: Long): Int = rng(attemptSeed, Stream.FIRST_PLAYER).nextInt(2)
}
