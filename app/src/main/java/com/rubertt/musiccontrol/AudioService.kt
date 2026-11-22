package com.rubertt.musiccontrol

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import org.json.JSONArray

class AudioService : Service() {
    private val channelCount = 10
    private val players = arrayOfNulls<MediaPlayer>(channelCount)
    private val prepared = BooleanArray(channelCount)
    private val volumes = FloatArray(channelCount) { 1f }
    private val fadeLevels = FloatArray(channelCount) { 1f }
    private val titles = Array(channelCount) { "Canal vazio" }
    private val uris = arrayOfNulls<String>(channelCount)
    private val fadeHandler = Handler(Looper.getMainLooper())
    private val fadeTokens = IntArray(channelCount)
    private val channelId = "music_control_playback"
    private val notificationId = 42
    private lateinit var mediaSession: MediaSessionCompat
    private var currentTitle = "Music Control"
    private var currentChannel = 0
    private var masterVolume = 1f
    private var playlistMode = false
    private var currentIndex = -1
    private var fadeSeconds = 5f
    private var transitionStarted = false
    private var transitionToken = 0
    private var transitionRunnable: Runnable? = null
    private var manualActiveChannel = -1
    private var manualTransitionToken = 0
    private var manualTransitionRunnable: Runnable? = null

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
        val channel = command.getIntExtra("channel", 0).coerceIn(0, 9)
        when (action) {
            "playlist" -> startPlaylist(command.getStringExtra("playlist") ?: "[]", command.getFloatExtra("fadeSeconds", 5f))
            "load" -> {
                playlistMode = false
                if (manualActiveChannel == channel) manualActiveChannel = -1
                val uri = command.getStringExtra("uri") ?: return START_NOT_STICKY
                val title = command.getStringExtra("title") ?: "Áudio selecionado"
                currentTitle = title
                currentChannel = channel
                loadOne(channel, uri, title, command.getBooleanExtra("autoPlay", false))
            }
            "play" -> { playlistMode = false; playManual(channel, false, command.getLongExtra("durationMs", 3000L)) }
            "pause" -> { cancelFade(channel); players[channel]?.let { if (it.isPlaying) it.pause() } }
            "fade_in" -> { playlistMode = false; playManual(channel, true, command.getLongExtra("durationMs", 3000L)) }
            "fade_out" -> { playlistMode = false; fadeOutManual(channel, command.getLongExtra("durationMs", 3000L)) }
            "stop" -> { players[channel]?.let { if (it.isPlaying) it.pause(); try { it.seekTo(0) } catch (_: Exception) {} } }
            "volume" -> { volumes[channel] = command.getFloatExtra("value", 1f).coerceIn(0f, 1f); applyVolume(channel) }
            "master" -> { masterVolume = command.getFloatExtra("value", 1f).coerceIn(0f, 1f); for (i in 0 until channelCount) applyVolume(i) }
            "next" -> if (playlistMode) beginCrossfade(nextIndex())
        }
        updateNotification()
        return START_STICKY
    }

    private fun loadOne(index: Int, uri: String, title: String, startWhenReady: Boolean) {
        release(index)
        uris[index] = uri
        titles[index] = title
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(this, Uri.parse(uri))
            p.setOnPreparedListener {
                prepared[index] = true
                if (startWhenReady && (currentIndex == -1 || currentIndex == index)) {
                    currentIndex = index
                    it.start()
                    fadeLevels[index] = 1f
                    currentTitle = titles[index]
                    currentChannel = index
                    scheduleAutoTransition()
                }
                applyVolume(index)
                updateNotification()
            }
            p.setOnCompletionListener {
                if (playlistMode && index == currentIndex && !transitionStarted) beginCrossfade(nextIndex())
                else if (!playlistMode) updateNotification()
            }
            p.setOnErrorListener { _, _, _ -> release(index); updateNotification(); true }
            players[index] = p
            p.prepareAsync()
            startForeground(notificationId, notification())
        } catch (_: Exception) { release(index) }
    }

    private fun startPlaylist(json: String, seconds: Float) {
        stopPlaylistPlayers()
        playlistMode = true
        fadeSeconds = seconds.coerceIn(1f, 30f)
        currentIndex = -1
        transitionStarted = false
        val list = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        val count = minOf(list.length(), channelCount)
        if (count == 0) { playlistMode = false; updateNotification(); return }
        for (i in 0 until count) {
            val item = list.optJSONObject(i) ?: continue
            val uri = item.optString("uri")
            val title = item.optString("name", "Faixa ${i + 1}")
            if (uri.isNotBlank()) loadOne(i, uri, title, i == 0)
        }
        currentTitle = "Preparando playlist"
        currentChannel = 0
        startForeground(notificationId, notification())
    }

    private fun nextIndex(): Int {
        val loaded = (0 until channelCount).filter { uris[it] != null }
        if (loaded.isEmpty()) return -1
        val position = loaded.indexOf(currentIndex)
        return loaded[(position + 1).mod(loaded.size)]
    }

    private fun scheduleAutoTransition() {
        cancelTransition()
        if (!playlistMode || currentIndex !in 0 until channelCount) return
        val p = players[currentIndex] ?: return
        if (!prepared[currentIndex]) return
        val remaining = (p.duration - p.currentPosition).coerceAtLeast(0)
        val delay = (remaining - (fadeSeconds * 1000).toInt()).coerceAtLeast(0)
        val token = transitionToken
        val task = Runnable { if (token == transitionToken && playlistMode && !transitionStarted) beginCrossfade(nextIndex()) }
        transitionRunnable = task
        fadeHandler.postDelayed(task, delay.toLong())
    }

    private fun cancelTransition() {
        transitionToken++
        transitionRunnable?.let { fadeHandler.removeCallbacks(it) }
        transitionRunnable = null
    }

    private fun beginCrossfade(next: Int) {
        if (!playlistMode || next !in 0 until channelCount || next == currentIndex || transitionStarted) return
        val oldIndex = currentIndex
        val old = if (oldIndex in 0 until channelCount) players[oldIndex] else null
        val incoming = players[next] ?: return
        if (!prepared[next]) {
            val token = transitionToken
            fadeHandler.postDelayed({ if (token == transitionToken) beginCrossfade(next) }, 150L)
            return
        }
        transitionStarted = true
        cancelTransition()
        try { incoming.seekTo(0); incoming.setVolume(0f, 0f); if (!incoming.isPlaying) incoming.start() } catch (_: Exception) {}
        val duration = (fadeSeconds * 1000).toLong()
        val started = SystemClock.uptimeMillis()
        val token = ++transitionToken
        val task = object : Runnable {
            override fun run() {
                if (token != transitionToken) return
                val f = ((SystemClock.uptimeMillis() - started).toFloat() / duration).coerceIn(0f, 1f)
                fadeLevels[next] = f
                applyVolume(next)
                if (oldIndex in 0 until channelCount) {
                    fadeLevels[oldIndex] = 1f - f
                    applyVolume(oldIndex)
                }
                if (f < 1f) fadeHandler.postDelayed(this, 40L)
                else {
                    if (oldIndex in 0 until channelCount) release(oldIndex)
                    currentIndex = next
                    currentChannel = next
                    currentTitle = titles[next]
                    fadeLevels[next] = 1f
                    transitionStarted = false
                    updateNotification()
                    scheduleAutoTransition()
                }
            }
        }
        fadeHandler.post(task)
    }

    private fun playAll() {
        if (playlistMode && currentIndex >= 0) {
            players[currentIndex]?.let { if (!it.isPlaying) it.start() }
            scheduleAutoTransition()
        } else players.forEach { p -> try { if (p != null && !p.isPlaying) p.start() } catch (_: Exception) {} }
        if (hasPlayers()) startForeground(notificationId, notification())
        updateNotification()
    }

    private fun pauseAll() {
        cancelTransition()
        players.forEach { p -> try { if (p?.isPlaying == true) p.pause() } catch (_: Exception) {} }
        updateNotification()
    }

    private fun stopAll() {
        stopPlaylistPlayers()
        mediaSession.isActive = false
        updateMediaSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPlaylistPlayers() {
        cancelTransition()
        transitionStarted = false
        for (i in 0 until channelCount) release(i)
        currentIndex = -1
    }

    private fun applyVolume(i: Int) {
        val v = (volumes[i] * fadeLevels[i] * masterVolume).coerceIn(0f, 1f)
        try { players[i]?.setVolume(v, v) } catch (_: Exception) {}
    }


    private fun nextLoadedChannel(from: Int): Int {
        val loaded = (0 until channelCount).filter { uris[it] != null && prepared[it] }
        if (loaded.size < 2) return -1
        val pos = loaded.indexOf(from)
        return if (pos >= 0) loaded[(pos + 1) % loaded.size] else loaded.first()
    }

    private fun cancelManualTransition() {
        manualTransitionToken++
        manualTransitionRunnable?.let { fadeHandler.removeCallbacks(it) }
        manualTransitionRunnable = null
    }

    private fun playManual(target: Int, fadeIn: Boolean, durationMs: Long) {
        val incoming = players[target] ?: return
        if (!prepared[target]) return
        val oldIndex = manualActiveChannel
        val old = if (oldIndex in 0 until channelCount) players[oldIndex] else null
        if (oldIndex == target && old?.isPlaying == true) {
            if (fadeIn) startFade(target, true, durationMs)
            return
        }
        if (oldIndex !in 0 until channelCount || old == null || !old.isPlaying) {
            cancelFade(target)
            if (fadeIn) {
                fadeLevels[target] = 0f
                applyVolume(target)
                try { incoming.seekTo(0); incoming.start() } catch (_: Exception) { return }
                manualActiveChannel = target
                val started = SystemClock.uptimeMillis()
                val duration = durationMs.coerceIn(200L, 60000L)
                val token = ++manualTransitionToken
                val task = object : Runnable {
                    override fun run() {
                        if (token != manualTransitionToken) return
                        val f = ((SystemClock.uptimeMillis() - started).toFloat() / duration).coerceIn(0f, 1f)
                        fadeLevels[target] = f
                        applyVolume(target)
                        if (f < 1f) fadeHandler.postDelayed(this, 35L)
                        else { manualTransitionRunnable = null; updateNotification() }
                    }
                }
                manualTransitionRunnable = task
                fadeHandler.post(task)
            } else {
                fadeLevels[target] = 1f
                applyVolume(target)
                try { if (!incoming.isPlaying) incoming.start() } catch (_: Exception) { return }
                manualActiveChannel = target
            }
            currentChannel = target
            currentTitle = titles[target]
            startForeground(notificationId, notification())
            updateNotification()
            return
        }
        cancelManualTransition()
        cancelFade(oldIndex)
        cancelFade(target)
        val duration = durationMs.coerceIn(200L, 60000L)
        val started = SystemClock.uptimeMillis()
        val oldStart = fadeLevels[oldIndex]
        if (fadeIn) {
            fadeLevels[target] = 0f
            applyVolume(target)
            try { incoming.seekTo(0); if (!incoming.isPlaying) incoming.start() } catch (_: Exception) { return }
        } else {
            try { if (!incoming.isPlaying) incoming.start() } catch (_: Exception) { return }
        }
        val token = ++manualTransitionToken
        val task = object : Runnable {
            override fun run() {
                if (token != manualTransitionToken) return
                val f = ((SystemClock.uptimeMillis() - started).toFloat() / duration).coerceIn(0f, 1f)
                fadeLevels[oldIndex] = oldStart * (1f - f)
                applyVolume(oldIndex)
                if (fadeIn) {
                    fadeLevels[target] = f
                    applyVolume(target)
                }
                if (f < 1f) fadeHandler.postDelayed(this, 35L)
                else {
                    try { old.pause(); old.seekTo(0) } catch (_: Exception) {}
                    fadeLevels[oldIndex] = 1f
                    applyVolume(oldIndex)
                    if (!fadeIn) {
                        fadeLevels[target] = 1f
                        applyVolume(target)
                    }
                    manualActiveChannel = target
                    currentChannel = target
                    currentTitle = titles[target]
                    manualTransitionRunnable = null
                    updateNotification()
                }
            }
        }
        manualTransitionRunnable = task
        fadeHandler.post(task)
        startForeground(notificationId, notification())
    }

    private fun fadeOutManual(channel: Int, durationMs: Long) {
        if (channel == manualActiveChannel) {
            val next = nextLoadedChannel(channel)
            if (next >= 0) {
                playManual(next, true, durationMs)
                return
            }
            manualActiveChannel = -1
        }
        startFade(channel, false, durationMs)
    }

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
        val startedAt = SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (fadeTokens[i] != token) return
                val fraction = ((SystemClock.uptimeMillis() - startedAt).toFloat() / duration).coerceIn(0f, 1f)
                fadeLevels[i] = start + (end - start) * fraction
                applyVolume(i)
                if (fraction < 1f) fadeHandler.postAtTime(this, i, SystemClock.uptimeMillis() + 40L)
                else if (!fadeIn) { try { if (player.isPlaying) player.pause() } catch (_: Exception) {} ; updateNotification() }
            }
        }
        fadeHandler.postAtTime(tick, i, SystemClock.uptimeMillis())
    }

    private fun release(i: Int) {
        if (manualActiveChannel == i) manualActiveChannel = -1
        cancelFade(i)
        try { players[i]?.release() } catch (_: Exception) {}
        players[i] = null
        prepared[i] = false
        uris[i] = null
        fadeLevels[i] = 1f
    }

    private fun isPlaying() = players.any { try { it?.isPlaying == true } catch (_: Exception) { false } }
    private fun hasPlayers() = players.any { it != null }

    private fun updateMediaSession() {
        if (!::mediaSession.isInitialized) return
        mediaSession.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Music Control · Canal ${currentChannel + 1}")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "NERV AUDIO SYSTEM")
            .build())
        val playing = isPlaying()
        val state = when { playing -> PlaybackStateCompat.STATE_PLAYING; hasPlayers() -> PlaybackStateCompat.STATE_PAUSED; else -> PlaybackStateCompat.STATE_STOPPED }
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder().setActions(actions).setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f).build())
        mediaSession.isActive = hasPlayers()
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = isPlaying()
        val toggle = PendingIntent.getService(this, 1, Intent(this, AudioService::class.java).setAction(if (playing) "pause_all" else "play_all"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, AudioService::class.java).setAction("STOP_ALL"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        updateMediaSession()
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle)
            .setContentText("Music Control · Canal ${currentChannel + 1}")
            .setContentIntent(open).setOngoing(playing).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pausar" else "Reproduzir", toggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop)
            .setStyle(MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1)).build()
    }

    private fun updateNotification() {
        updateMediaSession()
        if (hasPlayers()) getSystemService(NotificationManager::class.java).notify(notificationId, notification())
        else { mediaSession.isActive = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        stopPlaylistPlayers()
        if (::mediaSession.isInitialized) { mediaSession.isActive = false; mediaSession.release() }
        super.onDestroy()
    }
}
