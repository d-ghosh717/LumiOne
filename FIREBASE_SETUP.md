# Firebase setup for account library sync

The Android client uses Firebase Authentication (Google provider) and Cloud Firestore in project `lumione-1278a`.

1. In Firebase Console, enable **Cloud Firestore** for the project.
2. Publish the rules in [`firestore.rules`](firestore.rules) without broadening the UID ownership check.
3. In Firebase Authentication, keep the Google provider enabled and retain the existing Android package/OAuth client configuration.

The app stores data below `users/{firebaseUid}/...`; the Gmail address is never used as an account key. SharedPreferences remain the immediate UID-scoped cache. Firestore must be enabled and the rules published before account sync can be verified against the project. No service-account credential belongs in the Android app.
