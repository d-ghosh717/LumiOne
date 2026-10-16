package com.lumione.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumione.player.queue.Track
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

import android.util.Log
import com.lumione.player.player.AudioStreamResult
import com.lumione.player.player.YouTubeAudioProvider

@RunWith(AndroidJUnit4::class)
class YouTubePlaybackOnDeviceTest {

    @Before
    fun setup() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            YoutubeDL.getInstance().init(appContext)
        } catch (e: Exception) {
            // Already initialized
        }
    }

    @Test
    fun testRealPlaybackOnDevice() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = YouTubeAudioProvider()
        val track = Track(
            videoId = "60ItHLz5WEA",
            title = "Faded",
            artist = "Alan Walker",
            durationMs = 212000L,
            youtubeVideoId = "60ItHLz5WEA"
        )

        Log.i("TEST_PLAY", "Resolving track '${track.title}' via YouTubeAudioProvider...")
        val result = provider.resolveStreamUrl(track)
        Log.i("TEST_PLAY", "Result type: ${result.javaClass.simpleName}")

        if (result !is AudioStreamResult.Success) {
            val reason = (result as? AudioStreamResult.Unavailable)?.reason ?: "Unknown"
            Log.e("TEST_PLAY", "Resolution failed: $reason")
            fail("Provider resolution failed: $reason")
            return@runBlocking
        }

        Log.i("TEST_PLAY", "Stream URL resolved: ${result.streamUrl.take(80)}...")
        Log.i("TEST_PLAY", "Stream Headers: ${result.headers}")

        val readyDeferred = CompletableDeferred<Boolean>()
        var observedError: PlaybackException? = null
        var exoPlayer: ExoPlayer? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)

            val customUserAgent = result.headers.entries.firstOrNull { entry -> entry.key.equals("User-Agent", ignoreCase = true) }?.value
            if (!customUserAgent.isNullOrBlank()) {
                httpDataSourceFactory.setUserAgent(customUserAgent)
            } else {
                httpDataSourceFactory.setUserAgent("Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
            }

            // Clean headers: do not pass navigation or browser-specific fetch headers
            val cleanHeaders = result.headers.filterNot { entry ->
                entry.key.equals("User-Agent", ignoreCase = true) ||
                entry.key.startsWith("Sec-", ignoreCase = true) ||
                entry.key.equals("Accept", ignoreCase = true)
            }
            if (cleanHeaders.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(cleanHeaders)
            }

            val dataSourceFactory = DefaultDataSource.Factory(appContext, httpDataSourceFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val player = ExoPlayer.Builder(appContext)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            exoPlayer = player

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val stateName = when (playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "$playbackState"
                    }
                    Log.i("TEST_PLAY", "ExoPlayer state: $stateName")
                    if (playbackState == Player.STATE_READY) {
                        Log.i("TEST_PLAY", "ExoPlayer is READY! Duration: ${player.duration}ms")
                        readyDeferred.complete(true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i("TEST_PLAY", "ExoPlayer isPlaying: $isPlaying (pos: ${player.currentPosition}ms)")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("TEST_PLAY", "ExoPlayer Error: ${error.errorCodeName} (${error.errorCode}): ${error.message}")
                    var c: Throwable? = error.cause
                    while (c != null) {
                        Log.e("TEST_PLAY", "  Cause: ${c.javaClass.name}: ${c.message}")
                        c = c.cause
                    }
                    observedError = error
                    readyDeferred.complete(false)
                }
            })

            val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(result.streamUrl))
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        }

        withTimeout(25000) {
            val ready = readyDeferred.await()
            if (!ready) {
                fail("ExoPlayer failed with: ${observedError?.errorCodeName} - ${observedError?.message}")
            }
        }

        delay(3000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val p = exoPlayer!!
            Log.i("TEST_PLAY", "Final verified position: ${p.currentPosition}ms, isPlaying=${p.isPlaying}")
            assertTrue("Current position must advance beyond 0", p.currentPosition > 0)
            p.stop()
            p.release()
        }
    }
}
