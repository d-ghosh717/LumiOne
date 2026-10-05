package com.lumione.player.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*

interface YouTubePlayerListener {
    fun onPlayerReady()
    fun onPlayStateChanged(isPlaying: Boolean)
    fun onProgressUpdate(currentMs: Long, durationMs: Long)
    fun onVideoEnded()
    fun onError(errorCode: Int)
}

class YouTubePlayerController(private val context: Context) {

    private var webView: WebView? = null
    private var isReady = false
    private var isCurrentlyPlaying = false
    private var currentDurationMs: Long = 0
    private var currentPositionMs: Long = 0
    private var listener: YouTubePlayerListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingVideoId: String? = null

    fun setListener(listener: YouTubePlayerListener?) {
        this.listener = listener
    }

    fun isPlaying(): Boolean = isCurrentlyPlaying

    fun getDurationMs(): Long = currentDurationMs

    fun getCurrentPositionMs(): Long = currentPositionMs

    @SuppressLint("SetJavaScriptEnabled")
    fun initialize(targetWebView: WebView) {
        this.webView = targetWebView

        targetWebView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                javaScriptCanOpenWindowsAutomatically = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            setBackgroundColor(0xFF08050F.toInt())
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // HTML loaded
                }
            }
            addJavascriptInterface(YouTubeBridge(), "AndroidBridge")
        }

        val htmlContent = buildPlayerHtml()
        targetWebView.loadDataWithBaseURL("https://lumione.app", htmlContent, "text/html", "UTF-8", null)
    }

    private fun buildPlayerHtml(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <meta name="referrer" content="strict-origin-when-cross-origin">
          <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html, body { width: 100%; height: 100%; background: #08050F; overflow: hidden; display: flex; align-items: center; justify-content: center; }
            #player { width: 100%; height: 100%; position: absolute; top:0; left:0; border: 0; }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <script>
            var tag = document.createElement('script');
            tag.src = "https://www.youtube.com/iframe_api";
            var firstScriptTag = document.getElementsByTagName('script')[0];
            firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

            var player;
            var isPlayerReady = false;

            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                width: '100%',
                height: '100%',
                host: 'https://www.youtube.com',
                playerVars: {
                  'autoplay': 1,
                  'playsinline': 1,
                  'controls': 1,
                  'rel': 0,
                  'fs': 0,
                  'modestbranding': 1,
                  'iv_load_policy': 3,
                  'enablejsapi': 1,
                  'origin': 'https://lumione.app'
                },
                events: {
                  'onReady': onPlayerReady,
                  'onStateChange': onPlayerStateChange,
                  'onError': onPlayerError
                }
              });
            }

            function onPlayerReady(event) {
              isPlayerReady = true;
              if (window.AndroidBridge) {
                window.AndroidBridge.onReady();
              }
            }

            function onPlayerStateChange(event) {
              if (window.AndroidBridge) {
                window.AndroidBridge.onStateChange(event.data);
              }
            }

            function onPlayerError(event) {
              if (window.AndroidBridge) {
                window.AndroidBridge.onError(event.data);
              }
            }

            function loadVideo(videoId) {
              if (player && player.loadVideoById) {
                player.loadVideoById(videoId);
              }
            }

            function cueVideo(videoId) {
              if (player && player.cueVideoById) {
                player.cueVideoById(videoId);
              }
            }

            function playVideo() {
              if (player && player.playVideo) {
                player.playVideo();
              }
            }

            function pauseVideo() {
              if (player && player.pauseVideo) {
                player.pauseVideo();
              }
            }

            function seekToSec(seconds) {
              if (player && player.seekTo) {
                player.seekTo(seconds, true);
              }
            }

            setInterval(function() {
              if (player && isPlayerReady && player.getCurrentTime && player.getDuration) {
                try {
                  var current = player.getCurrentTime() || 0;
                  var duration = player.getDuration() || 0;
                  if (window.AndroidBridge && duration > 0) {
                    window.AndroidBridge.onTimeUpdate(current, duration);
                  }
                } catch(e) {}
              }
            }, 500);
          </script>
        </body>
        </html>
        """.trimIndent()
    }

    fun loadVideo(videoId: String) {
        if (!isReady) {
            pendingVideoId = videoId
            return
        }
        mainHandler.post {
            webView?.evaluateJavascript("loadVideo('$videoId');", null)
        }
    }

    fun play() {
        mainHandler.post {
            webView?.evaluateJavascript("playVideo();", null)
        }
    }

    fun pause() {
        mainHandler.post {
            webView?.evaluateJavascript("pauseVideo();", null)
        }
    }

    fun seekTo(positionMs: Long) {
        val seconds = positionMs / 1000.0
        mainHandler.post {
            webView?.evaluateJavascript("seekToSec($seconds);", null)
        }
    }

    fun destroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            destroy()
        }
        webView = null
        listener = null
    }

    private inner class YouTubeBridge {
        @JavascriptInterface
        fun onReady() {
            mainHandler.post {
                isReady = true
                listener?.onPlayerReady()
                pendingVideoId?.let {
                    loadVideo(it)
                    pendingVideoId = null
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            mainHandler.post {
                // YT.PlayerState:
                // -1 (UNSTARTED), 0 (ENDED), 1 (PLAYING), 2 (PAUSED), 3 (BUFFERING), 5 (CUED)
                when (state) {
                    1 -> {
                        isCurrentlyPlaying = true
                        listener?.onPlayStateChanged(true)
                    }
                    2 -> {
                        isCurrentlyPlaying = false
                        listener?.onPlayStateChanged(false)
                    }
                    0 -> {
                        isCurrentlyPlaying = false
                        listener?.onPlayStateChanged(false)
                        listener?.onVideoEnded()
                    }
                    else -> {}
                }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            mainHandler.post {
                listener?.onError(code)
            }
        }

        @JavascriptInterface
        fun onTimeUpdate(currentTimeSec: Float, durationSec: Float) {
            mainHandler.post {
                currentPositionMs = (currentTimeSec * 1000).toLong()
                currentDurationMs = (durationSec * 1000).toLong()
                listener?.onProgressUpdate(currentPositionMs, currentDurationMs)
            }
        }
    }
}
