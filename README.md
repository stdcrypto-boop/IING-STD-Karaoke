# IING STD KARAOKE 🎤

Android karaoke player, offline local audio and online direct HTTPS audio streams.

## Current implementation
- Local audio picker (Android Storage Access Framework)
- HTTPS direct audio URL playback
- Play/pause, seek, playback speed
- LRC lyrics picker and time-synchronized highlighting
- Jetpack Compose UI / Media3 ExoPlayer

## Build
GitHub Actions > Build Android APK > Run workflow, then download the artifact.
Local: `gradle :app:assembleDebug` with JDK 17 and Android SDK 35.

## Limitations
Online mode supports direct audio streams, **not** YouTube/Spotify catalog search.
AI vocal removal, pitch shift, microphone effects, recording, and duet video are **not implemented yet**.
Only use audio and lyrics you have rights to use.
