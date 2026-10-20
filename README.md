# LumiOne

> **Discover music. Build your library. Stay in the flow.**

LumiOne is a native Android music player focused on music discovery, personal playlists, an immersive listening experience, and uninterrupted background playback. Designed with a deep navy interface and electric cyan accents, it combines a distinctive visual identity with a dedicated audio playback engine.

---

## Overview

LumiOne brings music discovery and playback together in one native Android experience:

- **Music Discovery** — Search for tracks and artists, and explore curated, trending music charts.
- **Personal Library** — Save liked tracks, revisit listening history, and manage an active playback queue.
- **Personal Playlists** — Organize tracks into custom playlists with full creation and editing capabilities.
- **Background Playback** — Continue listening uninterrupted when the app is minimized or the screen is turned off.
- **System Media Controls** — Control playback through Android's media notification and supported lock-screen controls.
- **Immersive Player** — Vinyl-inspired rotating disc, animated playback visuals, and custom audio controls.
- **Personalized Profile** — Manage listener details and access library shortcuts.
- **Cloud-Backed Services** — Use an authenticated backend for supported discovery and metadata requests.

---

## Features

| Feature | Description |
| :--- | :--- |
| **Music Search** | Discover tracks and artists through configured search and discovery services. |
| **Trending Discovery** | Browse official charts, recommendations, and music-focused collections without non-music noise. |
| **Audio Playback** | Native audio playback engine powered by AndroidX Media3 and ExoPlayer. |
| **Background Listening** | Foreground media service for seamless playback outside the app. |
| **Playback Queue** | View, reorder, and manage the upcoming listening session. |
| **Favorites & History** | Quick access to liked tracks and recently played music. |
| **Personal Playlists** | Create, rename, delete, and organize personal music collections. |
| **Google Sign-In** | Firebase Authentication integration for secure user sessions. |
| **Custom Interface** | Dark navy surfaces, electric cyan accents, and vinyl-inspired visuals. |

> *Note: Available discovery sources and playback formats depend on provider availability and their respective terms.*

---

## Technology Stack

### Android Application
- **Language**: Kotlin 1.9.22 (JVM 17 Target)
- **UI Framework**: Native Android XML layouts & custom views (`VinylDiscView`, `CyanWaveformView`)
- **Audio Engine**: AndroidX Media3 / ExoPlayer 1.2.1 (`PlaybackService`)
- **Image Loading**: Bumptech Glide 4.16.0
- **Authentication**: Firebase Authentication & Google Play Services Auth
- **Build System**: Gradle 8.2 with Android Gradle Plugin 8.2.2

### Cloud Backend
- **Runtime**: Node.js & Express
- **Auth Verification**: Firebase Admin SDK
- **Hosting**: Render Web Service
- **Live Endpoint**: `https://lumione.onrender.com`

> *The backend supports discovery and metadata operations. Audio playback is handled directly by the Android client and its configured playback providers.*

---

## Architecture

```text
┌──────────────────────────────────────────────┐
│                LumiOne Android               │
│                                              │
│   Home · Search · Library · Profile · Player │
└──────────────────────┬───────────────────────┘
                       │ Firebase ID Token
                       ▼
┌──────────────────────────────────────────────┐
│             Express Cloud Backend            │
│          (https://lumione.onrender.com)      │
│                                              │
│   Auth Verification · Search & Chart Metadata│
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│           Configured Data Sources            │
│                                              │
│   Official Music Charts · Track Metadata     │
└──────────────────────────────────────────────┘

               Android Audio Playback
                       │
                       ▼
┌──────────────────────────────────────────────┐
│          Audio Provider Resolution           │
│                      ↓                       │
│              Media3 / ExoPlayer              │
│                      ↓                       │
│               PlaybackService                │
│                      ↓                       │
│         Background & Media Controls          │
└──────────────────────────────────────────────┘
```

---

## Getting Started

