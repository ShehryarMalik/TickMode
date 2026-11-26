package com.tickmode

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/** Small, shared haptic path for both previews and active-session signals. */
object HapticEngine {
    private const val TAG = "TickModeHaptics"

    @Suppress("DEPRECATION")
    private fun defaultVibrator(context: Context): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    /** Migrate names saved by earlier builds to the three supported choices. */
    fun normalizePattern(pattern: String?): String = when (pattern?.trim()?.lowercase()) {
        "short", "single" -> "Short"
        "long" -> "Long"
        "medium", "double", "triple", "custom", "system" -> "Medium"
        else -> "Medium"
    }

    fun capabilitySummary(context: Context): String = try {
        val vibrator = defaultVibrator(context)
        when {
            !vibrator.hasVibrator() -> "No vibrator detected"
            vibrator.hasAmplitudeControl() -> "Vibrator available · amplitude control"
            else -> "Vibrator available · standard amplitude"
        }
    } catch (error: Exception) {
        Log.e(TAG, "Could not inspect vibrator", error)
        "Vibration status unavailable"
    }

    /**
     * Delivers one native pulse. Alarm usage is important: Android asks background
     * apps to identify vibration as alarm/notification/ringtone usage.
     */
    @Suppress("DEPRECATION")
    fun play(context: Context, pattern: String?): Boolean {
        return try {
            val vibrator = defaultVibrator(context)
            if (!vibrator.hasVibrator()) {
                Log.w(TAG, "No vibrator is available")
                return false
            }

            val durationMs = when (normalizePattern(pattern)) {
                "Short" -> 130L
                "Medium" -> 300L
                else -> 650L
            }
            val amplitude = if (vibrator.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE
            val effect = VibrationEffect.createOneShot(durationMs, amplitude)

            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }

            Log.i(TAG, "Vibration requested: ${normalizePattern(pattern)}, ${durationMs}ms, amplitude=$amplitude, usage=alarm")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Vibration request failed for pattern=$pattern", error)
            false
        }
    }

    fun cancel(context: Context) {
        try {
            defaultVibrator(context).cancel()
        } catch (error: Exception) {
            Log.w(TAG, "Could not cancel vibration", error)
        }
    }
}
