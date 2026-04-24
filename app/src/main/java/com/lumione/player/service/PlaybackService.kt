package com.lumione.player.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.lumione.player.R
import com.lumione.player.bridge.AndroidBridge
import com.lumione.player.queue.QueueManager
import com.lumione.player.queue.Track
import com.lumione.player.ui.MainActivity

/**
 * LumiOne Foreground Playback Service
 *
 * Architecture:
 * - Runs as a Foreground Service with MediaSession for lock-screen controls
 * - Owns the hidden WebView hosting the YouTube IFrame Player API
 * - Receives events from JS → AndroidBridge → here
 * - Exposes IBinder (LumiBinder) for Activity binding
 */
class PlaybackService : Service(), AndroidBridge.PlayerEventListener {

    companion object {
        const val CHANNEL_ID = "lumione_playback"
        const val NOTIFICATION_ID = 101
        const val ACTION_PLAY = "com.lumione.PLAY"
        const val ACTION_PAUSE = "com.lumione.PAUSE"
        const val ACTION_NEXT = "com.lumione.NEXT"
        const val ACTION_PREV = "com.lumione.PREV"
        const val ACTION_STOP = "com.lumione.STOP"
    }

    // ─── Public state (observed by Activity) ─────────────────────────────────
    val queueManager = QueueManager()
    var isPlaying = false
        private set
    var currentPositionMs = 0L
        private set
    var durationMs = 0L
        private set
    var bufferedPct = 0
        private set

    // ─── Internal components ──────────────────────────────────────────────────
    private lateinit var webView: WebView
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var serviceListener: PlaybackServiceListener? = null

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

    // ─── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupAudioManager()
        setupMediaSession()
        setupWebView()
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
        mediaSession.release()
        webView.destroy()
        abandonAudioFocus()
        super.onDestroy()
    }

    fun setListener(listener: PlaybackServiceListener?) {
        serviceListener = listener
    }

    // ─── WebView Setup ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
            }

            // Inject the Android bridge
            addJavascriptInterface(
                AndroidBridge(this@PlaybackService),
                "Android"
            )

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Page is ready; IFrame API will call onYouTubeIframeAPIReady
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    // Attempt reload on error
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.reload()
                    }, 3000)
                }
            }

            webChromeClient = WebChromeClient()
        }

        // Load the bundled player HTML from assets
        webView.loadUrl("file:///android_asset/player.html")
    }

    // ─── Playback Control (Android → JS) ─────────────────────────────────────

    fun loadAndPlay(track: Track) {
        requestAudioFocus()
        evalJs("LumiPlayer.loadVideo('${track.videoId}', true)")
        updateMediaSessionMetadata(track)
        notifyTrackChanged(track)
        updateNotification()
    }

    fun loadAndQueue(track: Track) {
        evalJs("LumiPlayer.loadVideo('${track.videoId}', false)")
        updateMediaSessionMetadata(track)
    }

    fun pausePlayback() {
        evalJs("LumiPlayer.pause()")
        setPlaybackState(false)
    }

    fun resumePlayback() {
        requestAudioFocus()
        evalJs("LumiPlayer.play()")
    }

    fun seekTo(positionMs: Long) {
        evalJs("LumiPlayer.seek($positionMs)")
        currentPositionMs = positionMs
        updatePlaybackState()
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
        evalJs("LumiPlayer.setVolume($level)")
    }

    private fun evalJs(script: String) {
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript(script, null)
        }
    }

    // ─── AndroidBridge.PlayerEventListener ───────────────────────────────────

    override fun onReady() {
        serviceListener?.onPlayerReady()
        // Auto-start current track if any
        queueManager.currentTrack()?.let { loadAndPlay(it) }
    }

    override fun onEnd() {
        if (queueManager.hasNext()) {
            skipNext()
        } else {
            setPlaybackState(false)
        }
    }

    override fun onPlaying() = setPlaybackState(true)
    override fun onPaused() = setPlaybackState(false)
    override fun onBuffering() {}

    override fun onError(code: Int) {
        serviceListener?.onError(code)
        // Retry after 2s on certain errors
        if (code == 150 || code == 100) {
            Handler(Looper.getMainLooper()).postDelayed({
                // Reload the WebView player engine entirely
                webView.reload()
            }, 2000)
        } else if (code == 101 || code == 5) {
            skipNext()
        }
    }

    override fun onProgress(currentMs: Long, durationMs: Long, bufferedPct: Int) {
        this.currentPositionMs = currentMs
        this.durationMs = durationMs
        this.bufferedPct = bufferedPct
        serviceListener?.onProgressUpdate(currentMs, durationMs, bufferedPct)
        updatePlaybackState()
    }

    // ─── Audio Focus ──────────────────────────────────────────────────────────

    private fun setupAudioManager() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { focus ->
                    when (focus) {
                        AudioManager.AUDIOFOCUS_LOSS -> pausePlayback()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pausePlayback()
                        AudioManager.AUDIOFOCUS_GAIN -> resumePlayback()
                    }
                }
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        }
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
            val pi = PendingIntent.getService(
                this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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
