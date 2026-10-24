package pt.tiago.nightfleet.audio

/**
 * The sound settings. Volumes are 0..1. The Settings screen will change
 * them through [GameAudio]; the defaults are music on at 40 %.
 */
data class AudioSettings(
    val sfxVolume: Float = 0.8f,
    val musicVolume: Float = 0.4f,
    val vibration: Boolean = true,
)
