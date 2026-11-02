# Officer K — Android APK project

This repository wraps the supplied `index.html` media player in a Capacitor Android app and builds a debug APK with GitHub Actions.

## Build the APK with GitHub

1. Create a new, empty repository on GitHub.
2. Extract this ZIP on your computer.
3. Upload **the contents** of the extracted `officer-kd6-android` folder to the repository (the `.github` folder must be included).
4. Open the repository's **Actions** tab.
5. Select **Build Officer K Android APK** and press **Run workflow** (or push/commit to `main` or `master`).
6. When the run finishes, open it and download the artifact named **Officer-K-debug-APK**.
7. Extract the downloaded artifact. Install `app-debug.apk` on your Android phone. If Android asks, allow installation from that source.

## Notes

- This is a debug APK for testing/sideloading, not a Play Store-signed release.
- The original HTML app is kept in `www/index.html`.
- The original app's built-in browser-based file picker and media features remain in place.
- The HTML references optional native plugins named `LocalMusic`, `MediaSession`, and `CapacitorShareTarget`. This ZIP does not implement those custom native plugins; native-only features depending on them may not work until those plugins are added. The APK build itself does not depend on them.
- Internet-based features and external CDN libraries still require an internet connection.

## Notification-panel media player

The build adds a native `MediaSession` plugin + foreground service (`assets/android-java/`). While music plays you get a player in the notification panel / lock screen with cover, seek bar, previous, play/pause, next and **Stop**. On Android 13+ allow the "Notifications" prompt the first time you open the app.

### If the panel doesn't appear
Temporary toasts and a test panel (3 s after launch) help diagnose it; set `DEBUG = false` in `MediaPlaybackService.java` to remove them.
1. "sending 'playing' to the player panel" → the app reached the native plugin.
2. "player panel service started" → the service is running.
3. A red-flag toast ("Notifications are OFF…") → enable notifications for Officer K in Android settings.

## Full internal-storage access (Android APK)

This version adds an Android-native **Browse all internal storage** screen under **Settings → Advanced**. Grant **All files access** when Android opens the system permission screen. The browser lists the files and folders already on the phone directly; it does not upload or copy them into the app. The browser can permanently delete an individual file after confirmation; folder deletion is intentionally disabled to protect folder contents.

For local audio tracks, **Delete from library** now deletes the actual phone file (after Android's All files access permission is granted). When you rescan, audio files deleted elsewhere are removed from the player library and newly added audio tracks are discovered. This uses Android shared-storage access and MediaStore, not cloud storage. Android may still restrict certain protected app folders, and this permission is not the same as access to private data inside other apps.

The change is in the source ZIP; it is not a prebuilt APK. Push the project to GitHub and run the existing **Build Officer K Android APK** workflow to build the updated APK.
