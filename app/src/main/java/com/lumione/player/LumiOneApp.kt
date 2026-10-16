package com.lumione.player

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LumiOneApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initYoutubeDL()
    }

    private fun initYoutubeDL() {
        try {
            YoutubeDL.getInstance().init(this)
            Log.i(TAG, "Successfully initialized native yt-dlp runtime.")

            // Update yt-dlp binary in background to stay current with YouTube API changes.
            // This is critical — the bundled binary becomes stale as YouTube updates its API.
            appScope.launch {
                try {
                    val status = YoutubeDL.getInstance().updateYoutubeDL(this@LumiOneApp)
                    Log.i(TAG, "yt-dlp update check result: $status")
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp update check failed (non-fatal, using bundled version): ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize native yt-dlp runtime: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "LumiOneApp"
    }
}
