package com.kafkasl.phonewhisper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kafkasl.phonewhisper.ui.DiagnosticsScreen
import com.kafkasl.phonewhisper.ui.HistoryItemUi
import com.kafkasl.phonewhisper.ui.HistoryScreen
import com.kafkasl.phonewhisper.ui.HistoryUiState
import com.kafkasl.phonewhisper.ui.LocalModelUi
import com.kafkasl.phonewhisper.ui.ModelPickerContent
import com.kafkasl.phonewhisper.ui.PhoneWhisperTheme
import com.kafkasl.phonewhisper.ui.PickerTarget
import com.kafkasl.phonewhisper.ui.PickerUi
import com.kafkasl.phonewhisper.ui.PromptPreset
import com.kafkasl.phonewhisper.ui.SettingsScreen
import com.kafkasl.phonewhisper.ui.SettingsUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the Compose screens on the JVM. Images land in app/build/outputs/roborazzi/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            PhoneWhisperTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private val ready = SettingsUiState(
        micGranted = true, accessibilityOn = true, engineReady = true,
        engineDetail = "Custom · eleven-scribe",
        mode = TranscriptionMode.CLOUD_WITH_FALLBACK, language = "is",
        serviceName = "Custom", baseUrl = "https://litellm.example.com/v1", apiKeyHint = "••••x9Qa",
        sttModel = "eleven-scribe",
        connection = "Connected: 14 models (3 speech-to-text, 9 chat)", connectionOk = true,
        localModels = listOf(
            LocalModelUi("p110", "Parakeet 110M", "★★★ Best value · 100 MB", installed = true, selected = true),
            LocalModelUi("wb", "Whisper Base", "★★★ · 199 MB", installed = false, selected = false),
            LocalModelUi("p06", "Parakeet 0.6B", "★★★★ Best quality · 465 MB", installed = false, selected = false),
            LocalModelUi("mt", "Moonshine Tiny", "★★☆ Fast · 103 MB", installed = true, selected = false),
            LocalModelUi("wt", "Whisper Turbo (multilingual)", "★★★★ Icelandic + 90 languages · slow, ~1 GB on disk · 564 MB",
                installed = false, selected = false, progress = 0.42f),
        ),
        cleanupOn = true, prompt = PromptPreset.DEV, chatModel = "claude-haiku",
        historyOn = true, retentionDays = 7, version = "0.3.0",
    )

    @Test @Config(qualifiers = "w400dp-h2900dp-xxhdpi")
    fun settings_full_light() = shot("settings_full_light") { SettingsScreen(ready, {}) }

    @Test fun settings_top_light() = shot("settings_top_light") { SettingsScreen(ready, {}) }

    @Test fun settings_top_dark() = shot("settings_top_dark", dark = true) { SettingsScreen(ready, {}) }

    @Test fun settings_setup_needed_with_crash_banner() = shot("settings_setup_needed") {
        SettingsScreen(
            ready.copy(micGranted = false, accessibilityOn = false, engineReady = false, engineDetail = "Set API key or download a local model",
                crashed = true, apiKeyHint = null, connection = null, connectionOk = null, serviceName = "OpenAI",
                baseUrl = "https://api.openai.com/v1", sttModel = "whisper-1", offlineHint = "Download a local model to keep working offline"),
            {},
        )
    }

    private val pickerModels = listOf(
        RemoteModel("eleven-scribe", RemoteModel.Kind.STT), RemoteModel("whisper-large-v3-turbo", RemoteModel.Kind.STT),
        RemoteModel("gpt-4o-transcribe", RemoteModel.Kind.STT), RemoteModel("gpt-4o-mini-transcribe", RemoteModel.Kind.STT),
        RemoteModel("claude-haiku", RemoteModel.Kind.CHAT), RemoteModel("gpt-4o-mini", RemoteModel.Kind.CHAT),
        RemoteModel("gemini-flash", RemoteModel.Kind.AUDIO_CHAT), RemoteModel("eleven-voice", RemoteModel.Kind.OTHER),
        RemoteModel("text-embedding-3-small", RemoteModel.Kind.OTHER),
    )

    @Test fun model_picker_transcription() = shot("model_picker_transcription") {
        Box(Modifier.fillMaxSize()) {
            ModelPickerContent(PickerUi(PickerTarget.TRANSCRIPTION, "eleven-scribe", pickerModels, loading = false), {})
        }
    }

    @Test fun model_picker_show_all_dark() = shot("model_picker_show_all_dark", dark = true) {
        ModelPickerContent(PickerUi(PickerTarget.TRANSCRIPTION, "eleven-scribe", pickerModels, loading = true), {}, initialShowAll = true)
    }

    @Test fun model_picker_cleanup_search() = shot("model_picker_cleanup_search") {
        ModelPickerContent(PickerUi(PickerTarget.CLEANUP, "claude-haiku", pickerModels, loading = false), {}, initialQuery = "gpt")
    }

    @Test fun model_picker_sheet_over_settings() {
        compose.setContent {
            PhoneWhisperTheme(dynamicColor = false) {
                SettingsScreen(ready.copy(picker = PickerUi(PickerTarget.TRANSCRIPTION, "eleven-scribe", pickerModels, loading = false)), {})
            }
        }
        compose.waitForIdle()
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/outputs/roborazzi/model_picker_sheet.png")
    }

    @Test fun history_light() {
        val audio = File.createTempFile("hist", ".wav").apply { writeText("x"); deleteOnExit() }
        val now = System.currentTimeMillis()
        fun entry(id: Long, ago: Long, text: String?, err: String? = null, raw: String? = null) = HistoryItemUi(
            HistoryEntry(id, now - ago, if (err == null) HistoryEntry.Status.OK else HistoryEntry.Status.FAILED,
                text, raw, if (err == null) "Cloud" else null, "com.slack", err, if (err != null) audio.path else null, 12_000),
            "Slack",
        )
        shot("history_light") {
            HistoryScreen(
                HistoryUiState(
                    items = listOf(
                        entry(1, 60_000, "Can we move the standup to 10:30 tomorrow? I have a dentist appointment first thing.", raw = "can we move the standup"),
                        entry(2, 3_600_000, null, err = "Could not connect: timeout"),
                        entry(3, 7_200_000, "Góðan daginn, ég verð aðeins seinn í dag."),
                        entry(4, 86_400_000, "Remember to push the release branch before the demo."),
                    ),
                    loaded = true, retrying = emptySet(), retentionDays = 7,
                ),
                {}, {}, { _, _ -> }, {}, {}, {}, {},
            )
        }
    }

    @Test fun history_empty_dark() = shot("history_empty_dark", dark = true) {
        HistoryScreen(HistoryUiState(loaded = true, retentionDays = 0), {}, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test fun diagnostics_light() = shot("diagnostics_light") {
        DiagnosticsScreen(
            report = "=== Phone Whisper diagnostics ===\nGenerated: 2026-09-30 10:31:02.114 +0000\nApp: com.kafkasl.phonewhisper 0.3.0 (2)\n" +
                "Device: Google Pixel 8 (shiba)\nAndroid: 15 (SDK 35)\nMode: cloud_fallback\nEndpoint: https://litellm.example.com/v1\n" +
                "API key set: true\nSTT model: eleven-scribe\nCrash reports: 1\n\n=== Phone Whisper crash ===\n" +
                "java.lang.IllegalArgumentException: View=LinearLayout not attached to window manager\n" +
                "    at android.view.WindowManagerGlobal.findViewLocked(WindowManagerGlobal.java:604)\n" +
                "    at com.kafkasl.phonewhisper.WhisperAccessibilityService.updatePillPosition(...)",
            crashCount = 1, onBack = {}, onCopy = {}, onShare = {}, onClear = {}, onTestCrash = {},
        )
    }
}
