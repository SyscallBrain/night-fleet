package pt.tiago.nightfleet.audio

import android.content.Context
import android.content.res.Resources
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log

/**
 * Short sound effects through a [SoundPool] with at most [MAX_STREAMS] voices. Each effect is
 * decoded on first use, so nothing is loaded at start-up. An identical effect is played at most once
 * per [THROTTLE_MS], so a burst of hits does not pile up. A resource that fails to load is logged and
 * skipped; it never throws.
 */
class SoundPlayer(context: Context) {

    private val appContext = context.applicationContext
    private val pool: SoundPool? = try {
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    } catch (e: RuntimeException) {
        Log.w(TAG, "SoundPool unavailable, sound effects are off", e)
        null
    }

    private val throttle = EffectThrottle<SoundEffect>(THROTTLE_MS)
    private val sampleIds = HashMap<SoundEffect, Int>()
    private val sampleEffects = HashMap<Int, SoundEffect>()
    private val ready = HashSet<SoundEffect>()
    private val waiting = HashSet<SoundEffect>()
    private val failed = HashSet<SoundEffect>()
    private var released = false

    /** Sound-effect volume, 0..1. Applied on top of each effect's own [SoundEffect.gain]. */
    @Volatile
    var volume: Float = DEFAULT_VOLUME
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    init {
        pool?.setOnLoadCompleteListener { _, sampleId, status ->
            val effect = sampleEffects[sampleId] ?: return@setOnLoadCompleteListener
            if (status != 0) {
                Log.w(TAG, "could not load $effect (status $status)")
                failed.add(effect)
                waiting.remove(effect)
                return@setOnLoadCompleteListener
            }
            ready.add(effect)
            if (waiting.remove(effect)) playLoaded(effect, sampleId)
        }
    }

    /** Plays [effect] now, or as soon as its sample has finished loading. */
    fun play(effect: SoundEffect) {
        if (released || pool == null || failed.contains(effect)) return
        val level = volume * effect.gain
        if (level <= 0f) return
        if (!throttle.accept(effect, SystemClock.elapsedRealtime())) return

        val sampleId = sampleIds[effect] ?: load(effect) ?: return
        if (ready.contains(effect)) {
            playLoaded(effect, sampleId)
        } else {
            waiting.add(effect)
        }
    }

    /** Stops every sound and frees the native pool. Calls after this do nothing. */
    fun release() {
        if (released) return
        released = true
        pool?.release()
        sampleIds.clear()
        sampleEffects.clear()
        ready.clear()
        waiting.clear()
    }

    private fun load(effect: SoundEffect): Int? {
        val soundPool = pool ?: return null
        return try {
            val sampleId = soundPool.load(appContext, effect.resId, 1)
            if (sampleId == 0) {
                failed.add(effect)
                Log.w(TAG, "SoundPool refused $effect")
                null
            } else {
                sampleIds[effect] = sampleId
                sampleEffects[sampleId] = effect
                sampleId
            }
        } catch (e: Resources.NotFoundException) {
            failed.add(effect)
            Log.w(TAG, "missing resource for $effect", e)
            null
        }
    }

    private fun playLoaded(effect: SoundEffect, sampleId: Int) {
        val level = (volume * effect.gain).coerceIn(0f, 1f)
        if (level <= 0f) return
        pool?.play(sampleId, level, level, 1, 0, 1f)
    }

    private companion object {
        const val TAG = "NightFleetAudio"
        const val MAX_STREAMS = 4
        const val THROTTLE_MS = 50L
        const val DEFAULT_VOLUME = 0.8f
    }
}
