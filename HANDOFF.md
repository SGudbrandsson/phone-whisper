# Handoff: Phone Whisper fork (branch `feat/cloud-fallback`)

This is Siggi's fork of kafkasl/phone-whisper, an Android floating-bubble dictation app
(an accessibility service draws the overlay). The goal is a Wispr Flow–style dictation
app for Android. It sends audio to an OpenAI-compatible endpoint, falls back to an
on-device model when offline, and keeps a history.

## Status (2026-09-30)

The record crash is fixed (pill `updateViewLayout` ran before `addView`) and covered by
`OverlayFlowTest`, which drives the real service under Robolectric. Since then the branch
gained in-app crash reports (`Diagnostics`, `DiagnosticsActivity`), a `/models` picker
(`ModelCatalog`, including LiteLLM `/model/info`), multilingual offline Whisper Turbo, and
a Compose/Material 3 UI (`ui/`). Screenshots: `./gradlew testDebugUnitTest` writes PNGs to
`app/build/outputs/roborazzi/` (`ScreenshotTest`, `OverlayScreenshotTest`).

Siggi uses a LiteLLM proxy to ElevenLabs Scribe (plain `/audio/transcriptions`), so the
chat-completions `input_audio` path was not built.

Still open: a history entry can be written if cancel lands between the success callback's
session check and the write; offline Whisper is decoded in fixed 28 s chunks (no VAD).

## What's on the branch (7 commits on top of upstream `e6518cd`)

| Area | Files |
|---|---|
| sherpa-onnx from the JitPack AAR `v1.13.8` instead of vendored wrappers plus missing jniLibs | `settings.gradle.kts`, `app/build.gradle.kts` |
| Modes: cloud with local fallback (default), cloud only, local only. Offline or unreachable (4s connect timeout) falls back to local. HTTP errors are shown, not masked | `TranscriptionRouter.kt` (pure, tested), `TranscriptionEngine.kt`, `Connectivity.kt` |
| Configurable OpenAI-compatible base URL, STT model, language, cleanup model. OpenAI and Groq presets. `http://` allowed for LAN servers (with a warning) | `AppSettings.kt`, `TranscriberClient.kt`, `PostProcessor.kt`, `res/xml/network_security_config.xml` |
| API key encrypted with an Android Keystore AES-GCM key; the old plaintext key is migrated | `SecretStore.kt` |
| Recording pill beside the bubble: cancel, waveform, timer, stop. Cancel also works during transcription | `WhisperAccessibilityService.kt`, `WaveformView.kt` |
| History in SQLite. Failed dictations keep a WAV for retry. History screen; long-press bubble for a quick sheet of the last 10; auto-clear after 1/7/30 days or never (default 7) via WorkManager | `HistoryStore.kt`, `HistoryActivity.kt`, `HistoryCleanupWorker.kt` |
| Bubble only shown while the keyboard is up or an editable field is focused; hidden on the lock screen | `WhisperAccessibilityService.kt`, `res/xml/accessibility_service_config.xml` |
| APK 54 MB → 19 MB (compressed native libs and dex, unused sherpa C/C++ API libs dropped) | `app/build.gradle.kts` |

The transcription flow is: `WhisperAccessibilityService.stopAndTranscribe()` →
`TranscriptionEngine.run()`. The engine plans on a background thread, delivers the outcome
exactly once, and the job is cancellable. The service drops results from stale sessions
using the `session` counter. `HistoryActions.retry()` reuses the same engine.

Tests: 53 JVM tests pass. They cover the router, endpoints, the HTTP client with
MockWebServer, the history DB with Robolectric, the WAV reader and writer, and level
metering. None of them exercise the overlay or the service, which is how the crash got
through.

## Remaining work (Siggi's requests, in priority order)

