package com.rubertt.musiccontrol

import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

class AudioService : Service() {
    private val players = arrayOfNulls<MediaPlayer>(5)
    private val volumes = FloatArray(5) { 1f }
    private val fadeLevels = FloatArray(5) { 1f }
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeTokens = IntArray(5)
    private val channelId = "music_control_playback"
    private val notificationId = 42
    private lateinit var mediaSession: MediaSessionCompat
    private var currentTitle = "Music Control"
    private var currentChannel = 0

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Reprodução de áudio", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        mediaSession = MediaSessionCompat(this, "MusicControlSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { playAll() }
                override fun onPause() { pauseAll() }
                override fun onStop() { stopAll() }
            })
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        }
        updateMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_ALL" -> { stopAll(); return START_NOT_STICKY }
            "pause_all" -> { pauseAll(); return START_NOT_STICKY }
            "play_all" -> { playAll(); return START_NOT_STICKY }
        }
        val command = intent ?: return START_NOT_STICKY
        val action = command.getStringExtra("action") ?: command.action ?: return START_NOT_STICKY
        val channel = command.getIntExtra("channel", 0).coerceIn(0, 4)
        when (action) {
            "load" -> {
                val uri = command.getStringExtra("uri") ?: return START_NOT_STICKY
                currentTitle = command.getStringExtra("title") ?: currentTitle
                currentChannel = channel
                release(channel)
                try {
                    val player = MediaPlayer()
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    player.setDataSource(this, android.net.Uri.parse(uri))
                    player.setOnPreparedListener { updateNotification(); it.start(); updateNotification() }
                    player.setOnCompletionListener { updateNotification() }
                    players[channel] = player
                    updateMediaSession()
                    player.prepareAsync()
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

    private fun playAll() {
        players.forEach { player -> try { if (player != null && !player.isPlaying) player.start() } catch (_: Exception) {} }
        if (players.any { it != null }) startForeground(notificationId, notification())
        updateNotification()
    }

    private fun pauseAll() {
        players.forEach { player -> try { if (player?.isPlaying == true) player.pause() } catch (_: Exception) {} }
        updateNotification()
    }

    private fun stopAll() {
        for (i in 0..4) release(i)
        mediaSession.isActive = false
        updateMediaSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
                else if (!fadeIn) { try { if (player.isPlaying) player.pause() } catch (_: Exception) {} ; updateNotification() }
            }
        }
        fadeHandler.postAtTime(tick, i, android.os.SystemClock.uptimeMillis())
    }

    private fun release(i: Int) { cancelFade(i); try { players[i]?.release() } catch (_: Exception) {}; players[i] = null; fadeLevels[i] = 1f }
    private fun isPlaying() = players.any { it?.isPlaying == true }
    private fun hasPlayers() = players.any { it != null }

    private fun updateMediaSession() {
        if (!::mediaSession.isInitialized) return
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_TITLE, currentTitle)
                .putString(MediaMetadataCompat.METADATA_ARTIST, "Music Control · Canal ${currentChannel + 1}")
                .putString(MediaMetadataCompat.METADATA_ALBUM, "NERV AUDIO SYSTEM")
                .putLong(MediaMetadataCompat.METADATA_DURATION, 0L)
                .build()
        )
        val playing = isPlaying()
        val state = when {
            playing -> PlaybackStateCompat.STATE_PLAYING
            hasPlayers() -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions).setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
        mediaSession.isActive = hasPlayers()
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = isPlaying()
        val toggleAction = if (playing) "pause_all" else "play_all"
        val toggle = PendingIntent.getService(this, 1, Intent(this, AudioService::class.java).setAction(toggleAction), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AudioService::class.java).setAction("STOP_ALL"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        updateMediaSession()
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentText("Music Control · Canal ${currentChannel + 1}")
            .setContentIntent(open)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pausar" else "Reproduzir", toggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun updateNotification() {
        updateMediaSession()
        if (hasPlayers()) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(notificationId, notification())
        } else {
            mediaSession.isActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        for (i in 0..4) release(i)
        if (::mediaSession.isInitialized) { mediaSession.isActive = false; mediaSession.release() }
        super.onDestroy()
    }
}
