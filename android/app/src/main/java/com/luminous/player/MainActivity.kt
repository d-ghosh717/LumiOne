package com.luminous.player

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import android.content.Intent
import android.content.Context
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.regex.Pattern
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var webView: WebView
    private var mediaService: MediaPlaybackService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MediaPlaybackService.LocalBinder
            mediaService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
        }
    }

    // Cache the decipher function operations so we don't re-fetch player.js every time
    private var cachedDecipherOps: List<DecipherOp>? = null
    private var cachedPlayerJsUrl: String? = null
    private var cachedNTransformFunc: String? = null

    data class DecipherOp(val func: String, val arg: Int)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Start bounded service
        val intent = Intent(this, MediaPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Enable hardware acceleration for video/media
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        webView = WebView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Enable hardware acceleration on the WebView itself
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        setContentView(webView)
        setupImmersiveMode()

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("luminous.app")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        WebView.setWebContentsDebuggingEnabled(true)

        // CRITICAL: Enable third-party cookies so YouTube IFrame player works
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            databaseEnabled = true
            // Allow inline media playback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                mediaPlaybackRequiresUserGesture = false
            }
            // Important for YouTube embeds
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        webView.addJavascriptInterface(NativeHttpBridge(), "NativeHttp")
        webView.addJavascriptInterface(NativeMediaBridge(), "NativeMedia")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                return assetLoader.shouldInterceptRequest(request.url)
            }
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("LumiOneJS", "${it.messageLevel()}: ${it.message()}")
                }
                return true
            }
        }

        webView.setBackgroundColor(android.graphics.Color.parseColor("#0d0d18"))
        window.decorView.setBackgroundColor(android.graphics.Color.parseColor("#0d0d18"))
        webView.loadUrl("https://luminous.app/assets/index.html")
    }

    // ── Native HTTP Bridge ───────────────────────────────────
    inner class NativeHttpBridge {

        @JavascriptInterface
        fun post(requestId: String, url: String, body: String, extraHeaders: String) {
            Thread {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.doOutput = true

                    if (extraHeaders.isNotEmpty()) {
                        try {
                            val headers = JSONObject(extraHeaders)
                            headers.keys().forEach { key ->
                                conn.setRequestProperty(key, headers.getString(key))
                            }
                        } catch (_: Exception) {}
                    }

                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

                    val responseCode = conn.responseCode
                    val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                    sendResponse(requestId, responseBody, responseCode)
                } catch (e: Exception) {
                    sendError(requestId, e.message ?: "Unknown error")
                }
            }.start()
        }

        @JavascriptInterface
        fun get(requestId: String, url: String) {
            Thread {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000

                    val responseCode = conn.responseCode
                    val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                    sendResponse(requestId, responseBody, responseCode)
                } catch (e: Exception) {
                    sendError(requestId, e.message ?: "Unknown error")
                }
            }.start()
        }

        /**
         * Fetch audio URL from a Piped/Invidious API instance.
         * This is a reliable fallback for when the IFrame player fails on certain videos.
         */
        @JavascriptInterface
        fun getPipedAudio(requestId: String, videoId: String) {
            Thread {
                val pipedInstances = listOf(
                    "https://pipedapi.kavin.rocks",
                    "https://pipedapi.r4fo.com",
                    "https://api.piped.privacyredirect.com",
                    "https://pipedapi.darkness.services",
                    "https://pipedapi.in.projectsegfault.com"
                )

                val invidiousInstances = listOf(
                    "https://inv.nadeko.net",
                    "https://invidious.nerdvpn.de",
                    "https://invidious.jing.rocks",
                    "https://vid.puffyan.us"
                )

                // Try Piped instances first
                for (instance in pipedInstances) {
                    try {
                        Log.d("LumiOneNative", "Trying Piped: $instance")
                        val url = "$instance/streams/$videoId"
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                        if (conn.responseCode == 200) {
                            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                            val json = JSONObject(body)
                            val audioStreams = json.optJSONArray("audioStreams")
                            if (audioStreams != null && audioStreams.length() > 0) {
                                // Find best audio stream (prefer m4a/mp4, then highest bitrate)
                                var bestUrl = ""
                                var bestBitrate = 0
                                var bestMime = ""
                                for (i in 0 until audioStreams.length()) {
                                    val stream = audioStreams.getJSONObject(i)
                                    val streamUrl = stream.optString("url", "")
                                    val bitrate = stream.optInt("bitrate", 0)
                                    val mime = stream.optString("mimeType", stream.optString("type", "audio/mp4"))
                                    if (streamUrl.isNotEmpty() && bitrate > bestBitrate) {
                                        bestUrl = streamUrl
                                        bestBitrate = bitrate
                                        bestMime = mime
                                    }
                                }
                                if (bestUrl.isNotEmpty()) {
                                    Log.d("LumiOneNative", "Piped audio found: $bestMime @ ${bestBitrate}bps from $instance")
                                    val result = JSONObject()
                                    result.put("status", "OK")
                                    result.put("url", bestUrl)
                                    result.put("mimeType", bestMime)
                                    result.put("bitrate", bestBitrate)
                                    result.put("source", instance)
                                    sendResponse(requestId, result.toString(), 200)
                                    return@Thread
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.w("LumiOneNative", "Piped $instance failed: ${e.message}")
                    }
                }

                // Try Invidious instances
                for (instance in invidiousInstances) {
                    try {
                        Log.d("LumiOneNative", "Trying Invidious: $instance")
                        val url = "$instance/api/v1/videos/$videoId"
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0")

                        if (conn.responseCode == 200) {
                            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                            val json = JSONObject(body)
                            val adaptiveFormats = json.optJSONArray("adaptiveFormats")
                            if (adaptiveFormats != null) {
                                var bestUrl = ""
                                var bestBitrate = 0
                                var bestMime = ""
                                for (i in 0 until adaptiveFormats.length()) {
                                    val fmt = adaptiveFormats.getJSONObject(i)
                                    val mime = fmt.optString("type", "")
                                    if (!mime.startsWith("audio/")) continue
                                    val streamUrl = fmt.optString("url", "")
                                    val bitrate = fmt.optInt("bitrate", 0)
                                    if (streamUrl.isNotEmpty() && bitrate > bestBitrate) {
                                        bestUrl = streamUrl
                                        bestBitrate = bitrate
                                        bestMime = mime
                                    }
                                }
                                if (bestUrl.isNotEmpty()) {
                                    Log.d("LumiOneNative", "Invidious audio found from $instance")
                                    val result = JSONObject()
                                    result.put("status", "OK")
                                    result.put("url", bestUrl)
                                    result.put("mimeType", bestMime)
                                    result.put("bitrate", bestBitrate)
                                    result.put("source", instance)
                                    sendResponse(requestId, result.toString(), 200)
                                    return@Thread
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.w("LumiOneNative", "Invidious $instance failed: ${e.message}")
                    }
                }

                sendError(requestId, "All Piped/Invidious instances failed")
            }.start()
        }

        /**
         * Dedicated YouTube audio stream extractor.
         * Fetches the YouTube page, extracts player response, deciphers signatures.
         */
        @JavascriptInterface
        fun getYouTubeAudio(requestId: String, videoId: String) {
            Thread {
                try {
                    Log.d("LumiOneNative", "Extracting audio for: $videoId")

                    // Step 1: Fetch the YouTube watch page
                    val pageUrl = "https://www.youtube.com/watch?v=$videoId&has_verified=1&bpctr=9999999999"
                    val html = fetchUrl(pageUrl, mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Cookie" to "CONSENT=PENDING+999"
                    ))

                    Log.d("LumiOneNative", "Page fetched: ${html.length} chars")

                    // Step 2: Extract ytInitialPlayerResponse
                    val playerJson = extractPlayerResponse(html)
                        ?: throw Exception("Could not extract player response")
                    
                    val playerObj = JSONObject(playerJson)
                    Log.d("LumiOneNative", "Player response extracted")

                    // Step 3: Check playability
                    val status = playerObj.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
                    if (status != "OK") {
                        throw Exception("Playability: $status")
                    }

                    // Step 4: Get streaming data
                    val streamingData = playerObj.optJSONObject("streamingData")
                        ?: throw Exception("No streaming data")
                    
                    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                        ?: JSONArray()

                    // Step 5: Extract player.js URL for signature deciphering
                    val playerJsUrl = extractPlayerJsUrl(html)
                    Log.d("LumiOneNative", "Player JS: $playerJsUrl")

                    // Step 6: Get/cache decipher operations if needed
                    var decipherOps = cachedDecipherOps
                    if (playerJsUrl != null && (decipherOps == null || cachedPlayerJsUrl != playerJsUrl)) {
                        val playerJs = fetchUrl("https://www.youtube.com$playerJsUrl", mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ))
                        decipherOps = extractDecipherOps(playerJs)
                        if (decipherOps != null) {
                            cachedDecipherOps = decipherOps
                            cachedPlayerJsUrl = playerJsUrl
                            Log.d("LumiOneNative", "Decipher ops extracted: ${decipherOps.size} operations")
                        }
                    }

                    // Step 7: Build audio formats list
                    val audioFormats = JSONArray()
                    for (i in 0 until adaptiveFormats.length()) {
                        val fmt = adaptiveFormats.getJSONObject(i)
                        val mimeType = fmt.optString("mimeType", "")
                        if (!mimeType.startsWith("audio/")) continue

                        var url = fmt.optString("url", "")
                        
                        // If no direct URL, try to decipher signatureCipher
                        if (url.isEmpty()) {
                            val cipher = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
                            if (cipher.isNotEmpty() && decipherOps != null) {
                                url = decipherSignatureCipher(cipher, decipherOps)
                            }
                        }

                        if (url.isNotEmpty()) {
                            val result = JSONObject()
                            result.put("url", url)
                            result.put("mimeType", mimeType)
                            result.put("bitrate", fmt.optInt("bitrate", 0))
                            audioFormats.put(result)
                        }
                    }

                    Log.d("LumiOneNative", "Audio formats found: ${audioFormats.length()}")

                    val response = JSONObject()
                    response.put("status", "OK")
                    response.put("formats", audioFormats)
                    sendResponse(requestId, response.toString(), 200)

                } catch (e: Exception) {
                    Log.e("LumiOneNative", "YouTube extraction failed: ${e.message}")
                    sendError(requestId, e.message ?: "Extraction failed")
                }
            }.start()
        }

        // ── Helpers ──────────────────────────────────────────

        private fun sendResponse(requestId: String, data: String, statusCode: Int) {
            val encoded = Base64.encodeToString(data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            webView.post {
                webView.evaluateJavascript(
                    "window._onNativeResponse('$requestId', '$encoded', $statusCode)", null
                )
            }
        }

        private fun sendError(requestId: String, message: String) {
            val msg = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
            webView.post {
                webView.evaluateJavascript(
                    "window._onNativeError('$requestId', '$msg')", null
                )
            }
        }

        private fun fetchUrl(url: String, headers: Map<String, String> = emptyMap()): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        }

        /**
         * Extract ytInitialPlayerResponse JSON from YouTube page HTML
         */
        private fun extractPlayerResponse(html: String): String? {
            val patterns = listOf(
                "var ytInitialPlayerResponse\\s*=\\s*",
                "ytInitialPlayerResponse\\s*=\\s*",
                "window\\[\"ytInitialPlayerResponse\"\\]\\s*=\\s*"
            )
            
            for (pat in patterns) {
                val regex = Pattern.compile(pat)
                val matcher = regex.matcher(html)
                if (matcher.find()) {
                    val start = matcher.end()
                    return extractJsonObject(html, start)
                }
            }
            return null
        }

        /**
         * Extract a complete JSON object starting at position `start` in the string
         */
        private fun extractJsonObject(str: String, start: Int): String? {
            if (start >= str.length || str[start] != '{') return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until str.length) {
                val c = str[i]
                if (escaped) { escaped = false; continue }
                if (c == '\\') { escaped = true; continue }
                if (c == '"' && !escaped) { inString = !inString; continue }
                if (inString) continue
                if (c == '{') depth++
                if (c == '}') { depth--; if (depth == 0) return str.substring(start, i + 1) }
            }
            return null
        }

        /**
         * Extract player.js URL from YouTube page HTML
         */
        private fun extractPlayerJsUrl(html: String): String? {
            val patterns = listOf(
                "\"jsUrl\"\\s*:\\s*\"([^\"]+)\"",
                "\"/s/player/[^\"]+base\\.js\""
            )
            for (pat in patterns) {
                val matcher = Pattern.compile(pat).matcher(html)
                if (matcher.find()) {
                    return matcher.group(1) ?: matcher.group(0)?.trim('"')
                }
            }
            return null
        }

        /**
         * Extract signature decipher operations from YouTube's player.js
         */
        private fun extractDecipherOps(playerJs: String): List<DecipherOp>? {
            try {
                val funcPattern = Pattern.compile(
                    """(?:function\s+\w+|var\s+\w+\s*=\s*function)\s*\(\s*(\w+)\s*\)\s*\{\s*\1\s*=\s*\1\.split\(\s*""\s*\)\s*;(.+?)\s*return\s+\1\.join\(\s*""\s*\)"""
                )
                val funcMatcher = funcPattern.matcher(playerJs)
                if (!funcMatcher.find()) {
                    Log.w("LumiOneNative", "Decipher function not found")
                    return null
                }

                val operations = funcMatcher.group(2) ?: return null

                val helperMatch = Pattern.compile("""(\w+)\.\w+\(\w+,\d+\)""").matcher(operations)
                if (!helperMatch.find()) return null
                val helperName = helperMatch.group(1) ?: return null

                val escapedHelper = Pattern.quote(helperName)
                val helperPattern = Pattern.compile(
                    """var\s+$escapedHelper\s*=\s*\{(.+?)\};""",
                    Pattern.DOTALL
                )
                val helperMatcher = helperPattern.matcher(playerJs)
                if (!helperMatcher.find()) return null
                val helperBody = helperMatcher.group(1) ?: return null

                val methodMap = mutableMapOf<String, String>()
                val methodPattern = Pattern.compile(
                    """(\w+)\s*:\s*function\s*\([^)]*\)\s*\{([^}]+)\}"""
                )
                val methodMatcher = methodPattern.matcher(helperBody)
                while (methodMatcher.find()) {
                    val name = methodMatcher.group(1) ?: continue
                    val body = methodMatcher.group(2) ?: continue
                    methodMap[name] = when {
                        body.contains("reverse") -> "reverse"
                        body.contains("splice") -> "splice"
                        else -> "swap"
                    }
                }

                val ops = mutableListOf<DecipherOp>()
                val opPattern = Pattern.compile(
                    """$escapedHelper\.(\w+)\(\w+,(\d+)\)"""
                )
                val opMatcher = opPattern.matcher(operations)
                while (opMatcher.find()) {
                    val method = opMatcher.group(1) ?: continue
                    val arg = opMatcher.group(2)?.toIntOrNull() ?: continue
                    val func = methodMap[method] ?: continue
                    ops.add(DecipherOp(func, arg))
                }

                return if (ops.isNotEmpty()) ops else null
            } catch (e: Exception) {
                Log.e("LumiOneNative", "Decipher parse error: ${e.message}")
                return null
            }
        }

        /**
         * Decipher a signatureCipher string using extracted operations
         */
        private fun decipherSignatureCipher(cipher: String, ops: List<DecipherOp>): String {
            val params = mutableMapOf<String, String>()
            cipher.split("&").forEach { part ->
                val eq = part.indexOf("=")
                if (eq > 0) {
                    params[URLDecoder.decode(part.substring(0, eq), "UTF-8")] =
                        URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                }
            }

            val encodedSig = params["s"] ?: return ""
            val sigParam = params["sp"] ?: "sig"
            val baseUrl = params["url"] ?: return ""

            val sig = applyDecipherOps(encodedSig.toCharArray(), ops)

            return "$baseUrl&$sigParam=$sig"
        }

        private fun applyDecipherOps(arr: CharArray, ops: List<DecipherOp>): String {
            var result = arr.toMutableList()
            for (op in ops) {
                when (op.func) {
                    "reverse" -> result.reverse()
                    "splice" -> {
                        for (i in 0 until op.arg.coerceAtMost(result.size)) {
                            result.removeAt(0)
                        }
                    }
                    "swap" -> {
                        val idx = op.arg % result.size
                        val tmp = result[0]
                        result[0] = result[idx]
                        result[idx] = tmp
                    }
                }
            }
            return result.joinToString("")
        }
    }

    // ── Native Media Bridge ──────────────────────────────────
    inner class NativeMediaBridge {
        @JavascriptInterface
        fun updateState(isPlaying: Boolean, title: String, artist: String, coverUrl: String) {
            // Deprecated: UI now managed directly by ExoPlayer's listener, but keep for legacy HTML fallback if any.
        }

        @JavascriptInterface
        fun playViaExo(videoId: String, title: String, artist: String, coverUrl: String) {
            Thread {
                // 1. First try the highly robust native YouTube signature decipherer
                var audioUrl = fetchYouTubeAudioNative(videoId)

                // 2. Fallback to public Piped/Invidious APIs if native extraction fails
                if (audioUrl == null) {
                    audioUrl = fetchBestPipedAudioUrl(videoId)
                }

                if (audioUrl != null && isBound) {
                    runOnUiThread {
                        mediaService?.playExoMedia(audioUrl!!, title, artist, coverUrl)
                    }
                } else {
                    runOnUiThread {
                        webView.evaluateJavascript("window.UI && window.UI.showToast('ExoPlayer: Stream fetch failed')", null)
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun exoControl(action: String, payload: Long) {
            runOnUiThread {
                if (!isBound) return@runOnUiThread
                when (action) {
                    "PLAY" -> mediaService?.exoPlayer?.play()
                    "PAUSE" -> mediaService?.exoPlayer?.pause()
                    "SEEK" -> mediaService?.exoPlayer?.seekTo(payload)
                }
            }
        }


        @JavascriptInterface
        fun updateTime(positionSeconds: Double, durationSeconds: Double) {
            // Handled natively by ExoPlayer thread now!
        }


        /**
         * Opens the Android native share sheet with the YouTube link and track info.
         */
        @JavascriptInterface
        fun shareTrack(title: String, artist: String, videoId: String) {
            val shareText = "🎵 $title\nby $artist\n\nhttps://www.youtube.com/watch?v=$videoId\n\nPlaying on LumiOne"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, "$title \u2014 $artist")
            }
            runOnUiThread {
                startActivity(Intent.createChooser(intent, "Share via"))
            }
        }

        /**
         * Downloads the song audio to Music/LumiOne/ via Android DownloadManager.
         * Fetches the best audio URL from Piped, then enqueues the download.
         */
        @JavascriptInterface
        fun downloadTrack(videoId: String, title: String, artist: String) {
            Thread {
                try {
                    Log.d("LumiOneNative", "Download requested: $title")
                    val audioUrl = fetchBestPipedAudioUrl(videoId)
                    if (audioUrl != null) {
                        enqueueDownload(audioUrl, title, artist)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.UI && window.UI.showToast('\u2705 Download started \u2014 check notifications')", null
                            )
                        }
                    } else {
                        webView.post {
                            webView.evaluateJavascript(
                                "window.UI && window.UI.showToast('\u274c Download failed \u2014 try again')", null
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LumiOneNative", "Download error: \${e.message}")
                    val msg = (e.message ?: "Unknown error").replace("'", "")
                    webView.post {
                        webView.evaluateJavascript(
                            "window.UI && window.UI.showToast('Download error: $msg')", null
                        )
                    }
                }
            }.start()
        }

        private fun fetchYouTubeAudioNative(videoId: String): String? {
            try {
                val pageUrl = "https://www.youtube.com/watch?v=$videoId&has_verified=1&bpctr=9999999999"
                val html = fetchUrl(pageUrl, mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Cookie" to "CONSENT=PENDING+999"
                ))
                val playerJson = extractPlayerResponse(html) ?: return null
                val playerObj = JSONObject(playerJson)
                val status = playerObj.optJSONObject("playabilityStatus")?.optString("status", "") ?: ""
                if (status != "OK") return null
                val streamingData = playerObj.optJSONObject("streamingData") ?: return null
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                
                val playerJsUrl = extractPlayerJsUrl(html)
                var decipherOps = cachedDecipherOps
                if (playerJsUrl != null && (decipherOps == null || cachedPlayerJsUrl != playerJsUrl)) {
                    val playerJs = fetchUrl("https://www.youtube.com$playerJsUrl", mapOf(
                        "User-Agent" to "Mozilla/5.0"
                    ))
                    decipherOps = extractDecipherOps(playerJs)
                    if (decipherOps != null) {
                        cachedDecipherOps = decipherOps
                        cachedPlayerJsUrl = playerJsUrl
                    }
                }

                var bestUrl = ""
                var bestBitrate = 0
                for (i in 0 until adaptiveFormats.length()) {
                    val fmt = adaptiveFormats.getJSONObject(i)
                    val mimeType = fmt.optString("mimeType", "")
                    if (!mimeType.startsWith("audio/")) continue

                    var url = fmt.optString("url", "")
                    if (url.isEmpty()) {
                        val cipher = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
                        if (cipher.isNotEmpty() && decipherOps != null) {
                            url = decipherSignatureCipher(cipher, decipherOps)
                        }
                    }

                    val bitrate = fmt.optInt("bitrate", 0)
                    if (url.isNotEmpty() && bitrate > bestBitrate && mimeType.contains("mp4")) {
                        bestUrl = url; bestBitrate = bitrate
                    }
                }
                
                // Fallback to non-mp4 if needed
                if (bestUrl.isEmpty()) {
                    for (i in 0 until adaptiveFormats.length()) {
                        val fmt = adaptiveFormats.getJSONObject(i)
                        val mimeType = fmt.optString("mimeType", "")
                        if (!mimeType.startsWith("audio/")) continue
                        var url = fmt.optString("url", "")
                        if (url.isEmpty()) {
                            val cipher = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
                            if (cipher.isNotEmpty() && decipherOps != null) {
                                url = decipherSignatureCipher(cipher, decipherOps)
                            }
                        }
                        val bitrate = fmt.optInt("bitrate", 0)
                        if (url.isNotEmpty() && bitrate > bestBitrate) {
                            bestUrl = url; bestBitrate = bitrate
                        }
                    }
                }
                
                return if (bestUrl.isNotEmpty()) bestUrl else null
            } catch (e: Exception) {
                Log.e("LumiOneNative", "Native YT extraction failed: ${e.message}")
                return null
            }
        }

        private fun fetchBestPipedAudioUrl(videoId: String): String? {
            val pipedInstances = listOf(
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.smnz.de",
                "https://pi.ggtyler.dev/api",
                "https://api.piped.privacyredirect.com",
                "https://pipedapi.lunar.icu",
                "https://piped-api.lunar.icu",
                "https://pipedapi.r4fo.com"
            )
            for (instance in pipedInstances) {
                try {
                    val conn = URL("$instance/streams/$videoId").openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                        val json = JSONObject(body)
                        val streams = json.optJSONArray("audioStreams") ?: continue
                        var bestUrl = ""
                        var bestBitrate = 0
                        for (i in 0 until streams.length()) {
                            val s = streams.getJSONObject(i)
                            val url = s.optString("url", "")
                            val br = s.optInt("bitrate", 0)
                            val mime = s.optString("mimeType", "")
                            if (url.isNotEmpty() && br > bestBitrate && mime.contains("mp4")) {
                                bestUrl = url; bestBitrate = br
                            }
                        }
                        if (bestUrl.isEmpty()) {
                            for (i in 0 until streams.length()) {
                                val s = streams.getJSONObject(i)
                                val url = s.optString("url", "")
                                val br = s.optInt("bitrate", 0)
                                if (url.isNotEmpty() && br > bestBitrate) {
                                    bestUrl = url; bestBitrate = br
                                }
                            }
                        }
                        if (bestUrl.isNotEmpty()) return bestUrl
                    }
                    conn.disconnect()
                } catch (e: Exception) { }
            }

            // Fallback to Invidious
            val invidiousInstances = listOf(
                "https://inv.tux.pizza",
                "https://invidious.nerdvpn.de",
                "https://invidious.jing.rocks",
                "https://invidious.protokolla.fi",
                "https://invidious.fdn.fr"
            )
            for (instance in invidiousInstances) {
                try {
                    val conn = URL("$instance/api/v1/videos/$videoId").openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                        val json = JSONObject(body)
                        val formatStreams = json.optJSONArray("formatStreams") ?: continue
                        var bestUrl = ""
                        var bestBitrate = 0
                        for (i in 0 until formatStreams.length()) {
                            val s = formatStreams.getJSONObject(i)
                            val format = s.optString("type", "")
                            if (format.startsWith("audio/")) {
                                val br = s.optInt("bitrate", 0)
                                val url = s.optString("url", "")
                                if (url.isNotEmpty() && br > bestBitrate) {
                                    bestUrl = url; bestBitrate = br
                                }
                            }
                        }
                        if (bestUrl.isNotEmpty()) return bestUrl
                    }
                    conn.disconnect()
                } catch (e: Exception) { }
            }
            return null
        }

        private fun enqueueDownload(audioUrl: String, title: String, artist: String) {
            // Sanitise filename rigorously
            val cleanTitle = title.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").replace(" ", "_").trim().take(50)

            val fileName = "$cleanTitle.m4a"

            val request = android.app.DownloadManager.Request(
                android.net.Uri.parse(audioUrl)
            ).apply {
                setTitle(title)
                setDescription(artist)
                setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_MUSIC,
                    "LumiOne/$fileName"
                )
                addRequestHeader("User-Agent", "Mozilla/5.0")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            dm.enqueue(request)
            Log.d("LumiOneNative", "Download enqueued: $fileName")
        }
    }

    // ── ExoPlayer Callbacks from Service ────────────────────────
    
    fun notifyExoStateChanged(isPlaying: Boolean) {
        runOnUiThread {
            webView.evaluateJavascript("window.Player && window.Player.onExoStateChanged($isPlaying);", null)
        }
    }

    fun notifyExoTrackEnded() {
        runOnUiThread {
            webView.evaluateJavascript("window.Player && window.Player.onExoTrackEnded();", null)
        }
    }

    fun updateExoProgress(positionMs: Long, durationMs: Long) {
        val posS = positionMs / 1000.0
        val durS = durationMs / 1000.0
        runOnUiThread {
            webView.evaluateJavascript("window.Player && window.Player.onExoProgress($posS, $durS);", null)
        }
    }

    // ── Notification Actions from Service ──────────────────
    fun handleMediaAction(action: String) {
        runOnUiThread {
            when (action) {
                MediaPlaybackService.ACTION_NEXT -> webView.evaluateJavascript("window.Player && window.Player.playNext()", null)
                MediaPlaybackService.ACTION_PREV -> webView.evaluateJavascript("window.Player && window.Player.playPrevious()", null)
            }
        }
    }

    fun handleSeek(positionMs: Long) {
        runOnUiThread {
            val seconds = positionMs / 1000.0
            webView.evaluateJavascript("window.Player && window.Player.seek($seconds)", null)
        }
    }

    private fun setupImmersiveMode() {
        window.statusBarColor = android.graphics.Color.parseColor("#0d0d18")
        window.navigationBarColor = android.graphics.Color.parseColor("#0d0d18")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
        // Note: FLAG_KEEP_SCREEN_ON removed — music should play with screen off.
        // The MediaPlaybackService WakeLock keeps the CPU awake instead.
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        webView.evaluateJavascript(
            "(function() { return window.handleBackPress && window.handleBackPress(); })()"
        ) { result ->
            if (result == "true") {
                // JS handled the back press (e.g. navigated from player to home)
            } else {
                // On home screen — move to background instead of closing
                // so music keeps playing via foreground service
                moveTaskToBack(true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        runOnUiThread { webView.evaluateJavascript("window.Player && window.Player.onAppBackgrounded && window.Player.onAppBackgrounded()", null) }
        // DO NOT pause webView because background media playback needs it awake!
    }

    override fun onResume() {
        super.onResume()
        runOnUiThread { webView.evaluateJavascript("window.Player && window.Player.onAppForegrounded && window.Player.onAppForegrounded()", null) }
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        // Do NOT stop the service — let music continue playing in background
        instance = null
        webView.destroy()
        super.onDestroy()
    }
}
