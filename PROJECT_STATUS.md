# IING STD Karaoke — Project Status and Handoff

Updated: 2026-11-23. Source of truth for the **engineering handoff**; user decisions and actual on-device tests take precedence over assumptions in this file.

## Mission and non-negotiable user requirements

Ship a stable Android karaoke application with a clean vocal recording, music mix, and a **working Studio LIVE mixer before AND during recording**. The user has endured ~245 builds and wants fewer install/test cycles.

- Bonkyo F999 NEO connected via LIVE OTG; LIVE1 is obsolete and should not be reintroduced.
- User expects microphone/vocal volume, music playback volume, bass/mid/treble, reverb, compressor and monitor gain in a compact, clearly outlined, collapsible UI with a settings lock.
- Changes must be audible immediately in earphones **before pressing Record**, persist while recording, and be reflected in the recorded vocal. Avoid doubled monitoring, audible delay, clipping and boomy/warbling voice.
- The F999 NEO's physical effects cannot be disabled or read back by Android software. For Studio LIVE tests, manually set physical EQ/effects neutral; distinguish direct hardware monitoring from Android AudioTrack output.
- Preserve Soundcard LIVE as a fallback. Do not assert Studio LIVE works merely because code compiles.
- Updates must install over previous APKs without uninstall; stable signing certificate and monotonically increasing versionCode are both required. Existing APK signed with a different key may require a one-time migration. Preserve user recordings before any uninstall.
- No new features or redesign beyond this scope without the user's agreement.

## What is actually observed on-device (user-reported)

- Offline music playback works.
- **Music LIVE volume slider works**.
- **MIC/vocal volume slider, Studio LIVE EQ/reverb and monitoring-before-record do NOT work** as desired; hardware soundcard controls still dominate the audible signal.
- User disabled physical soundcard effects and moved software controls, with no audible software effects.
- Previous recording had vocal quality/synchronization issues; synchronization reportedly improved, but overall sound quality and live/record match remain unverified.
- UI diagnostic meter was judged clutter and removed; expandable menus should look like outlined/pill buttons.
- Installing new Actions APK over old one reported a package conflict.

## Implemented in repository, NOT verified working on F999 NEO

- `MainActivity.kt`: Soundcard LIVE / Studio LIVE UI; collapsible menu controls; sliders for music, vocal gain, EQ, reverb, compressor, monitor; preview start/stop and recording controls.
- `StudioLiveDsp.kt`: sample-based EQ, ambience, compression, limiting; mutable parameters.
- `WiredVocalMonitor.kt`: microphone AudioRecord -> DSP -> AudioTrack preview, USB input preference and runtime failure reporting.
- `WiredCaptureRecorder.kt`: AudioRecord -> same DSP -> WAV and AudioTrack for recording session.
- `VoiceRecorder.kt`: routes Studio LIVE recording through wired recorder when enabled; otherwise MediaRecorder capture.
- `app/build.gradle.kts`: versionCode based on GITHUB_RUN_NUMBER; optional persistent signing via environment.
- `.github/workflows/build-apk.yml`: reads optional GitHub signing secrets; **secrets not verified configured**, so in-place updates NOT guaranteed.
- `.github/workflows/karaoke-health-check.yml`: read-only source checks every 5 minutes (GitHub may delay); NOT an AI developer and NOT an Android build/audio test.

## Open blockers — priority order

1. **Audio routing/monitoring**: determine which Android AudioRecord input and AudioTrack output are active on F999 NEO LIVE OTG; ensure preview actually captures mic and plays processed signal rather than only hearing hardware direct-monitor. AudioTrack playback through the same device may be impossible or double with direct-monitor on some routing setups. Never promise software can control hardware monitoring.
2. **Single reliable processing path**: confirm DSP settings are applied in preview and recording; ensure no double capture, no preview/record race, and clear error states. Compare actual WAV vocal with live output. Fix failures with targeted tests rather than blind EQ tuning.
3. **Mic gain and FX**: validate audible mic gain and bass/mid/treble/reverb/compression in both states, without distortion. Music playback gain is separate from final music mix level; reconcile these deliberately.
4. **Stable signing**: configure a persistent keystore through repository secrets (do not commit keystore/passwords). Verify certificate fingerprint and two successive builds installing as updates, preserving data. Check GitHub Actions build and unit tests.
5. **Release gate**: complete a single consolidated device test plan and verify clean recordings, sync, monitor, mixing, crash-free navigation, update-in-place and safe fallback before claiming release readiness.

## Working rules to prevent cross-session miscommunication

1. Before changing code: fetch this file, the latest GitHub commits, affected source files and relevant workflow runs; compare with user feedback in the current conversation.
2. Separate **user-tested working**, **code committed**, **CI build passed**, and **device-tested** statuses. Green Actions does NOT prove audio works.
3. Make small, focused commits with meaningful messages. Update this file in the SAME working session with commit references, remaining issues, test results and next action.
4. Do not run autonomous AI code modifications on a 5-minute schedule. Current scheduled health check is READ ONLY. Any future AI agent must work on a separate branch/PR, with explicit permission and safeguards.
5. Never claim to continue working in the background unless an enabled automation actually performs that work. Scheduled jobs may be delayed and cannot be guaranteed to reply inside this same chat.
6. Do not ask user to reinstall until CI and code checks are ready for a consolidated device test.
7. If a change cannot be verified, say so. Record actual observed error text or captured audio evidence, not speculation.

