package com.luminous.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import java.net.URL
import kotlin.concurrent.thread

class MediaPlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    var exoPlayer: ExoPlayer? = null
    private val binder = LocalBinder()

    private var currentTitle: String = "Not Playing"
    private var currentArtist: String = "LumiOne"
    private var currentThumbnailUrl: String = ""
    private var isPlaying: Boolean = false
    private var currentThumbnailBitmap: Bitmap? = null
    private var currentPositionMs: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "lumione_media_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_PLAY = "com.luminous.player.PLAY"
        const val ACTION_PAUSE = "com.luminous.player.PAUSE"
        const val ACTION_NEXT = "com.luminous.player.NEXT"
        const val ACTION_PREV = "com.luminous.player.PREV"
        const val ACTION_STOP = "com.luminous.player.STOP"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaPlaybackService = this@MediaPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LumiOne:AudioWakeLock"
        ).also { it.acquire() }

        exoPlayer = ExoPlayer.Builder(this).build()
        exoPlayer?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingStatus: Boolean) {
                isPlaying = isPlayingStatus
                updateSessionAndNotification()
                MainActivity.instance?.notifyExoStateChanged(isPlayingStatus)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    MainActivity.instance?.notifyExoTrackEnded()
                }
            }
        })

        mediaSession = MediaSessionCompat(this, "LumiOneSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { exoPlayer?.play() }
                override fun onPause() { exoPlayer?.pause() }
                override fun onSkipToNext() { MainActivity.instance?.handleMediaAction(ACTION_NEXT) }
                override fun onSkipToPrevious() { MainActivity.instance?.handleMediaAction(ACTION_PREV) }
                override fun onStop() { exoPlayer?.stop() }
                override fun onSeekTo(pos: Long) {
                    exoPlayer?.seekTo(pos)
                }
            })
            isActive = true
        }

        updatePlaybackState()
        updateMetadata()
        updateSessionAndNotification() // MUST be called in onCreate to prevent ForegroundService crash
        
        // Progress tracking thread
        thread {
            while (true) {
                if (isPlaying) {
                    val pos = exoPlayer?.currentPosition ?: 0L
                    val dur = exoPlayer?.duration ?: 0L
                    if (dur > 0) {
                        currentPositionMs = pos
                        MainActivity.instance?.updateExoProgress(pos, dur)
                    }
                }
                Thread.sleep(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> exoPlayer?.play()
            ACTION_PAUSE -> exoPlayer?.pause()
            ACTION_NEXT -> MainActivity.instance?.handleMediaAction(ACTION_NEXT)
            ACTION_PREV -> MainActivity.instance?.handleMediaAction(ACTION_PREV)
            ACTION_STOP -> {
                exoPlayer?.stop()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        mediaSession.isActive = false
        mediaSession.release()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun playExoMedia(audioUrl: String, title: String, artist: String, thumbnailUrl: String) {
        currentTitle = title
        currentArtist = artist
        val requireNewBitmap = (thumbnailUrl != currentThumbnailUrl)
        currentThumbnailUrl = thumbnailUrl

        exoPlayer?.setMediaItem(MediaItem.fromUri(audioUrl))
        exoPlayer?.prepare()
        exoPlayer?.play()

        if (requireNewBitmap && thumbnailUrl.isNotEmpty()) {
            thread {
                try {
                    val url = URL(thumbnailUrl)
                    currentThumbnailBitmap = BitmapFactory.decodeStream(url.openConnection().inputStream)
                    updateSessionAndNotification()
                } catch (e: Exception) {
                    currentThumbnailBitmap = null
                    updateSessionAndNotification()
                }
            }
        } else {
            updateSessionAndNotification()
        }
    }

    private fun updateSessionAndNotification() {
        updatePlaybackState()
        updateMetadata()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {}
    }

    private fun updatePlaybackState() {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackSpeed = if (isPlaying) 1f else 0f
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, currentPositionMs, playbackSpeed)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_STOP
                )
                .build()
        )
    }

    private fun updateMetadata() {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
        currentThumbnailBitmap?.let {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        }
        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun buildNotification(): Notification {
        val context = this
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_pause, "Pause",
                getPendingIntent(ACTION_PAUSE)
            ).build()
        } else {
            NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play, "Play",
                getPendingIntent(ACTION_PLAY)
            ).build()
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setContentIntent(pendingOpenApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)

        currentThumbnailBitmap?.let { builder.setLargeIcon(it) }

        builder.addAction(android.R.drawable.ic_media_previous, "Previous", getPendingIntent(ACTION_PREV))
        builder.addAction(playPauseAction)
        builder.addAction(android.R.drawable.ic_media_next, "Next", getPendingIntent(ACTION_NEXT))

        return builder.build()
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
