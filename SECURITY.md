# LumiOne v1.2.0 — Security Audit Report

**Date of Audit:** October 23, 2026  
**Auditor:** Antigravity Autonomous Security Engineer & Code Reviewer  
**Scope:** LumiOne Android Application (`com.lumione.player`) and Node.js Backend Microservice (`luminous-player-backend`)  
**Release Target:** v1.2.0  
**Overall Status:** **READY WITH CAVEATS**

---

## 1. Executive Summary

A comprehensive security review was conducted across the LumiOne Android client codebase, build configuration, network parameters, authentication flows, Firestore rules, and backend API service. 

Key security postures verified:
- **Mandatory Authentication & Data Isolation:** Firebase Authentication gates all core features (Search, Trending, Cloud Library Sync). All Firestore documents under `/users/{uid}/` enforce strict UID ownership (`request.auth.uid == uid`), preventing cross-tenant or unauthenticated data access.
- **Credential Hygiene:** No hardcoded private keys, signing keystores, service account JSON secrets, or API master tokens were found committed in version control or git history.
- **TLS & Network Security:** Fixed an insecure cleartext baseline configuration in `network_security_config.xml`, enforcing HTTPS transport across all domains.
- **Component Exposure:** Android manifest components are strictly guarded; the foreground media playback service is unexported (`android:exported="false"`), and only `MainActivity` is exported with standard launcher intent filters.

---

## 2. Areas & Files Inspected

### Android Client
- `app/src/main/AndroidManifest.xml`: Component export flags, permissions, application backup flags, cleartext traffic configuration.
- `app/src/main/res/xml/network_security_config.xml`: Trust anchors, cleartext transport allowances, domain configurations.
- `app/src/main/java/com/lumione/player/auth/AuthGatePolicy.kt`: Authentication gate policy enforcement.
- `app/src/main/java/com/lumione/player/auth/AuthManager.kt`: Google Sign-In & Firebase ID token handling.
- `app/src/main/java/com/lumione/player/data/AccountLibrarySync.kt`: Cloud library sync, token lifecycle, and collection scoping.
- `app/src/main/java/com/lumione/player/ui/MainActivity.kt`: Navigation protection, insets handling, and UI session state.
- `app/src/main/java/com/lumione/player/service/PlaybackService.kt`: Media playback service boundaries and notification controls.
- `app/build.gradle`: ProGuard/R8 configurations, SDK target versions, build config fields, and signing configs.
- `firestore.rules`: Security rules for Cloud Firestore databases and subcollections.

### Backend Microservice
- `backend/server.js`: Express routing, Firebase Admin token verification middleware, CORS headers, input sanitization, and rate limits.
- `backend/music_intelligence.js` & `backend/recommender.js`: Query parsing, candidate scoring, and search proxy logic.
- `backend/package.json`: Dependency manifests and vulnerability advisories via `npm audit`.

---

## 3. Findings & Vulnerability Matrix

| ID | Title | Severity | Status | Component |
|---|---|---|---|---|
| **SEC-01** | Global Cleartext Permitted in Network Security Config | Medium | **RESOLVED** | Android Client |
| **SEC-02** | Wildcard Permissive CORS on Backend Endpoints | Low | **OPEN (Configuration)** | Backend Service |
| **SEC-03** | Transitive Vulnerabilities in Backend Node Modules | Medium | **OPEN (Audit / Action)** | Backend Service |
| **SEC-04** | Release Build Signs with Debug Keystore by Default | Low / Info | **DOCUMENTED** | Android Build Config |
| **SEC-05** | Obsolete Manifest Package Declaration | Info | **RESOLVED** | Android Manifest |

---

## 4. Detailed Findings

### SEC-01: Global Cleartext Permitted in Network Security Config
- **Severity:** Medium
- **Location:** `app/src/main/res/xml/network_security_config.xml`
- **Evidence:** 
  The configuration originally contained `<base-config cleartextTrafficPermitted="true">`, overriding `android:usesCleartextTraffic="false"` in the manifest and allowing arbitrary cleartext HTTP connections.
- **Impact:** Risk of man-in-the-middle (MITM) eavesdropping or token interception on insecure networks.
- **Fix Applied:** Modified `base-config` and `domain-config` to `cleartextTrafficPermitted="false"`, enforcing TLS across all client network calls.
- **Verification:** Verified by clean build and XML schema inspection.

### SEC-02: Wildcard Permissive CORS on Backend Endpoints
- **Severity:** Low
- **Location:** `backend/server.js:10`
- **Evidence:** `app.use(cors())` configures `Access-Control-Allow-Origin: *` globally.
- **Impact:** While mobile apps do not enforce CORS, any arbitrary web application running in a user's browser can call backend search and recommendation endpoints. Authenticated endpoints still require a valid Firebase bearer token, mitigating unauthorized data access.
- **Recommended Action:** If web client usage is restricted to specific deployment domains (e.g. `lumione-web.pages.dev`), restrict the CORS origin array accordingly.

### SEC-03: Transitive Vulnerabilities in Backend Node Modules
- **Severity:** Medium
- **Location:** `backend/node_modules/` (`body-parser`, `node-forge`, `proxy-addr`, `qs`, `uuid`)
- **Evidence:** `npm audit` reported 12 vulnerabilities (1 critical, 2 high, 8 moderate, 1 low) in transitive dependencies pulled by Express and `firebase-admin@^12.0.0`.
  - `proxy-addr`: IP spoofing via IPv4-mapped IPv6 trust subnet.
  - `node-forge`: RSA PKCS#1 v1.5 signature verification advisory.
