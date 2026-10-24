package pt.tiago.nightfleet.audio

import androidx.annotation.RawRes
import pt.tiago.nightfleet.R

/**
 * The sound effects of the game. Each one is a generated Ogg file (tools/generate_sounds.py).
 * [gain] is the relative loudness applied on top of the sound-effect volume: the files all share a
 * -3 dBFS peak, so the softer effects are trimmed here.
 */
enum class SoundEffect(@RawRes val resId: Int, val gain: Float) {
    SPLASH(R.raw.sfx_splash, 1f),
    HIT(R.raw.sfx_hit, 1f),
    SINK(R.raw.sfx_sink, 1f),
    SONAR_PING(R.raw.sfx_sonar_ping, 1f),
    TORPEDO_RUN(R.raw.sfx_torpedo_run, 1f),
    RECON_PLANE(R.raw.sfx_recon_plane, 1f),
    CROSS_BARRAGE(R.raw.sfx_cross_barrage, 1f),
    MINE(R.raw.sfx_mine, 1f),
    DECOY_REVEALED(R.raw.sfx_decoy_revealed, 1f),
    REPAIR(R.raw.sfx_repair, 1f),
    SHIP_MOVE(R.raw.sfx_ship_move, 1f),
    CLOCK_TICK(R.raw.sfx_clock_tick, 0.8f),
    STAR(R.raw.sfx_star, 1f),
    VICTORY(R.raw.sfx_victory, 1f),
    DEFEAT(R.raw.sfx_defeat, 1f),
    UI_TAP(R.raw.sfx_ui_tap, 0.35f),
}
