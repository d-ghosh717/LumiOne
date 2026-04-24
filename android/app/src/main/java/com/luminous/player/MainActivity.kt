package com.luminous.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var statusText: TextView
    private var mediaSession: MediaSession? = null
    private lateinit var notificationManager: NotificationManager

    private var currentTitle = "LumiOne"
    private var currentArtist = "Unknown"
    private var currentThumbnail = ""
    private var isPlaying = false

    companion object {
        const val SERVER_URL = "http://10.13.162.54:3000"
        const val CHANNEL_ID = "lumione_media"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_PLAY = "com.luminous.player.ACTION_PLAY"
        const val ACTION_PAUSE = "com.luminous.player.ACTION_PAUSE"
        const val ACTION_NEXT = "com.luminous.player.ACTION_NEXT"
        const val ACTION_PREV = "com.luminous.player.ACTION_PREV"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        initMediaSession()

        window.apply {
            statusBarColor = Color.parseColor("#0e0e10")
            navigationBarColor = Color.parseColor("#0e0e10")
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0e0e10")) }

        statusText = TextView(this).apply {
            text = "Connecting to LumiOne server…"
            setTextColor(Color.parseColor("#ba9eff"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(statusText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        webView = WebView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.INVISIBLE

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowContentAccess = true
                allowFileAccess = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = "$userAgentString LumiOneApp/1.0"
            }
            
            addJavascriptInterface(WebAppInterface(), "AndroidApp")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.visibility = View.VISIBLE
                    statusText.visibility = View.GONE
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        statusText.text = "❌ Cannot connect to server!\n\nTap to retry"
                        statusText.visibility = View.VISIBLE
                        view?.visibility = View.INVISIBLE
                    }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    android.util.Log.d("LumiOne-Web", "${consoleMessage?.message()} [${consoleMessage?.lineNumber()}]")
                    return true
                }
            }
        }

        root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        
        statusText.setOnClickListener {
            statusText.text = "Connecting to LumiOne server…"
            loadServer()
        }
        
        loadServer()
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun updateMetadata(title: String, artist: String, thumbnailUrl: String) {
            runOnUiThread {
                currentTitle = title
                currentArtist = artist
                currentThumbnail = thumbnailUrl
                updateNotification()
            }
        }
        @JavascriptInterface
        fun updatePlaybackState(playing: Boolean) {
            runOnUiThread {
                isPlaying = playing
                updateNotification()
            }
        }
    }

    private fun initMediaSession() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = MediaSession(this, "LumiOneSession").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() { webView.evaluateJavascript("if(typeof togglePlay === 'function') togglePlay()", null) }
                    override fun onPause() { webView.evaluateJavascript("if(typeof togglePlay === 'function') togglePlay()", null) }
                    override fun onSkipToNext() { webView.evaluateJavascript("if(typeof skipNext === 'function') skipNext()", null) }
                    override fun onSkipToPrevious() { webView.evaluateJavascript("if(typeof skipPrev === 'function') skipPrev()", null) }
                })
                isActive = true
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Media controls for LumiOne"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mediaSession?.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                    .build()
            )

            val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .build()
            )
            
            val playPauseAction = if (isPlaying) {
                Notification.Action.Builder(android.R.drawable.ic_media_pause, "Pause", getPendingIntent(ACTION_PAUSE)).build()
            } else {
                Notification.Action.Builder(android.R.drawable.ic_media_play, "Play", getPendingIntent(ACTION_PLAY)).build()
            }
            
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(currentTitle)
                .setContentText(currentArtist)
                .setOngoing(isPlaying)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Prev", getPendingIntent(ACTION_PREV)).build())
                .addAction(playPauseAction)
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", getPendingIntent(ACTION_NEXT)).build())
                .setStyle(Notification.MediaStyle().setMediaSession(mediaSession?.sessionToken).setShowActionsInCompactView(0, 1, 2))
                
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { this.action = action }
        return PendingIntent.getActivity(this, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        when (intent?.action) {
            ACTION_PLAY, ACTION_PAUSE -> webView.evaluateJavascript("if(typeof togglePlay === 'function') togglePlay()", null)
            ACTION_NEXT -> webView.evaluateJavascript("if(typeof skipNext === 'function') skipNext()", null)
            ACTION_PREV -> webView.evaluateJavascript("if(typeof skipPrev === 'function') skipPrev()", null)
        }
    }

    private fun loadServer() { webView.loadUrl(SERVER_URL) }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession?.release()
        }
        notificationManager.cancel(NOTIFICATION_ID)
        webView.destroy()
        super.onDestroy()
    }
    
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
