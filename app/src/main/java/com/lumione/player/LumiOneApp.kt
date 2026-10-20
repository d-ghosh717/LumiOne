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
        appScope.launch {
            try {
                YoutubeDL.getInstance().init(this@LumiOneApp)
                Log.i(TAG, "Successfully initialized native yt-dlp runtime.")

                try {
                    val status = YoutubeDL.getInstance().updateYoutubeDL(this@LumiOneApp)
                    Log.i(TAG, "yt-dlp update check result: $status")
                } catch (t: Throwable) {
                    Log.w(TAG, "yt-dlp update check failed (non-fatal, using bundled version): ${t.message}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize native yt-dlp runtime: ${t.message}", t)
            }
        }
    }

    companion object {
        private const val TAG = "LumiOneApp"
    }
}
