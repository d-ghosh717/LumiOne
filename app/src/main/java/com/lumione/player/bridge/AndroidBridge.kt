package com.lumione.player.bridge

import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * JS → Android bridge.
 * Injected into WebView as "Android" so the player HTML can call:
 *   Android.onReady(), Android.onEnd(), Android.onProgress(…), etc.
 */
class AndroidBridge(private val listener: PlayerEventListener) {

    interface PlayerEventListener {
        fun onReady()
        fun onEnd()
        fun onPlaying()
        fun onPaused()
        fun onBuffering()
        fun onError(code: Int)
        fun onProgress(currentMs: Long, durationMs: Long, bufferedPct: Int)
    }

    private val mainScope = CoroutineScope(Dispatchers.Main)

    @JavascriptInterface
    fun onReady() {
        mainScope.launch { listener.onReady() }
    }

    @JavascriptInterface
    fun onEnd() {
        mainScope.launch { listener.onEnd() }
    }

    @JavascriptInterface
    fun onPlaying() {
        mainScope.launch { listener.onPlaying() }
    }

    @JavascriptInterface
    fun onPaused() {
        mainScope.launch { listener.onPaused() }
    }

    @JavascriptInterface
    fun onBuffering() {
        mainScope.launch { listener.onBuffering() }
    }

    @JavascriptInterface
    fun onError(code: Int) {
        mainScope.launch { listener.onError(code) }
    }

    @JavascriptInterface
    fun onProgress(currentMs: Long, durationMs: Long, bufferedPct: Int) {
        mainScope.launch { listener.onProgress(currentMs, durationMs, bufferedPct) }
    }
}
