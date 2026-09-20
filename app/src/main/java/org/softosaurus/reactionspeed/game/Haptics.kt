package org.softosaurus.reactionspeed.game

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Thin vibration wrapper that hides the three generations of the Android API.
 *
 * Requires `android.permission.VIBRATE` (declared in the manifest since 3.x). Every call is
 * defensive: a device without a vibrator, or a revoked permission, must never take the game down.
 * [vibrate] is called from the render thread.
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        Log.w(TAG, "no vibrator available", e)
        null
    }

    /** True when the device can actually vibrate. */
    val hasVibrator: Boolean get() = vibrator?.hasVibrator() == true

    /** Buzzes for [durationMs] ms (legacy used 70 ms when the target appeared). */
    fun vibrate(durationMs: Long) {
        val v = vibrator ?: return
        if (durationMs <= 0L) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // SecurityException (permission revoked) or a vendor bug — never fatal for the game.
            Log.w(TAG, "vibrate failed", e)
        }
    }

    companion object {
        private const val TAG = "Haptics"

        /** Legacy pulse length when the target appears. */
        const val TARGET_PULSE_MS = 70L
    }
}