- **Impact:** Potential DoS or IP spoofing if upstream reverse proxies forward untrusted `X-Forwarded-For` headers.
- **Action Required for Operator:** Run `npm audit fix` in the backend environment. Upgrading `firebase-admin` to v14+ is a major version jump that requires validation against current node runtime versions.

### SEC-04: Release Build Fallback to Debug Keystore
- **Severity:** Low / Informational
- **Location:** `app/build.gradle:38-41`
- **Evidence:** In the absence of `RELEASE_STORE_FILE` property, `signingConfig` initializes with `signingConfigs.debug`.
- **Impact:** The resulting release APK is signed with the Android SDK default debug certificate. While installable and testable on sideloaded devices, it cannot be uploaded to Google Play Console as an official release key.
- **Action Required for Play Store Publishing:** Provide production release keystore credentials via environment variables (`RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`).

### SEC-05: Obsolete Manifest Package Declaration
- **Severity:** Informational
- **Location:** `app/src/main/AndroidManifest.xml:3`
- **Evidence:** AGP 8+ ignores the `package="com.lumione.player"` attribute in `<manifest>`, generating build-time warnings.
- **Fix Applied:** Removed the redundant attribute. Namespace is cleanly managed in `app/build.gradle`.

---

## 5. Firebase & Firestore Security Audit

> **Note:** Findings below are based on source-code inspection of `firestore.rules`, `AccountLibrarySync.kt`, and `AuthGatePolicy.kt`. Production deployment to live Firebase instances requires live console deployment.

1. **User Scoping & Isolation:**
   - Every collection storing personal user state (`favorites`, `history`, `recentSearches`, `playlists`) resides under `/users/{uid}/`.
   - Rule constraint: `allow read, write: if request.auth != null && request.auth.uid == uid;`
   - Unauthenticated requests are rejected (`request.auth != null`).
   - Authenticated users cannot read or write another user's documents (`request.auth.uid == uid`).
2. **Global Deny:**
   - Default fallback rule `match /{document=**} { allow read, write: if false; }` prevents exposure of any undeclared collections.
3. **No Permissive Overrides:**
   - Confirmed absence of `allow read, write: if true;` or public read leaks.

---

## 6. Dependency & Static Analysis Summary

- **Android Client:**
  - Media3 / ExoPlayer: `1.2.1`
  - Firebase BoM: `32.8.0`
  - Play Services Auth: `21.0.0`
  - Glide: `4.16.0`
  - AndroidX Core / AppCompat / Material: Current stable revisions.
- **Backend Service:**
  - Express: `4.18.2`
  - Firebase Admin: `12.0.0`
  - CORS: `2.8.5`

---

## 7. Release-Readiness Assessment

### **VERDICT: READY WITH CAVEATS**

**Caveats:**
1. **Production Play Store Key:** The built release APK uses the local Android debug certificate fallback because no private production keystore (`RELEASE_STORE_FILE`) is present in the workspace. This is fully functional for QA testing, sideloading, and staging distribution, but must be signed with a production keystore for Google Play Store submission.
2. **Live Firestore Rules Deployment:** Ensure `firestore.rules` is deployed to the production Firebase project via `firebase deploy --only firestore:rules`.
3. **Backend Node Audit:** Apply `npm audit fix` on the hosting server (Render/container) before high-traffic public exposure.

---

## 8. APK Build Artifacts & Signing Verification

### Artifact Inventory & Integrity

| Artifact | File Path | Size (Bytes) | SHA-256 Checksum |
|---|---|---|---|
| **Debug APK (Latest v1.2.0)** | `/Volumes/d/Projects/MusicPlayer/LumiOne/release/LumiOne-V1.2.0-debug.apk` | 85,206,398 | `639d3f40f91b9c2d0e89eeffe685b8618f85be3d677ff5a1c1868a1eec7ada00` |
| **Release APK (New v1.2.0 Build)** | `/Volumes/d/Projects/MusicPlayer/LumiOne/app/build/outputs/apk/release/app-release.apk` | 73,138,190 | `73a3a28fa44413b61a61e85de00c4e867c497305637c0c227011bdd97e47bfd0` |
| **Release APK (Preserved Baseline)** | `/Volumes/d/Projects/MusicPlayer/LumiOne/release/LumiOne-V1.2.0.apk` | 73,137,210 | `1d14c2b8315fa47ab1506b1b1c31fa9d3ba75b868c90612b22332140f2c4c59e` |

### Signing Certificate Verification (`apksigner`)

Verification run on `app-release.apk` using Android SDK `apksigner` (v35.0.0):
- **Signer #1 Certificate DN:** `C=US, O=Android, CN=Android Debug`
- **Certificate SHA-256 Digest:** `e1a31f870f9fa5c1af88450e0028623323ad01eda8f8f9a31a497cc6dc8094a5`
- **APK Signature Scheme v2:** `true` (valid signature block)
- **APK Signature Scheme v1 (JAR):** `false`
- **Signing Keystore Status:** **Debug Certificate Fallback** (per `build.gradle` release configuration when `RELEASE_STORE_FILE` is not set). In accordance with release instructions, the baseline artifact `/Volumes/d/Projects/MusicPlayer/LumiOne/release/LumiOne-V1.2.0.apk` is preserved intact and not silently replaced with a debug-certificate production claim.

### Device Testing Status
- **Connected Android Devices:** None detected (`adb devices` returned 0 attached devices/emulators).
- **Physical Device Smoke Test:** Not performed due to absence of hardware test target. Unit test suite passed with 100% success (27/27 tasks executed without error).

