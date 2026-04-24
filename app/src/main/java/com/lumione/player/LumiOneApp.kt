package com.lumione.player

import android.app.Application
import android.webkit.WebView

class LumiOneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Enable WebView debugging in debug builds
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
