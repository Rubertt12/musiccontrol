package com.rubertt.musiccontrol

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

class AudioService : Service() {
    private val players = arrayOfNulls<MediaPlayer>(5)
    private val volumes = FloatArray(5) { 1f }
    private val fadeLevels = FloatArray(5) { 1f }
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeTokens = IntArray(5)
    private val channelId = "music_control_playback"
    private val notificationId = 42

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Reprodução de áudio", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_ALL") {
            for (i in 0..4) release(i)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == "pause_all") {
            players.forEach { player -> if (player?.isPlaying == true) player.pause() }
            updateNotification()
            return START_NOT_STICKY
        }
        if (intent?.action == "play_all") {
            players.forEach { player -> try { player?.start() } catch (_: Exception) {} }
            startForeground(notificationId, notification())
            updateNotification()
            return START_NOT_STICKY
        }
        val command = intent ?: return START_NOT_STICKY
        val action = command.getStringExtra("action") ?: command.action ?: return START_NOT_STICKY
        val channel = command.getIntExtra("channel", 0).coerceIn(0, 4)
        when (action) {
            "load" -> {
                val uri = command.getStringExtra("uri") ?: return START_NOT_STICKY
                release(channel)
                try {
                    val player = MediaPlayer()
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    player.setDataSource(this, android.net.Uri.parse(uri))
                    player.setOnPreparedListener { updateNotification(); it.start(); updateNotification() }
                    player.setOnCompletionListener { updateNotification() }
                    player.prepareAsync()
                    players[channel] = player
                    startForeground(notificationId, notification())
                } catch (_: Exception) { release(channel) }
            }
            "play" -> { cancelFade(channel); fadeLevels[channel] = 1f; applyVolume(channel); players[channel]?.let { if (!it.isPlaying) it.start() }; startForeground(notificationId, notification()) }
            "pause" -> { cancelFade(channel); players[channel]?.let { if (it.isPlaying) it.pause() } }
            "fade_in" -> startFade(channel, true, command.getLongExtra("durationMs", 3000L))
            "fade_out" -> startFade(channel, false, command.getLongExtra("durationMs", 3000L))
            "stop" -> { players[channel]?.let { if (it.isPlaying) it.pause(); try { it.seekTo(0) } catch (_: Exception) {} } }
            "volume" -> { volumes[channel] = command.getFloatExtra("value", 1f).coerceIn(0f, 1f); applyVolume(channel) }
            "master" -> {
                val v = command.getFloatExtra("value", 1f).coerceIn(0f, 1f)
                for (i in 0..4) { volumes[i] = v; applyVolume(i) }
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun applyVolume(i: Int) { val v = (volumes[i] * fadeLevels[i]).coerceIn(0f, 1f); players[i]?.setVolume(v, v) }
    private fun cancelFade(i: Int) { fadeTokens[i]++; fadeHandler.removeCallbacksAndMessages(i) }
    private fun startFade(i: Int, fadeIn: Boolean, durationMs: Long) {
        val player = players[i] ?: return
        cancelFade(i)
        val token = fadeTokens[i]
        val duration = durationMs.coerceIn(200L, 60000L)
        val start = if (fadeIn) 0f else fadeLevels[i]
        val end = if (fadeIn) 1f else 0f
        if (fadeIn) {
            fadeLevels[i] = 0f
            applyVolume(i)
            try { if (!player.isPlaying) player.start() } catch (_: Exception) {}
            startForeground(notificationId, notification())
        }
        val startedAt = android.os.SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (fadeTokens[i] != token) return
                val fraction = ((android.os.SystemClock.uptimeMillis() - startedAt).toFloat() / duration).coerceIn(0f, 1f)
                fadeLevels[i] = start + (end - start) * fraction
                applyVolume(i)
                if (fraction < 1f) fadeHandler.postAtTime(this, i, android.os.SystemClock.uptimeMillis() + 40L)
                else if (!fadeIn) { try { if (player.isPlaying) player.pause() } catch (_: Exception) {} }
            }
        }
        fadeHandler.postAtTime(tick, i, android.os.SystemClock.uptimeMillis())
    }
    private fun release(i: Int) { cancelFade(i); try { players[i]?.release() } catch (_: Exception) {}; players[i] = null; fadeLevels[i] = 1f }
    private fun isPlaying() = players.any { it?.isPlaying == true }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggle = PendingIntent.getService(this, 1, Intent(this, AudioService::class.java).setAction(if (isPlaying()) "pause_all" else "play_all"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AudioService::class.java).setAction("STOP_ALL"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Music Control")
            .setContentText(if (isPlaying()) "Reprodução em andamento" else "Mixer pronto")
            .setContentIntent(open).setOngoing(isPlaying())
            .addAction(if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (isPlaying()) "Pausar" else "Reproduzir", toggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar tudo", stop)
            .build()
    }
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification())
        if (!isPlaying() && players.all { it == null }) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { for (i in 0..4) release(i); super.onDestroy() }
}
