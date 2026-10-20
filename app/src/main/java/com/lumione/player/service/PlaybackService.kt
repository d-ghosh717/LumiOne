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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lumione.player.R
import com.lumione.player.player.AudioProviderRegistry
import com.lumione.player.player.AudioStreamResult
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.Track
import com.lumione.player.ui.MainActivity
import kotlinx.coroutines.*
import android.util.Log

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
        private const val TAG = "PlaybackService"
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && isPlaying) {
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                val pDuration = player.duration
                if (pDuration > 0 && pDuration != androidx.media3.common.C.TIME_UNSET) {
                    durationMs = pDuration
                } else if (durationMs <= 0) {
                    durationMs = queueManager.currentTrack()?.durationMs ?: 0L
                }
                bufferedPct = player.bufferedPercentage
                serviceListener?.onProgressUpdate(currentPositionMs, durationMs, bufferedPct)
                updatePlaybackState()
                progressHandler.postDelayed(this, 300)
            }
        }
    }

    interface PlaybackServiceListener {
        fun onTrackChanged(track: Track?)
        fun onPlayStateChanged(playing: Boolean)
        fun onProgressUpdate(currentMs: Long, durationMs: Long, bufferedPct: Int)
        fun onPlayerReady()
        fun onError(code: Int)
        fun onStreamUnavailable(track: Track, reason: String) {}
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
        serviceScope.cancel()
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

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")

        val dataSourceFactory = DefaultDataSource.Factory(
            applicationContext,
            httpDataSourceFactory
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(applicationContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true) // Handles Audio Focus automatically
            .setWakeMode(C.WAKE_MODE_NETWORK)          // Network & CPU wake lock during playback
            .setHandleAudioBecomingNoisy(true)         // Auto-pause when headphones unplugged
            .build()

        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (::player.isInitialized) {
                    val pDuration = player.duration
                    if (pDuration > 0 && pDuration != androidx.media3.common.C.TIME_UNSET) {
                        durationMs = pDuration
                        serviceListener?.onProgressUpdate(player.currentPosition.coerceAtLeast(0L), durationMs, player.bufferedPercentage)
                        updatePlaybackState()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "STATE_IDLE"
                    Player.STATE_BUFFERING -> "STATE_BUFFERING"
                    Player.STATE_READY -> "STATE_READY"
                    Player.STATE_ENDED -> "STATE_ENDED"
                    else -> "STATE_UNKNOWN($playbackState)"
                }
                Log.i(TAG, "[PlaybackService] onPlaybackStateChanged: $stateName (isPlaying=$isPlaying)")

                when (playbackState) {
                    Player.STATE_READY -> {
                        val pDuration = player.duration
                        if (pDuration > 0 && pDuration != androidx.media3.common.C.TIME_UNSET) {
                            durationMs = pDuration
                        } else if (durationMs <= 0) {
                            durationMs = queueManager.currentTrack()?.durationMs ?: 0L
                        }
                        Log.i(TAG, "[PlaybackService] ExoPlayer READY. durationMs=$durationMs")
                        serviceListener?.onPlayerReady()
                        serviceListener?.onProgressUpdate(player.currentPosition.coerceAtLeast(0L), durationMs, player.bufferedPercentage)
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
                Log.i(TAG, "[PlaybackService] onIsPlayingChanged: $playing (currentPos=${player.currentPosition}ms)")
                setPlaybackState(playing)
                if (playing) {
                    startProgressPolling()
                } else {
                    stopProgressPolling()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "[PlaybackService] ExoPlayer Error: errorCode=${error.errorCode} errorCodeName=${error.errorCodeName} message=${error.message}")
                var currentCause: Throwable? = error.cause
                var depth = 1
                while (currentCause != null && depth <= 5) {
                    Log.e(TAG, "[PlaybackService]   Caused by ($depth): ${currentCause.javaClass.name}: ${currentCause.message}")
                    if (currentCause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                        val body = try { String(currentCause.responseBody) } catch (_: Exception) { "" }
                        Log.e(TAG, "[PlaybackService]   HTTP Response details: code=${currentCause.responseCode} msg=${currentCause.responseMessage} body='$body' headers=${currentCause.headerFields}")
                    }
                    currentCause = currentCause.cause
                    depth++
                }

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
        Log.i(TAG, "[PlaybackService] loadAndPlay called for '${track.title}' (videoId=${track.videoId})")
        currentPositionMs = 0L
        durationMs = if (track.durationMs > 0) track.durationMs else 0L
        serviceListener?.onProgressUpdate(0L, durationMs, 0)
        notifyTrackChanged(track)

        val directStreamUrl = track.getEffectiveStreamUrl()
        // If track already has a valid direct stream (like a radio stream), play it directly
        if (directStreamUrl.isNotBlank() && (track.provider.equals("tunein", ignoreCase = true) || directStreamUrl.startsWith("http://") || directStreamUrl.startsWith("https://") && !directStreamUrl.contains("googlevideo.com"))) {
            Log.i(TAG, "[PlaybackService] Using existing stream URL for '${track.title}'")
            playStream(track, directStreamUrl)
            return
        }

        // Asynchronously resolve fresh stream from active AudioProvider
        serviceScope.launch {
            val audioProvider = AudioProviderRegistry.getProvider()
            Log.i(TAG, "[PlaybackService] Resolving fresh stream for '${track.title}' via ${audioProvider.providerName}")
            when (val result = audioProvider.resolveStreamUrl(track)) {
                is AudioStreamResult.Success -> {
                    Log.i(TAG, "[PlaybackService] Successfully resolved stream for '${track.title}', handing off to ExoPlayer")
                    val resolvedTrack = track.copy(streamUrl = result.streamUrl)
                    queueManager.updateCurrentTrack(resolvedTrack)
                    playStream(resolvedTrack, result.streamUrl, result.headers)
                }
                is AudioStreamResult.Unavailable -> {
                    Log.w(TAG, "[PlaybackService] Stream unavailable for '${track.title}': ${result.reason}")
                    setPlaybackState(false)
                    serviceListener?.onStreamUnavailable(track, result.reason)
                    updateNotification()
                }
                is AudioStreamResult.Error -> {
                    Log.e(TAG, "[PlaybackService] Resolution error for '${track.title}': ${result.throwable.message}", result.throwable)
                    setPlaybackState(false)
                    serviceListener?.onError(-1)
                    updateNotification()
                }
            }
        }
    }

    private fun playStream(track: Track, streamUrl: String, headers: Map<String, String> = emptyMap()) {
        Log.i(TAG, "[PlaybackService] playStream START for '${track.title}' (headers: ${headers.keys})")

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val customUserAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        if (!customUserAgent.isNullOrBlank()) {
            Log.i(TAG, "[PlaybackService] Setting User-Agent on ExoPlayer: $customUserAgent")
            httpDataSourceFactory.setUserAgent(customUserAgent)
        } else {
            httpDataSourceFactory.setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
        }

        val extraHeaders = headers.filterNot {
            it.key.equals("User-Agent", ignoreCase = true) ||
            it.key.startsWith("Sec-", ignoreCase = true) ||
            it.key.equals("Accept", ignoreCase = true)
        }
        if (extraHeaders.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(extraHeaders)
        }

        val dataSourceFactory = DefaultDataSource.Factory(applicationContext, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(streamUrl))

        player.setMediaSource(mediaSource)
        player.prepare()
        player.play()
        updateMediaSessionMetadata(track)
        notifyTrackChanged(track)
        updateNotification()
        Log.i(TAG, "[PlaybackService] player.setMediaSource, prepare() & play() dispatched")
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
            val safePos = positionMs.coerceAtLeast(0L)
            player.seekTo(safePos)
            currentPositionMs = safePos
            updatePlaybackState()
            serviceListener?.onProgressUpdate(currentPositionMs, durationMs, bufferedPct)
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
