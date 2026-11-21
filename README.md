# IING STD KARAOKE 🎤

Android karaoke player with offline audio and direct HTTPS streaming.

## Implemented in source
- Pick local audio with Android Storage Access Framework or play a direct HTTPS audio URL.
- Play, pause, seek, repeat, volume, speed and pitch adjustment with Media3 ExoPlayer.
- Open timestamped LRC lyrics and adjust lyric timing.
- Local song favorites and listening history.
- Record microphone audio to AAC/M4A; pause and resume recording.
- Play and share the latest voice recording.
- Save the backing-track URI and playback position at recording start.
- Offline WAV mixing button for accessible local backing tracks and microphone recordings, with independent music/voice gain, playback, and sharing.
- Audio-focus and headphone-disconnection handling.
- Scrollable Jetpack Compose interface.

## Recording behavior and limitations
**Microphone recording itself captures vocals only.** A separate offline mixing action now decodes the recorded voice and accessible local backing track, combines them, and exports a stereo WAV. It requires matching sample rates, supports mono/stereo 16-bit PCM decoding, and does not support streaming backing tracks. The mixer uses the saved playback position, but cannot compensate for device recording latency, changes to playback speed/pitch, or pauses during recording. It has not been device-tested. The mixer caps decoded PCM at 32 MiB per track to reduce memory exhaustion; longer tracks are rejected with an error. The backing track must extend through the full vocal recording, otherwise mixing is rejected rather than silently truncated.

For cleaner vocals, use wired headphones while recording to avoid speaker bleed. On Android 10+ recordings are saved to Music/IING STD Karaoke via MediaStore. On Android 8–9 they are saved to the app-specific external Music folder and may be deleted if the app is uninstalled.

Direct streaming requires a playable HTTPS audio URL; YouTube and Spotify catalog search are not supported. AI vocal removal, microphone effects and duet video are not implemented.

## Build and verification
GitHub Actions > Build Android APK; download the debug APK artifact from a successful run. The workflow also checks the APK archive and generates a SHA-256 checksum. A successful build does not replace installation and functional testing on a device.

Local build: `gradle :app:assembleDebug` with JDK 17 and Android SDK 35.

Only use audio and lyrics you have rights to use.
