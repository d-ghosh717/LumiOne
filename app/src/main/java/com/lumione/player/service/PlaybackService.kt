package com.lumione.player.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lumione.player.R
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.Track
import com.lumione.player.ui.MainActivity

/**
 * LumiOne Foreground Playback Service powered by Android Media3 / ExoPlayer.
 *
 * Handles:
 * - Native ExoPlayer playback with automatic audio focus & noisy audio management
 * - Foreground service lifecycle with wake locks for screen-off / background playback
 * - MediaSessionCompat with lock-screen & notification transport controls
 * - In-memory track queue and progress synchronization
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "lumione_playback"
        const val NOTIFICATION_ID = 101
        const val ACTION_PLAY = "com.lumione.PLAY"
        const val ACTION_PAUSE = "com.lumione.PAUSE"
        const val ACTION_NEXT = "com.lumione.NEXT"
        const val ACTION_PREV = "com.lumione.PREV"
        const val ACTION_STOP = "com.lumione.STOP"
    }

    // ─── Public State (Observed by Activity) ─────────────────────────────────
    val queueManager = QueueManager()
    var isPlaying = false
        private set
    var currentPositionMs = 0L
        private set
    var durationMs = 0L
        private set
    var bufferedPct = 0
        private set

    // ─── Internal Components ──────────────────────────────────────────────────
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private var serviceListener: PlaybackServiceListener? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && isPlaying) {
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                bufferedPct = player.bufferedPercentage
                serviceListener?.onProgressUpdate(currentPositionMs, durationMs, bufferedPct)
                updatePlaybackState()
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    interface PlaybackServiceListener {
        fun onTrackChanged(track: Track?)
        fun onPlayStateChanged(playing: Boolean)
        fun onProgressUpdate(currentMs: Long, durationMs: Long, bufferedPct: Int)
        fun onPlayerReady()
        fun onError(code: Int)
    }

    inner class LumiBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val binder = LumiBinder()

    // ─── Service Lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        setupPlayer()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumePlayback()
            ACTION_PAUSE -> pausePlayback()
            ACTION_NEXT -> skipNext()
            ACTION_PREV -> skipPrevious()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProgressPolling()
        if (::player.isInitialized) {
            player.stop()
            player.release()
        }
        mediaSession.release()
        super.onDestroy()
    }

    fun setListener(listener: PlaybackServiceListener?) {
        serviceListener = listener
    }

    // ─── ExoPlayer Setup ──────────────────────────────────────────────────────

    private fun setupPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        player = ExoPlayer.Builder(applicationContext)
            .setAudioAttributes(audioAttributes, true) // Handles Audio Focus automatically
            .setWakeMode(C.WAKE_MODE_NETWORK)          // Network & CPU wake lock during playback
            .setHandleAudioBecomingNoisy(true)         // Auto-pause when headphones unplugged
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        durationMs = player.duration.coerceAtLeast(0L)
                        serviceListener?.onPlayerReady()
                        updatePlaybackState()
                    }
                    Player.STATE_ENDED -> {
                        if (queueManager.hasNext()) {
                            skipNext()
                        } else {
                            setPlaybackState(false)
                        }
                    }
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                setPlaybackState(playing)
                if (playing) {
                    startProgressPolling()
                } else {
                    stopProgressPolling()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                serviceListener?.onError(error.errorCode)
                // Auto-skip failed track gracefully after brief delay if next track exists
                if (queueManager.hasNext()) {
                    progressHandler.postDelayed({ skipNext() }, 1500)
                } else {
                    setPlaybackState(false)
                }
            }
        })
    }

    // ─── Playback Controls ────────────────────────────────────────────────────

    fun loadAndPlay(track: Track) {
        if (!::player.isInitialized) return
        val streamUrl = track.getEffectiveStreamUrl()
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMediaId(track.trackId)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        updateMediaSessionMetadata(track)
        notifyTrackChanged(track)
        updateNotification()
    }

    fun loadAndQueue(track: Track) {
        queueManager.addToQueue(track)
    }

    fun pausePlayback() {
        if (::player.isInitialized) {
            player.pause()
        }
    }

    fun resumePlayback() {
        if (::player.isInitialized) {
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (::player.isInitialized) {
            player.seekTo(positionMs)
            currentPositionMs = positionMs
            updatePlaybackState()
        }
    }

    fun skipNext() {
        val next = queueManager.nextTrack() ?: return
        loadAndPlay(next)
    }

    fun skipPrevious() {
        if (currentPositionMs > 3000) {
            seekTo(0)
        } else {
            val prev = queueManager.previousTrack() ?: return
            loadAndPlay(prev)
        }
    }

    fun setVolume(level: Int) {
        if (::player.isInitialized) {
            player.volume = (level.coerceIn(0, 100) / 100f)
        }
    }

    // ─── Progress Polling ─────────────────────────────────────────────────────

    private fun startProgressPolling() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressPolling() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    // ─── MediaSession ─────────────────────────────────────────────────────────

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "LumiOne").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = resumePlayback()
                override fun onPause() = pausePlayback()
                override fun onSkipToNext() = skipNext()
                override fun onSkipToPrevious() = skipPrevious()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stopSelf()
            })
            isActive = true
        }
    }

    private fun updateMediaSessionMetadata(track: Track) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, currentPositionMs, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun setPlaybackState(playing: Boolean) {
        isPlaying = playing
        serviceListener?.onPlayStateChanged(playing)
        updatePlaybackState()
        updateNotification()
    }

    private fun notifyTrackChanged(track: Track) {
        serviceListener?.onTrackChanged(track)
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LumiOne Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Music playback controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val track = queueManager.currentTrack()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun pendingAction(action: String, icon: Int, label: String): NotificationCompat.Action {
            val intent = Intent(this, PlaybackService::class.java).apply { this.action = action }
            val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(
                    this, action.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                PendingIntent.getService(
                    this, action.hashCode(), intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            return NotificationCompat.Action(icon, label, pi)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track?.title ?: "LumiOne")
            .setContentText(track?.artist ?: "Ready to play")
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(pendingAction(ACTION_PREV, R.drawable.ic_skip_prev, "Previous"))
            .addAction(
                pendingAction(
                    if (isPlaying) ACTION_PAUSE else ACTION_PLAY,
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    if (isPlaying) "Pause" else "Play"
                )
            )
            .addAction(pendingAction(ACTION_NEXT, R.drawable.ic_skip_next, "Next"))
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
