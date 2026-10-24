package pt.tiago.nightfleet.audio

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Vibration patterns. [timingsMs] alternates off and on durations, starting
 * with a delay of 0 ms, the format of [VibrationEffect.createWaveform].
 */
enum class HapticPattern(val timingsMs: LongArray) {
    /** A shot that hit. */
    HIT(longArrayOf(0, 25)),

    /** A ship going down: on 40 ms, off 60 ms, on 80 ms. */
    SINK(longArrayOf(0, 40, 60, 80)),

    /** One of your ships was hit. */
    OWN_SHIP_HIT(longArrayOf(0, 60)),

    /** A mine: two short pulses. */
    MINE(longArrayOf(0, 30, 60, 30)),
}

/**
 * Plays [HapticPattern]s. Does nothing when [enabled] is false, or on a device without a vibrator.
 * Callers decide when to vibrate (never for misses or in menus).
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = findVibrator(context.applicationContext)

    /** Vibration on or off (the vibration setting). */
    @Volatile
    var enabled: Boolean = true

    fun play(pattern: HapticPattern) {
        val device = vibrator ?: return
        if (!enabled) return
        try {
            if (!device.hasVibrator()) return
            device.vibrate(VibrationEffect.createWaveform(pattern.timingsMs, NO_REPEAT))
        } catch (e: SecurityException) {
            Log.w(TAG, "vibration not allowed", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibration failed", e)
        }
    }

    /** Stops a vibration that is still running. */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not cancel vibration", e)
        }
    }

    private fun findVibrator(context: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "no vibration service", e)
        null
    }

    private companion object {
        const val TAG = "NightFleetAudio"
        const val NO_REPEAT = -1
    }
}
