package com.kafkasl.phonewhisper.ui

import com.kafkasl.phonewhisper.RemoteModel
import com.kafkasl.phonewhisper.TranscriptionMode

data class LocalModelUi(
    val archive: String,
    val name: String,
    val detail: String,
    val installed: Boolean,
    val selected: Boolean,
    /** 0..1 while downloading, -1 while extracting, null when idle. */
    val progress: Float? = null,
    val error: String? = null,
)

enum class PickerTarget(val title: String) { TRANSCRIPTION("Transcription model"), CLEANUP("Cleanup model") }

data class PickerUi(
    val target: PickerTarget,
    val current: String,
    val models: List<RemoteModel>,
    val loading: Boolean,
    val error: String? = null,
)

data class ServicePreset(val name: String, val baseUrl: String, val sttModel: String, val chatModel: String)

enum class PromptPreset(val title: String, val subtitle: String) {
    DEV("Dev cleanup", "Coding, CLI and project names"),
    SIMPLE("Simple cleanup", "Grammar, punctuation, light cleanup"),
    CUSTOM("Custom", "Your own prompt"),
}

/** Everything the settings screen shows. Built by [SettingsViewModel], faked in screenshot tests. */
data class SettingsUiState(
    val micGranted: Boolean = false,
    val accessibilityOn: Boolean = false,
    val engineReady: Boolean = false,
    val engineDetail: String = "",
    val offlineHint: String? = null,
    val crashed: Boolean = false,
    val showOnlyWhenTyping: Boolean = true,
    val mode: TranscriptionMode = TranscriptionMode.CLOUD_WITH_FALLBACK,
    val language: String = "",
    val serviceName: String = "OpenAI",
    val baseUrl: String = "",
    /** "••••abcd" or null when no key is stored. */
    val apiKeyHint: String? = null,
    val sttModel: String = "",
    val connection: String? = null,
    val connectionOk: Boolean? = null,
    val testing: Boolean = false,
    val localModels: List<LocalModelUi> = emptyList(),
    val cleanupOn: Boolean = false,
    val prompt: PromptPreset = PromptPreset.DEV,
    val customPrompt: String = "",
    val chatModel: String = "",
    val historyOn: Boolean = true,
    val retentionDays: Int = 7,
    val picker: PickerUi? = null,
    val version: String = "",
) {
    val ready get() = micGranted && accessibilityOn && engineReady
    val usesCloud get() = mode != TranscriptionMode.LOCAL_ONLY
    val showCloud get() = usesCloud || cleanupOn
    val showLocal get() = mode != TranscriptionMode.CLOUD_ONLY
}

sealed interface SettingsEvent {
    data object GrantMic : SettingsEvent
    data object OpenAccessibility : SettingsEvent
    data object ViewCrash : SettingsEvent
    data object DismissCrash : SettingsEvent
    data class SetOnlyWhenTyping(val on: Boolean) : SettingsEvent
    data class SetMode(val mode: TranscriptionMode) : SettingsEvent
    data class SetLanguage(val code: String) : SettingsEvent
    data class ChooseService(val preset: ServicePreset) : SettingsEvent
    data class SetCustomUrl(val url: String) : SettingsEvent
    data class SetApiKey(val key: String) : SettingsEvent
    data object TestConnection : SettingsEvent
    data class OpenPicker(val target: PickerTarget) : SettingsEvent
    data object RefreshPicker : SettingsEvent
    data object ClosePicker : SettingsEvent
    data class PickModel(val target: PickerTarget, val id: String) : SettingsEvent
    data class LocalModelTap(val archive: String) : SettingsEvent
    data class DeleteLocalModel(val archive: String) : SettingsEvent
    data class SetCleanup(val on: Boolean) : SettingsEvent
    data class SetPrompt(val preset: PromptPreset) : SettingsEvent
    data class SetCustomPrompt(val text: String) : SettingsEvent
    data class SetHistory(val on: Boolean) : SettingsEvent
    data class SetRetention(val days: Int) : SettingsEvent
    data object ClearHistory : SettingsEvent
    data object OpenHistory : SettingsEvent
    data object OpenDiagnostics : SettingsEvent
}