### Prerequisites
- **Android Studio**: Iguana (2023.2.1) or newer
- **JDK**: Java Development Kit 17
- **Android SDK**: API level 34 (Compile & Target), API level 26 (Minimum)
- **Git**
- **Node.js & npm** (optional, only if running the backend locally)
- A Firebase project configured for Android and Web authentication

### 1. Clone the Repository
```bash
git clone https://github.com/d-ghosh717/LumiOne.git
cd LumiOne
```

### 2. Configure Android Project
1. Open the project directory in Android Studio.
2. Place your `google-services.json` in the `app/` directory.
3. Keep all private credentials and local configuration out of version control.

### 3. Build the Application

**Run unit tests:**
```bash
./gradlew test
```

**Build Debug APK:**
```bash
./gradlew assembleDebug
```
*Generated output: `app/build/outputs/apk/debug/app-debug.apk`*

**Build Release APK:**
```bash
./gradlew assembleRelease
```
*Generated output: `app/build/outputs/apk/release/app-release.apk`*

### 4. Run the Backend Locally (Optional)
```bash
cd backend
npm install
npm start
```
Configure your required environment variables (such as `PORT`, `FIREBASE_SERVICE_ACCOUNT`) before launching the backend service.

---

## Configuration and Security

- **Secrets Management**: Never commit API secrets, private service-account keys, signing credentials, or session cookies.
- **Environment Isolation**: Keep machine-specific `local.properties` and Gradle build artifacts excluded via `.gitignore`.
- **Encrypted Traffic**: Always use HTTPS for client-backend communications.
- **Provider Terms**: Respect the terms, access restrictions, and licensing requirements of every music metadata provider.

---

## Project Structure

```text
LumiOne/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/lumione/player/
│   │   │   │   ├── auth/          # Firebase authentication & Google Sign-In
│   │   │   │   ├── data/          # Favorites, History, and Playlist managers
│   │   │   │   ├── player/        # Media3 audio providers & stream resolvers
│   │   │   │   ├── queue/         # Queue management & track models
│   │   │   │   ├── search/        # Search engine & music discovery
│   │   │   │   ├── service/       # Media3 PlaybackService & notification
│   │   │   │   └── ui/            # MainActivity, adapters & custom views
│   │   │   └── res/               # Layouts, drawables, styles & navigation
│   │   ├── test/                  # JVM unit tests
│   │   └── androidTest/           # Android instrumentation tests
│   ├── build.gradle               # App-level build configuration
│   └── proguard-rules.pro         # ProGuard rules for release builds
├── backend/                       # Node.js / Express metadata backend
│   ├── server.js                  # API routes, auth check & discovery logic
│   └── package.json               # Backend dependencies
├── release/                       # Distribution release artifacts
├── build.gradle                   # Root project build configuration
├── settings.gradle                # Gradle settings
└── README.md                      # Project documentation
```

---

## Roadmap

- [x] Music discovery restricted to genuine music tracks and official chart playlists.
- [x] Seamless background audio playback with lock screen and notification controls.
- [x] Personal playlists management with local persistence.
- [ ] Offline caching and downloaded track playback.
- [ ] Synchronized lyrics presentation.
- [ ] Advanced audio equalizer and audio spatializer options.

---

## Contributing

Contributions, bug reports, and suggestions are welcome:

1. Fork the repository.
2. Create your feature branch (`git checkout -b feature/my-feature`).
3. Commit your changes (`git commit -m 'feat: add my feature'`).
4. Run the test suite (`./gradlew test`).
5. Push to the branch (`git push origin feature/my-feature`).
6. Open a Pull Request with a clear description of the modifications.

---

## License

All rights reserved.

Unless otherwise stated, the source code and project assets are not offered under an open-source license. Contact the repository owner for permission to reuse or redistribute them.

<p align="center">
  <strong>LumiOne</strong><br/>
  Built for a better listening experience.
</p>
