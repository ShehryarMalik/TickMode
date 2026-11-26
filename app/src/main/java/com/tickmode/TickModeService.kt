package com.tickmode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class TickModeService : Service() {
    companion object {
        const val ACTION_START = "com.tickmode.START"
        const val ACTION_PAUSE = "com.tickmode.PAUSE"
        const val ACTION_RESUME = "com.tickmode.RESUME"
        const val ACTION_STOP = "com.tickmode.STOP"
        const val ACTION_SIGNAL = "com.tickmode.SIGNAL"
        const val ACTION_UPDATE_SIGNAL = "com.tickmode.UPDATE_SIGNAL"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_VIBRATION = "vibration"
        const val EXTRA_PATTERN = "pattern"
        const val EXTRA_SOUND = "sound"
        const val EXTRA_TONE = "tone"
        const val EXTRA_CUSTOM_TONE_URI = "custom_tone_uri"
        const val CHANNEL_ID = "tickmode_session"
        const val NOTIFICATION_ID = 4101

        fun start(context: Context, intent: Intent) = ContextCompat.startForegroundService(context, intent)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private val signalRunnable = Runnable { deliverSignal() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val existing = SessionStore.current(this)
        startForeground(NOTIFICATION_ID, buildNotification(existing))
        if (existing?.status == "RUNNING") {
            acquireWakeLock()
            scheduleNext(existing)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopSession()
            ACTION_SIGNAL -> deliverSignal()
            ACTION_UPDATE_SIGNAL -> updateSignal(intent)
            else -> recoverIfNeeded()
        }
        return START_STICKY
    }

    private fun begin(intent: Intent) {
        // The timer is driven by a foreground service while holding a partial wake lock.
        // This supports short intervals while the display is off; battery use is higher.
        val interval = intent.getLongExtra(EXTRA_INTERVAL, 60_000L).coerceIn(1_000L, 86_400_000L)
        val now = System.currentTimeMillis()
        val session = TickSession(
            id = UUID.randomUUID().toString(), number = SessionStore.nextNumber(this), startedAt = now,
            intervalMs = interval,
            vibrationEnabled = intent.getBooleanExtra(EXTRA_VIBRATION, true),
            pattern = HapticEngine.normalizePattern(intent.getStringExtra(EXTRA_PATTERN) ?: "Medium"),
            soundEnabled = intent.getBooleanExtra(EXTRA_SOUND, true),
            tone = intent.getStringExtra(EXTRA_TONE) ?: "Beep",
            customToneUri = intent.getStringExtra(EXTRA_CUSTOM_TONE_URI) ?: "",
            status = "RUNNING", nextSignalAt = now + interval, remainingMs = interval,
            signalCount = 0, accumulatedActiveMs = 0L, activeSince = now
        )
        SessionStore.saveCurrent(this, session)
        acquireWakeLock()
        scheduleNext(session)
        updateNotification(session)
    }

    private fun pause() {
        val s = SessionStore.current(this) ?: return stopSelf()
        if (s.status != "RUNNING") return updateNotification(s)
        handler.removeCallbacks(signalRunnable)
        HapticEngine.cancel(this)
        val now = System.currentTimeMillis()
        val paused = s.copy(status = "PAUSED", remainingMs = (s.nextSignalAt - now).coerceAtLeast(0L),
            accumulatedActiveMs = s.elapsed(now), activeSince = 0L, nextSignalAt = 0L)
        SessionStore.saveCurrent(this, paused)
        releaseWakeLock()
        updateNotification(paused)
    }

    private fun resume() {
        val s = SessionStore.current(this) ?: return stopSelf()
        if (s.status != "PAUSED") return updateNotification(s)
        val now = System.currentTimeMillis()
        val running = s.copy(status = "RUNNING", activeSince = now,
            nextSignalAt = now + s.remainingMs.coerceAtLeast(1_000L))
        SessionStore.saveCurrent(this, running)
        acquireWakeLock()
        scheduleNext(running)
        updateNotification(running)
    }

    private fun stopSession() {
        handler.removeCallbacks(signalRunnable)
        HapticEngine.cancel(this)
        releaseWakeLock()
        stopAudio()
        val s = SessionStore.current(this)
        if (s != null) SessionStore.finish(this, s, "STOPPED")
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recoverIfNeeded() {
        val s = SessionStore.current(this)
        if (s == null) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (s.status == "RUNNING") {
            acquireWakeLock()
            val remaining = s.nextSignalAt - System.currentTimeMillis()
            if (remaining <= 0L) deliverSignal() else handler.postDelayed(signalRunnable, remaining)
        } else {
            releaseWakeLock()
        }
        updateNotification(s)
    }

    private fun updateSignal(intent: Intent) {
        val s = SessionStore.current(this) ?: return stopSelf()
        val updated = s.copy(
            vibrationEnabled = intent.getBooleanExtra(EXTRA_VIBRATION, s.vibrationEnabled),
            pattern = HapticEngine.normalizePattern(intent.getStringExtra(EXTRA_PATTERN) ?: s.pattern),
            soundEnabled = intent.getBooleanExtra(EXTRA_SOUND, s.soundEnabled),
            tone = intent.getStringExtra(EXTRA_TONE) ?: s.tone,
            customToneUri = intent.getStringExtra(EXTRA_CUSTOM_TONE_URI) ?: s.customToneUri
        )
        SessionStore.saveCurrent(this, updated)
        updateNotification(updated)
    }

    private fun scheduleNext(session: TickSession) {
        handler.removeCallbacks(signalRunnable)
        handler.postDelayed(signalRunnable, (session.nextSignalAt - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    private fun deliverSignal() {
        val s = SessionStore.current(this) ?: return stopSelf()
        if (s.status != "RUNNING") return updateNotification(s)
        if (s.vibrationEnabled) HapticEngine.play(this, s.pattern)
        if (s.soundEnabled) {
            if (s.customToneUri.isNotBlank()) playCustomTone(s.customToneUri) else playTone(s.tone)
        }
        val now = System.currentTimeMillis()
        val next = s.copy(signalCount = s.signalCount + 1, nextSignalAt = now + s.intervalMs, remainingMs = s.intervalMs)
        SessionStore.saveCurrent(this, next)
        scheduleNext(next)
        updateNotification(next)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TickMode:Session").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) { }
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
    }

    private fun playTone(tone: String) {
        SignalToneEngine.stopSoftBell()
        if (tone == "Soft Bell" && SignalToneEngine.playSoftBell(this)) return
        try {
            toneGenerator?.release()
            val generator = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 85)
            toneGenerator = generator
            val type = when (tone) {
                "Ack" -> ToneGenerator.TONE_PROP_ACK
                "Prompt" -> ToneGenerator.TONE_PROP_PROMPT
                else -> ToneGenerator.TONE_PROP_BEEP
            }
            generator.startTone(type, 220)
            handler.postDelayed({ try { generator.release(); if (toneGenerator === generator) toneGenerator = null } catch (_: Exception) { } }, 400)
        } catch (error: Exception) {
            android.util.Log.e("TickModeSound", "Could not play tone=$tone", error)
        }
    }

    private fun playCustomTone(uriString: String) {
        stopAudio()
        SignalToneEngine.stopSoftBell()
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setDataSource(this, Uri.parse(uriString))
            player.setOnCompletionListener { it.release(); if (mediaPlayer === it) mediaPlayer = null }
            player.setOnErrorListener { mp, _, _ -> mp.release(); if (mediaPlayer === mp) mediaPlayer = null; true }
            player.setOnPreparedListener { it.start() }
            player.prepareAsync()
        } catch (_: Exception) { stopAudio() }
    }

    private fun stopAudio() {
        SignalToneEngine.stopSoftBell()
        try { mediaPlayer?.stop() } catch (_: Exception) { }
        try { mediaPlayer?.release() } catch (_: Exception) { }
        mediaPlayer = null
        try { toneGenerator?.release() } catch (_: Exception) { }
        toneGenerator = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Active TickMode session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing status and controls for an active TickMode session"
                setShowBadge(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun actionPending(action: String, request: Int): PendingIntent {
        val i = Intent(this, SessionActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(this, request, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(session: TickSession?): Notification {
        val open = PendingIntent.getActivity(this, 4100, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val running = session?.status == "RUNNING"
        val paused = session?.status == "PAUSED"
        val state = when { running -> "RUNNING"; paused -> "PAUSED"; else -> "Ready" }
        val interval = session?.let { formatInterval(it.intervalMs) } ?: ""
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tickmode_mark)
            .setContentTitle(if (session != null) "TickMode · Session #${session.number}" else "TickMode")
            .setContentText(if (session != null) "$state · Every $interval · ${session.signalCount} signals" else "Preparing session")
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (session != null) "$state\nEvery $interval\n${session.signalCount} signals delivered" else "TickMode session"))
            .setContentIntent(open)
            .setOngoing(session != null)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (running && session != null) {
            builder.setWhen(session.nextSignalAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        }
        if (session != null) {
            if (running) builder.addAction(android.R.drawable.ic_media_pause, "Pause", actionPending(ACTION_PAUSE, 4110))
            if (paused) builder.addAction(android.R.drawable.ic_media_play, "Resume", actionPending(ACTION_RESUME, 4111))
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionPending(ACTION_STOP, 4112))
        }
        return builder.build()
    }

    private fun updateNotification(session: TickSession?) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(session))
    }

    private fun formatInterval(ms: Long): String = when {
        ms % 3_600_000L == 0L -> "${ms / 3_600_000L}h"
        ms % 60_000L == 0L -> "${ms / 60_000L}m"
        else -> "${ms / 1_000L}s"
    }

    override fun onDestroy() {
        handler.removeCallbacks(signalRunnable)
        HapticEngine.cancel(this)
        releaseWakeLock()
        stopAudio()
        super.onDestroy()
    }
}

class SessionActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val service = Intent(context, TickModeService::class.java).setAction(action)
        try { ContextCompat.startForegroundService(context, service) } catch (_: Exception) { }
    }
}
