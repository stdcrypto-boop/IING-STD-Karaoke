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
- Offline WAV mixing button for accessible local backing tracks and microphone recordings, with independent music/voice gain, manual backing synchronization adjustment (±500 ms), playback, and sharing. The latest successful WAV export is remembered across app restarts while the file exists. Starting a new vocal recording clears the previous mix from the app's current-result controls, but does not delete the older WAV file. The mixer scales combined gains to reduce clipping and applies a short 10 ms fade at the start and end of the export.
- Audio-focus and headphone-disconnection handling.
- Scrollable Jetpack Compose interface.

## Recording behavior and limitations
**Microphone recording itself captures vocals only.** A separate offline mixing action now decodes the recorded voice and accessible local backing track, combines them, and exports a stereo WAV. It supports mono/stereo 16-bit PCM decoding and resamples the backing track with linear interpolation when sample rates differ; streaming backing tracks remain unsupported. The mixer uses the saved playback position, but cannot compensate for device recording latency, changes to playback speed/pitch, or pauses during recording. It has not been device-tested. Mix parameters are snapshotted when export begins so slider changes cannot alter an in-progress mix. The mixer checks estimated decoded size before decoding and caps PCM at 512 MiB per track to reduce memory exhaustion; longer tracks are rejected with an error. The backing track must extend through the full vocal recording, otherwise mixing is rejected rather than silently truncated.

For cleaner vocals, use wired headphones while recording to avoid speaker bleed. On Android 10+ recordings are saved to Music/IING STD Karaoke via MediaStore. On Android 8–9 they are saved to the app-specific external Music folder and may be deleted if the app is uninstalled.

Direct streaming requires a playable HTTPS audio URL; YouTube and Spotify catalog search are not supported. AI vocal removal, microphone effects and duet video are not implemented.

## Build and verification
GitHub Actions > Build Android APK; download the debug APK artifact from a successful run. The workflow also checks the APK archive and generates a SHA-256 checksum. A successful build does not replace installation and functional testing on a device.

Local build: `gradle :app:assembleDebug` with JDK 17 and Android SDK 35.

Only use audio and lyrics you have rights to use.


### Mixing audio panjang (eksperimental)
Decoder kini menyimpan PCM sementara ke cache aplikasi, bukan menahan seluruh hasil decode di RAM. Batas pengaman sementara 512 MiB per track. Proses memerlukan ruang penyimpanan tambahan untuk dua file PCM dan WAV akhir; file PCM sementara dihapus setelah proses berhasil atau gagal. Mixing masih melakukan decoding penuh sebelum penggabungan, bukan streaming real-time. Kinerja, format decoder, dan ketepatan sinkronisasi harus diuji di perangkat Android sebelum rilis.

PCM disk-backed mixer now caches decoded stereo frames during sample reads to reduce redundant file seeks. Actual performance gains remain device-dependent and unbenchmarked.

Mixer validates WAV output size, backing offsets and duration arithmetic before export to avoid overflow on long or malformed audio files. These guards do not replace device testing.

The disk-backed PCM mixer now uses 64 KiB buffered reads for both audio tracks to avoid repeated per-sample disk seeks. The buffer is bounded; benchmark and Android-device playback validation are still pending.
