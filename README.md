# IING STD KARAOKE 🎤

Android karaoke player with offline audio and direct HTTPS streaming.

## Implemented in source
- Pick local audio with Android Storage Access Framework or play a direct HTTPS audio URL.
- Play, pause, seek, repeat, volume, speed and pitch adjustment with Media3 ExoPlayer.
- Open timestamped LRC lyrics and adjust lyric timing.
- Local song favorites and listening history.
- Record microphone audio to AAC/M4A; pause and resume recording.
- Play and share the latest voice recording.
- Save the backing-track URI and playback position at recording start as metadata for future synchronization.
- Audio-focus and headphone-disconnection handling.
- Scrollable Jetpack Compose interface.

## Recording behavior and limitations
**Recording currently captures microphone audio only.** It does **not** digitally combine the backing track and microphone into one mixed file. Backing-track metadata is not an audio mix and does not guarantee sample-accurate alignment.

For cleaner vocals, use wired headphones while recording to avoid speaker bleed. On Android 10+ recordings are saved to Music/IING STD Karaoke via MediaStore. On Android 8–9 they are saved to the app-specific external Music folder and may be deleted if the app is uninstalled.

Direct streaming requires a playable HTTPS audio URL; YouTube and Spotify catalog search are not supported. AI vocal removal, microphone effects and duet video are not implemented.

## Build and verification
GitHub Actions > Build Android APK; download the debug APK artifact from a successful run. The workflow also checks the APK archive and generates a SHA-256 checksum. A successful build does not replace installation and functional testing on a device.

Local build: `gradle :app:assembleDebug` with JDK 17 and Android SDK 35.

Only use audio and lyrics you have rights to use.
