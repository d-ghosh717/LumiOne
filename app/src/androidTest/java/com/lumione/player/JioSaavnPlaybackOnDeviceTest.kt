package com.lumione.player

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumione.player.player.AudioStreamResult
import com.lumione.player.player.JioSaavnAudioProvider
import com.lumione.player.queue.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JioSaavnPlaybackOnDeviceTest {

    @Test
    fun testFadedPlaybackOnDevice() = runBlocking {
        testSongPlayback("Faded", "Alan Walker")
    }

    @Test
    fun testNazmNazmPlaybackOnDevice() = runBlocking {
        testSongPlayback("Nazm Nazm", "Bareilly Ki Barfi")
    }

    private suspend fun testSongPlayback(title: String, artist: String) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = JioSaavnAudioProvider()
        val track = Track(
            videoId = "jiosaavn_test",
            title = title,
            artist = artist
        )

        Log.i("TEST_SAAVN", "Resolving track '${track.title}' via JioSaavnAudioProvider...")
        val result = provider.resolveStreamUrl(track)
        Log.i("TEST_SAAVN", "Result type: ${result.javaClass.simpleName}")

        assertTrue("Expected Success, got $result", result is AudioStreamResult.Success)
        val success = result as AudioStreamResult.Success
        Log.i("TEST_SAAVN", "Resolved URL: ${success.streamUrl}")
        assertTrue(success.streamUrl.contains("saavncdn.com"))

        val readyDeferred = CompletableDeferred<Boolean>()
        var observedError: PlaybackException? = null
        var exoPlayer: ExoPlayer? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

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
                    Log.i("TEST_SAAVN", "ExoPlayer state: $stateName")
                    if (playbackState == Player.STATE_READY) {
                        Log.i("TEST_SAAVN", "ExoPlayer is READY! Duration: ${player.duration}ms")
                        readyDeferred.complete(true)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.i("TEST_SAAVN", "ExoPlayer isPlaying: $isPlaying (pos: ${player.currentPosition}ms)")
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e("TEST_SAAVN", "ExoPlayer Error: ${error.errorCodeName} (${error.errorCode}): ${error.message}")
                    observedError = error
                    readyDeferred.complete(false)
                }
            })

            val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(success.streamUrl))
            player.setMediaSource(mediaSource)
            player.prepare()
            player.play()
        }

        withTimeout(20000) {
            val ready = readyDeferred.await()
            if (!ready) {
                fail("ExoPlayer failed with: ${observedError?.errorCodeName} - ${observedError?.message}")
            }
        }

        delay(3000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val p = exoPlayer!!
            Log.i("TEST_SAAVN", "Final verified position: ${p.currentPosition}ms, isPlaying=${p.isPlaying}")
            assertTrue("Current position must advance beyond 0", p.currentPosition > 0)
            p.stop()
            p.release()
        }
    }
}