1. **Fix the record crash and add the regression test** (see above).
2. **In-app crash reports.** Siggi can't read logcat.
   - Install a `Thread.setDefaultUncaughtExceptionHandler` in an `Application` subclass.
     Write the stack trace, device and app version, and the app's recent log lines
     (`logcat -d --pid=<own pid> -t 300`; an app can read its own logs) to
     `filesDir/crashes/`. Then chain to the previous handler.
   - Also log non-fatal failures: transcription errors and caught exceptions.
   - On the next launch, show a banner saying the app crashed last time, with a View
     button. Add a "Crash reports & diagnostics" screen with Copy and Share (share
     intent) so he can paste a report to Claude.
3. **Model dropdown instead of typing model names.**
   - After a key is entered or the endpoint changes, call `GET {base}/models` with the
     Bearer key. Offer a searchable picker, with a "show all models" escape hatch and a
     "Test connection" button.
   - Filter to speech-to-text using metadata where it exists, else the model name:
     - Metadata to check: a `capabilities` object (e.g. Mistral's audio transcription
       flag), `task == "automatic-speech-recognition"` (self-hosted servers such as
       speaches), and `architecture.input_modalities` containing `audio` (OpenRouter).
     - Name patterns as fallback: `whisper`, `transcribe`, `stt`, `speech`, `voxtral`,
       `parakeet`, `canary`, `scribe`.
     - Check the real response shapes of OpenAI, Groq, Mistral, OpenRouter and Gemini's
       OpenAI-compatible endpoint before relying on any field.
   - The cleanup-model picker should do the inverse: chat models only, excluding
     STT/TTS/embedding/image models.
   - Siggi mentioned using a "remote multimodal model". Multimodal chat models don't
     serve `/audio/transcriptions`. Consider a second transcription path: chat
     completions with an `input_audio` content part (base64 WAV, `format: "wav"`) and a
     "transcribe verbatim" system prompt, chosen automatically when the picked model is
     audio-input chat rather than a dedicated STT model. Ask Siggi which provider and
     model he's using before building this.
4. **UI redesign.** Siggi says the current settings UI is ugly.
   - The current `MainActivity` and `HistoryActivity` are hand-built Views. Rebuild them
     in Jetpack Compose with Material 3 and dynamic color: a setup/status card at the
     top (permissions, accessibility, engine ready), grouped cards for Transcription /
     Cloud endpoint / Local models / Cleanup / History / Diagnostics, and the model
     picker as a bottom sheet with search.
   - Keep the overlay as Views, since Compose in overlay windows needs lifecycle-owner
     wiring, but polish it.
   - Before borrowing components from other open-source dictation apps, check each
     license: Apache-2.0/MIT is fine; GPL/AGPL and FUTO's license are not compatible
     with this Apache-2.0 project.
   - Render screenshots on the JVM (Roborazzi with Robolectric native graphics, or
     Paparazzi) to check the look without a device, and share them with Siggi.
5. **Open from review (minor):**
   - A history entry can still be written if cancel lands in a tiny window after the
     callback's session check.
   - `HistoryActivity` can leak its own model if rotated mid-retry.
   - Offline Icelandic needs a multilingual Whisper model; the bundled local models
     are English-only. Siggi is Icelandic, so ask whether to add one.

## Build notes

- Requires JDK 17+ and Android SDK platform 34 / build-tools 34 (`local.properties`
  with `sdk.dir`).
- `./gradlew testDebugUnitTest assembleDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- **Cloud sandbox quirk:** Maven Central (`repo.maven.apache.org`) returned HTTP 429
  through the sandbox proxy. The workaround was a machine-local Gradle init script (not
  committed) that swaps `MavenRepo` for Google's mirror
  `https://maven-central.storage-download.googleapis.com/maven2/` in
  `settingsEvaluated`. JitPack works directly.
- The upstream Makefile assumes macOS paths. Use `./gradlew` directly on Linux.
- Debug builds are signed with the debug key, so the upstream Phone Whisper app must be
  uninstalled before installing.
- The APK must stay under 30 MB to send it back through chat.
- Commits end with Claude co-author trailers. Keep work on this branch unless Siggi says
  otherwise.
