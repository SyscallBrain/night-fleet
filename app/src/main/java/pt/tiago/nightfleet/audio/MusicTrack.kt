package pt.tiago.nightfleet.audio

import androidx.annotation.RawRes
import pt.tiago.nightfleet.R

/** Ambient loops: the home theme and one per sea (tools/generate_music.py). */
enum class MusicTrack(@RawRes val resId: Int) {
    HOME(R.raw.music_home),
    SEA_01(R.raw.music_sea01),
    SEA_02(R.raw.music_sea02),
    SEA_03(R.raw.music_sea03),
    SEA_04(R.raw.music_sea04),
    SEA_05(R.raw.music_sea05),
    SEA_06(R.raw.music_sea06),
    SEA_07(R.raw.music_sea07),
    SEA_08(R.raw.music_sea08),
    SEA_09(R.raw.music_sea09),
    SEA_10(R.raw.music_sea10),
    SEA_11(R.raw.music_sea11),
    SEA_12(R.raw.music_sea12);

    companion object {
        /** The loop for sea [number], 1..12. */
        fun forSea(number: Int): MusicTrack {
            require(number in 1..12) { "sea number out of range: $number" }
            return entries[number]
        }
    }
}
