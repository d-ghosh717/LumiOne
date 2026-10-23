# LUMIONE Web Player

This static HTML/CSS/JavaScript client is served by the existing Express backend. Its hash routes split Home, Search, Queue, Library, and Settings. Search and trending use Firebase-authenticated `/api/search` and `/api/trending`; radio recommendations use `/api/recommendations/related`, backed by the same hybrid recommender as the Android app. The web client passes the same Firebase ID token as `Authorization: Bearer <token>`.

## Run

From `/Volumes/d/Projects/MusicPlayer/web`:

```sh
cd backend && npm start
```

Open `http://localhost:3000`.

## Firebase Web sign-in setup

The web client uses the existing Firebase project and Google Auth provider. Ensure Google sign-in is enabled in Firebase Authentication and the deployment host is listed in Authentication's authorized domains. Library data reads and writes use Firebase Web Firestore against the Android app's existing `users/{uid}/favorites`, `history`, and `playlists` collections. Deploy the existing `firestore.rules` from the Android project so reads and writes remain restricted to the authenticated UID. The Web app settings live in `firebase-config.js`; replace them only when changing Firebase projects.

The values above are Firebase browser client configuration, not service-account credentials. Never place a service-account JSON/private key or an ID token in web files. If configuration is missing, the UI keeps the sign-in actions visible and explains the setup requirement; authenticated API calls are not attempted.

## Settings and API limits

The mobile Radio queue obtains seed-related candidates from the backend, ranks them with its hybrid recommender using history, likes, skipped artists, and recent tracks, preserves manual upcoming entries, and suppresses duplicates. The web uses the same backend engine and Firestore-backed account signals, preserves manually added tracks ahead of generated recommendations, and excludes recent/current queue tracks where the existing engine supports it. The queue and current playback position remain local to each device; the Android app does not persist a shared queue or playback position. Shuffle/repeat defaults remain browser-local because the mobile app has no shared preference storage for them.

History, liked songs, and playlists use the mobile app's Firestore schema and same Firebase UID. Changes are written directly to Firestore and visible on the other platform after its next account-library sync. Recent searches and playback queue state are not shared by the web client.

Playback controls remain disabled until a playable YouTube ID is loaded; metadata-only results without one cannot be played.

There is no separate web build, lint, or type-check command. Run `node --check script.js` and `cd backend && npm test` from this directory for available checks. The mobile repository's backend test suite is under `/Volumes/d/Projects/MusicPlayer/LumiOne/backend/test`.
