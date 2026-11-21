package com.rubertt.musiccontrol

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AudioService : Service() {
    private val players = arrayOfNulls<MediaPlayer>(5)
    private val volumes = FloatArray(5) { 1f }
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
        val action = intent?.getStringExtra("action") ?: intent?.action ?: return START_NOT_STICKY
        val channel = intent.getIntExtra("channel", 0).coerceIn(0, 4)
        when (action) {
            "load" -> {
                val uri = intent.getStringExtra("uri") ?: return START_NOT_STICKY
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
            "play" -> { players[channel]?.let { if (!it.isPlaying) it.start() }; startForeground(notificationId, notification()) }
            "pause" -> players[channel]?.let { if (it.isPlaying) it.pause() }
            "stop" -> { players[channel]?.let { if (it.isPlaying) it.pause(); try { it.seekTo(0) } catch (_: Exception) {} } }
            "volume" -> { volumes[channel] = intent.getFloatExtra("value", 1f).coerceIn(0f, 1f); applyVolume(channel) }
            "master" -> {
                val v = intent.getFloatExtra("value", 1f).coerceIn(0f, 1f)
                for (i in 0..4) { volumes[i] = v; applyVolume(i) }
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun applyVolume(i: Int) { players[i]?.setVolume(volumes[i], volumes[i]) }
    private fun release(i: Int) { try { players[i]?.release() } catch (_: Exception) {}; players[i] = null }
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
