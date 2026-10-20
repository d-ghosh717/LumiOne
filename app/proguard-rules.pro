# ProGuard rules for LumiOne

# Keep MediaSession / Media support
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

# Keep our service intact
-keep class com.lumione.player.service.PlaybackService { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {<init>(...);}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {**[] $VALUES; public *;}

# General Android
-dontwarn android.webkit.**
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# yt-dlp native Android runtime
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.youtubedl_android.**

# LumiOne domain and data models
-keep class com.lumione.player.search.** { *; }
-keep class com.lumione.player.queue.** { *; }
-keep class com.lumione.player.player.** { *; }
-keep class com.lumione.player.data.** { *; }
-keep class com.lumione.player.auth.** { *; }

