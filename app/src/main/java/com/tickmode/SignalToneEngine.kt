package com.tickmode

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.util.Log

/** Plays the device's selected notification sound as a softer bell option. */
object SignalToneEngine {
    private const val TAG = "TickModeSound"
    private var activeBell: Ringtone? = null

    @Synchronized
    fun playSoftBell(context: Context): Boolean {
        stopSoftBell()
        return try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return false
            val ringtone = RingtoneManager.getRingtone(context.applicationContext, uri) ?: return false
            ringtone.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            if (Build.VERSION.SDK_INT >= 28) ringtone.setVolume(0.42f)
            activeBell = ringtone
            ringtone.play()
            Log.i(TAG, "Played system notification sound at reduced volume")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Could not play system notification sound", error)
            stopSoftBell()
            false
        }
    }

    @Synchronized
    fun stopSoftBell() {
        try {
            activeBell?.stop()
        } catch (error: Exception) {
            Log.w(TAG, "Could not stop notification sound", error)
        }
        activeBell = null
    }
}
