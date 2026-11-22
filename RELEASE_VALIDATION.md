# IING STD Karaoke v1.0 — release validation gate

This is a **verification checklist**, not a claim that device tests have passed. Do not tag or publish a stable v1.0 solely because the debug APK compiles.

## CI baseline (2026-11-23 WIB)
- Commit: `ec108459404656be0ff95b45fbe3cf6055626a3b`
- GitHub Actions run: https://github.com/stdcrypto-boop/IING-STD-Karaoke/actions/runs/45077920499
- Observed: JVM unit tests, debug APK build, package/checksum verification and artifact upload succeeded.
- Debug artifact: `IING-STD-Karaoke-debug` (GitHub artifact ID `14775697328`).
- This does **not** verify real microphone, USB audio, latency, YouTube embed availability, or release signing.

## Release-blocking manual acceptance tests (not yet verified)
Use an Android phone with built-in microphone, wired headphones, and—where available—a LIVE OTG USB soundcard. Capture model/Android version, steps, audio sample, expected/actual outcome and logs for every failure.

| Area | Acceptance criterion | Status |
| --- | --- | --- |
| Portrait | Video/LRC stays visible at top while controls scroll independently, including short screens and large font settings | NOT TESTED |
| Landscape | Playback and controls usable after rotation; no unexpected restart/lost recording | NOT TESTED |
| Phone microphone | Recorded vocal plays cleanly, no double capture, no silent output | NOT TESTED |
| Offline remix | Voice gain affects exported WAV, backing defaults to 85%, no audible clipping or severe pumping at normal settings | NOT TESTED |
| LIVE OTG | USB/headset routing works, no duplicate monitoring or capture, clean start/stop/restart and unplug handling | NOT TESTED |
| Sync | No hard-coded 253 ms offset; auto estimate is validated against actual playback; manual ±1 ms adjustment works | NOT TESTED |
| Export | Recording, voice-only playback, WAV remix, reopen, share and MediaStore access work after app restart | NOT TESTED |
| Online YouTube | Supported embeds open when permitted; unsupported embeds fail gracefully; do not claim capture/mix of YouTube audio | NOT TESTED |
| Reliability | Repeat record/stop/mix 10 times; test background/foreground, low storage, permissions denied and interruptions | NOT TESTED |

## Automated verification before release
1. Run JVM tests and `assembleDebug` on the exact release candidate commit.
2. Validate artifact SHA-256 and installability; inspect Android manifest and versionName/versionCode.
3. Add focused regression tests for PCM short writes, zero/negative AudioTrack returns, independent stereo ducking envelopes, sample-rate-dependent smoothing and offset bounds as implementation permits.
4. Produce signed release APK only after signing is configured safely; never commit signing credentials.
5. Publish v1.0 only when release-blocking device tests are documented as passed. Otherwise label build **candidate/debug**.

## Known limitations
YouTube iframe playback is separate from local Media3 audio and is not a licensed source for offline WAV mixing. Audio effects applied by external soundcard hardware cannot be disabled reliably by the app. A successful CI run does not establish end-to-end audio quality.
