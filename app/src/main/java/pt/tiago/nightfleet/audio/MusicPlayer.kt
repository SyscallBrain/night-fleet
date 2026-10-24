package pt.tiago.nightfleet.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Looping ambient music. One track plays at a time. Switching tracks crossfades over
 * [CROSSFADE_MS] between two [MediaPlayer]s. [pause] and [resume] are meant for the activity's
 * onStop and onStart. The player follows audio focus: it ducks on a "can duck" request and pauses
 * on any other loss until focus comes back or the user starts music again.
 *
 * Use it from the main thread only: MediaPlayer callbacks, focus callbacks and fades all run there.
 */
class MusicPlayer(context: Context) {

    private class Slot(val track: MusicTrack, val player: MediaPlayer) {
        var prepared = false
    }

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** Music volume, 0..1. Default 0.4. Zero pauses the music. */
    var volume: Float = DEFAULT_VOLUME
        set(value) {
            field = value.coerceIn(0f, 1f)
            updatePlayback()
        }

    private var requested: MusicTrack? = null
    private var current: Slot? = null
    private var incoming: Slot? = null
    private var outgoing: Slot? = null
    private var fading = false
    private var fadeStartMs = 0L
    private var released = false
    private var activityPaused = false
    private var pausedByFocus = false
    private var ducked = false
    private var hasFocus = false
    private var focusRequest: AudioFocusRequest? = null

    private val fadeTick = Runnable { tickFade() }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    pausedByFocus = false
                    ducked = false
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ducked = true
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    hasFocus = false
                    pausedByFocus = true
                    ducked = false
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // The system has already taken focus back, so there is nothing to abandon.
                    hasFocus = false
                    pausedByFocus = true
                    ducked = false
                }
                else -> Unit
            }
            updatePlayback()
        }
    }

    /** Starts [track], crossfading from the current one. Asking for the playing track does nothing. */
    fun playTrack(track: MusicTrack) {
        if (released || requested == track) return
        requested = track
        pausedByFocus = false
        finishFade()
        incoming?.let { releaseSlot(it) }
        incoming = if (current?.track == track) null else createSlot(track)
        updatePlayback()
    }

    /** Called from the activity's onStop: pauses the music and gives up audio focus. */
    fun pause() {
        if (released) return
        activityPaused = true
        updatePlayback()
    }

    /** Called from the activity's onStart: resumes the music if it should be playing. */
    fun resume() {
        if (released) return
        activityPaused = false
        updatePlayback()
    }

    /** Stops and frees both players and gives up audio focus. Calls after this do nothing. */
    fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        current?.let { releaseSlot(it) }
        incoming?.let { releaseSlot(it) }
        outgoing?.let { releaseSlot(it) }
        current = null
        incoming = null
        outgoing = null
        fading = false
        abandonFocus()
    }

    private fun wantsToPlay(): Boolean =
        requested != null && !activityPaused && !pausedByFocus && volume > 0f

    private fun updatePlayback() {
        if (released) return
        if (!wantsToPlay()) {
            pausePlayers()
            abandonFocus()
            return
        }
        if (!hasFocus && !requestFocus()) {
            pausedByFocus = true
            pausePlayers()
            return
        }
        if (incoming?.prepared == true) beginCrossfade()
        current?.let { if (it.prepared && !isPlaying(it)) startSafely(it) }
        applyVolumes()
    }

    /** Starts the prepared incoming track and fades it in, fading the previous one out if it plays. */
    private fun beginCrossfade() {
        val next = incoming ?: return
        incoming = null
        val previous = current
        val fadingOut = previous?.takeIf { isPlaying(it) }
        previous?.takeIf { it !== fadingOut }?.let { releaseSlot(it) }
        current = next
        outgoing = fadingOut
        next.player.setVolume(0f, 0f)
        startSafely(next)
        fading = true
        fadeStartMs = SystemClock.uptimeMillis()
        handler.post(fadeTick)
    }

    private fun tickFade() {
        if (!fading) return
        val progress = ((SystemClock.uptimeMillis() - fadeStartMs).toFloat() / CROSSFADE_MS).coerceIn(0f, 1f)
        val target = effectiveVolume()
        current?.let { setVolume(it, target * progress) }
        outgoing?.let { setVolume(it, target * (1f - progress)) }
        if (progress >= 1f) finishFade() else handler.postDelayed(fadeTick, FADE_STEP_MS)
    }

    /** Ends a running crossfade at once: the outgoing player is released. */
    private fun finishFade() {
        handler.removeCallbacks(fadeTick)
        fading = false
        outgoing?.let { releaseSlot(it) }
        outgoing = null
        applyVolumes()
    }

    private fun applyVolumes() {
        if (fading) return
        current?.let { setVolume(it, effectiveVolume()) }
    }

    private fun effectiveVolume(): Float = if (ducked) volume * DUCK_FACTOR else volume

    private fun pausePlayers() {
        finishFade()
        current?.let { if (isPlaying(it)) pauseSafely(it) }
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        // minSdk is 26, so the focus request API is always available (no pre-O branch).
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
            .also { focusRequest = it }
        val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        hasFocus = granted
        return granted
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
    }

    private fun createSlot(track: MusicTrack): Slot? {
        return try {
            val player = MediaPlayer()
            val descriptor = appContext.resources.openRawResourceFd(track.resId)
                ?: throw IllegalStateException("no descriptor for $track")
            descriptor.use { afd -> player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length) }
            player.setAudioAttributes(attributes)
            player.isLooping = true
            player.setVolume(0f, 0f)
            val slot = Slot(track, player)
            player.setOnPreparedListener {
                if (released) return@setOnPreparedListener
                slot.prepared = true
                if (slot === incoming) updatePlayback()
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "player error $what/$extra for $track")
                handler.post { dropFailed(slot) }
                true
            }
            player.prepareAsync()
            slot
        } catch (e: Exception) {
            Log.w(TAG, "could not load $track", e)
            null
        }
    }

    private fun dropFailed(slot: Slot) {
        if (incoming === slot) incoming = null
        if (current === slot) current = null
        if (outgoing === slot) outgoing = null
        releaseSlot(slot)
    }

    private fun releaseSlot(slot: Slot) {
        slot.prepared = false
        try {
            slot.player.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "release of ${slot.track} failed", e)
        }
    }

    private fun isPlaying(slot: Slot): Boolean = try {
        slot.prepared && slot.player.isPlaying
    } catch (e: IllegalStateException) {
        false
    }

    private fun startSafely(slot: Slot) {
        try {
            slot.player.start()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not start ${slot.track}", e)
        }
    }

    private fun pauseSafely(slot: Slot) {
        try {
            slot.player.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not pause ${slot.track}", e)
        }
    }

    private fun setVolume(slot: Slot, level: Float) {
        try {
            slot.player.setVolume(level, level)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "could not set volume of ${slot.track}", e)
        }
    }

    private companion object {
        const val TAG = "NightFleetAudio"
        const val DEFAULT_VOLUME = 0.4f
        const val CROSSFADE_MS = 600L
        const val FADE_STEP_MS = 16L
        const val DUCK_FACTOR = 0.25f
    }
}
