package com.lumione.player

import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

/**
 * Glide module — configures thumbnail caching for track art.
 * Caches YouTube thumbnails to disk so they survive across sessions.
 */
@GlideModule
class LumiGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDefaultRequestOptions(
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
        )
    }

    // Disable manifest parsing for performance
    override fun isManifestParsingEnabled(): Boolean = false
}