## Latest checkpoint

- 2026-11-23: Added read-only 5-minute health-check workflow, commit `e3ca6b0`.
- 2026-11-23: Created this project handoff document to prevent drift between GitHub and ChatGPT sessions.
- Next action: inspect actual preview-to-record handoff and USB routing, then write reproducible tests; separately inspect signing secrets readiness. No claim of audio fix or release yet.

- 2026-11-23 17:32 WIB: Hardened preview-to-record handoff: `WiredVocalMonitor.stop()` now reports whether its audio worker has actually exited; `beginRecording()` refuses to start a competing microphone capture while teardown remains pending. This is a source-level safeguard, NOT proof of USB hardware monitoring. Prior head `8a40c3b`; prior Build Android APK run `45147707537` succeeded. New commit/build status must be checked after push. Remaining: F999 NEO input/output routing and direct-monitor behavior, WAV/live DSP comparison, stable signing secrets, on-device regression. Next: verify CI then inspect USB routing and capture state handling.

- 2026-11-23 18:26 WIB: Commit `ffec183` hardens `WiredCaptureRecorder`: when an automatically preferred USB mic (or explicitly selected mic) rejects `setPreferredDevice`, recording now aborts with a visible error instead of silently falling back to another microphone. This is a routing safety fix, not proof the F999 NEO works or that Android honors the route after selection. Source reviewed: `WiredVocalMonitor.kt`, `WiredCaptureRecorder.kt`, `StudioLiveDsp.kt`, build workflow. Prior commit `9ea3d3a` reviewed. CI for new change not yet verified. Remaining: confirm routed input at runtime (`routedDevice` after capture starts), ensure no direct-monitor doubling, compare live DSP with WAV, signing secrets and device tests. Next: check Actions then implement routed-device validation/diagnostics.

- 2026-11-23 19:23 WIB: Commit `5e85d25` adds runtime validation in `WiredCaptureRecorder.kt`: after `AudioRecord.startRecording()`, require recording state and verify `routedDevice.id` matches the preferred USB/headset/phone microphone; periodically detect mid-record route changes and fail the take instead of silently producing WAV from the wrong input. This is a code-level routing safeguard, **not** proof of Bonkyo F999 NEO microphone capture or monitor quality. Verified previous build run `45147707537` succeeded including JVM tests and APK assembly; latest commit CI not yet verified. Open: physical USB route test, hardware direct-monitor doubling, live DSP versus WAV, permanent signing. Next: verify latest CI, then inspect/guard monitor preview routing and run one consolidated physical test when ready.

- 2026-11-23 20:23 WIB: Commit `56d13dc` hardens `WiredVocalMonitor.kt` preview routing. If USB input selection is rejected, preview now fails instead of silently using phone mic; after starting capture it verifies actual routed USB input, and periodically checks USB input and selected earphone/USB output during monitoring. Source review completed; latest commit build/CI and Bonkyo F999 NEO behavior NOT yet verified. Existing connector's commit workflow-run lookup only covers PR-triggered runs and returned no runs for prior head `6c52afd`; this does NOT prove CI failed or passed. Open: actual USB mic/audio output test, direct hardware monitor doubling, real-time FX versus WAV, persistent signing. Next: inspect GitHub Actions push run for `56d13dc`, fix compile errors if any, then inspect route and monitoring UX failure feedback before consolidated device test.

- 2026-11-23 21:26 WIB: Commit `0d6ea21` hardens `WiredCaptureRecorder.kt`: after `AudioTrack.play()`, recording verifies PLAYING state and actual output route matches the chosen earphone/USB device; periodic checks now abort the take if the monitoring output switches mid-record. Previous logic checked output presence only, so a silent fallback to another output was possible. Source-level change only; no Bonkyo F999 NEO hardware validation. At inspection, GitHub combined commit statuses for previous head `5f913ea` were empty (not evidence of CI success/failure), and the workflow configuration was reviewed. New commit CI remains unverified. Open: physical direct-monitor doubling, device output routing behavior, DSP live-versus-WAV comparison, persistent signing and consolidated device testing. Next: inspect Actions build for `0d6ea21`, fix any compilation failure, then examine capture-loop error propagation and monitoring latency.

- 2026-11-23 22:26 WIB: Reviewed handoff, latest commits (`15438cf7`, `7eaeaff7`), `app/build.gradle.kts`, `.github/workflows/build-apk.yml`, and prior verified CI run `45147707537` (successful JVM tests and debug APK build, not current HEAD). Found latest workflow would automatically publish a non-draft `--latest` release on any successful signed build, before F999 NEO/device validation. Commit `1ea2473` changes publishing to a **draft prerelease candidate** (`--draft --prerelease`), removes `--latest`, and explicitly marks device/update testing pending. This prevents automatic public final release; signing secrets presence is still unverified. New commit CI not verified yet. Open: LIVE OTG input/monitoring and effects on real device, direct hardware-monitor duplication, WAV/live comparison, stable signing certificate and update-in-place test. Next: check CI for workflow change, then inspect audio failure/latency handling and validate with a single consolidated device test when ready.
