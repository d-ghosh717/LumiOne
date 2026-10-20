# 🎧 LumiOne — Smart Music Streaming App

LumiOne is a modern, high-fidelity Android music streaming app designed with a dark liquid-glass aesthetic, personal playlist management, multi-provider stream resolution, and seamless background playback.

---

## ✨ Features

- **Personal Playlists**: Create, rename, delete personal playlists, and manage song memberships with instant local persistence.
- **Personalized Profile**: Customizable listener display name, personal library shortcuts, and listening statistics.
- **Smart Search & Trending**: Real-time song and artist discovery powered by an authenticated cloud backend and embedded metadata engine.
- **Hybrid Playback Engine**: Seamless multi-provider audio streaming powered by Media3 / ExoPlayer with YouTube (yt-dlp) and JioSaavn high-fidelity fallback.
- **Background & Off-Screen Playback**: Uninterrupted listening with foreground media service, lock-screen controls, and system notification integration.
- **Rich Liquid-Glass UI**: Modern dark navy theme with electric cyan accents, interactive vinyl rotation, queue sheet, and tactile playback controls.

---

## 🛠️ Architecture & Tech Stack

### Android Client (`/app`)
- **Language**: Kotlin 1.9.22 (JVM 17 Target)
- **UI**: Android Native XML with Custom Views (`VinylDiscView`, `CyanWaveformView`)
- **Audio Engine**: AndroidX Media3 / ExoPlayer 1.2.1 (`PlaybackService`)
- **Image Loading**: Bumptech Glide 4.16.0
- **Authentication**: Firebase Authentication & Google Sign-In

### Cloud Backend (`/backend`)
- **Runtime**: Node.js & Express
- **Deployment**: Render Web Service (`https://lumione.onrender.com`)
- **Auth**: Firebase Admin SDK for authenticated request verification

---

## 🚀 Building & Running

### Prerequisites
- Android Studio Iguana | 2023.2.1 or newer
- JDK 17
- Android SDK 34 (Minimum Android 8.0 / API 26)

### Run Unit Tests
```bash
./gradlew test
```

### Build Debug APK
```bash
./gradlew assembleDebug
```
The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
All rights reserved.
