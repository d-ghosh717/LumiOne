# ProGuard rules for LumiOne

# Keep WebView JS bridge classes
-keepclassmembers class com.lumione.player.bridge.AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

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
