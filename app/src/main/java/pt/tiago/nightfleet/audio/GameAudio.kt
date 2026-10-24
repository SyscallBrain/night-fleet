package pt.tiago.nightfleet.audio

import android.content.Context

/**
 * The three audio players of the game, wired to one [AudioSettings]. The activity owns it:
 * [pause] from onStop, [resume] from onStart, [release] from onDestroy.
 */
class GameAudio(context: Context) {

    val sounds = SoundPlayer(context)
    val music = MusicPlayer(context)
    val haptics = Haptics(context)

    var settings: AudioSettings = AudioSettings()
        private set

    init {
        apply(settings)
    }

    fun update(newSettings: AudioSettings) {
        settings = newSettings.copy(
            sfxVolume = newSettings.sfxVolume.coerceIn(0f, 1f),
            musicVolume = newSettings.musicVolume.coerceIn(0f, 1f),
        )
        apply(settings)
    }

    fun setSfxVolume(volume: Float) = update(settings.copy(sfxVolume = volume))

    fun setMusicVolume(volume: Float) = update(settings.copy(musicVolume = volume))

    fun setVibration(enabled: Boolean) = update(settings.copy(vibration = enabled))

    fun pause() = music.pause()

    fun resume() = music.resume()

    fun release() {
        music.release()
        sounds.release()
        haptics.cancel()
    }

    private fun apply(value: AudioSettings) {
        sounds.volume = value.sfxVolume
        music.volume = value.musicVolume
        haptics.enabled = value.vibration
    }
}
