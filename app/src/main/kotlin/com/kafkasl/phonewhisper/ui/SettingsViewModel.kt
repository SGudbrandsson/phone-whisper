package com.kafkasl.phonewhisper.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.kafkasl.phonewhisper.AppSettings
import com.kafkasl.phonewhisper.Diagnostics
import com.kafkasl.phonewhisper.DownloadState
import com.kafkasl.phonewhisper.Endpoints
import com.kafkasl.phonewhisper.HistoryPolicy
import com.kafkasl.phonewhisper.HistoryStore
import com.kafkasl.phonewhisper.LocalTranscriber
import com.kafkasl.phonewhisper.MODEL_CATALOG
import com.kafkasl.phonewhisper.ModelCatalog
import com.kafkasl.phonewhisper.ModelDownloader
import com.kafkasl.phonewhisper.ModelLoader
import com.kafkasl.phonewhisper.PostProcessor
import com.kafkasl.phonewhisper.TranscriptionMode
import com.kafkasl.phonewhisper.TranscriptionRouter
import com.kafkasl.phonewhisper.WhisperAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.thread

/**
 * Settings logic. Survives rotation, so downloads and model lists keep their progress.
 * Events that need an Activity (permissions, opening screens) are handled by MainActivity.
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()
    private val settings = AppSettings(app)
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state

    private val downloads = mutableMapOf<String, Pair<Float?, String?>>()
    private var connection: Pair<String, Boolean>? = null
    private var testing = false

    init { refresh() }

    /** Re-reads everything; called on resume and after each change. */
    fun refresh() {
        val mic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val acc = WhisperAccessibilityService.instance != null
        val installed = LocalTranscriber.availableModels(ctx)
        val hasModel = installed.isNotEmpty()
        val mode = settings.mode
        val apiKey = settings.apiKey
        // Keep the selection on an installed model.
        if (settings.modelName !in installed) MODEL_CATALOG.firstOrNull { it.archive in installed }?.let { settings.modelName = it.archive }

        val plan = TranscriptionRouter.plan(mode, online = true, hasApiKey = apiKey.isNotBlank(), hasLocalModel = hasModel, baseUrl = settings.baseUrl)
        val engineReady = plan !is TranscriptionRouter.Plan.Unavailable
        val engineDetail = when {
            plan is TranscriptionRouter.Plan.Unavailable -> plan.reason
            mode == TranscriptionMode.LOCAL_ONLY -> "On-device: ${MODEL_CATALOG.firstOrNull { it.archive == settings.modelName }?.name ?: settings.modelName}"
            else -> "${serviceName(settings.baseUrl)} · ${settings.sttModel}"
        }
        val current = settings.postProcessingPrompt
        val prompt = when (current) {
            PostProcessor.DEV_PROMPT -> PromptPreset.DEV
            PostProcessor.SIMPLE_PROMPT -> PromptPreset.SIMPLE
            else -> PromptPreset.CUSTOM
        }
        _state.value = SettingsUiState(
            micGranted = mic,
            accessibilityOn = acc,
            engineReady = engineReady,
            engineDetail = engineDetail,
            offlineHint = if (mode == TranscriptionMode.CLOUD_WITH_FALLBACK && !hasModel) "Download a local model to keep working offline" else null,
            crashed = Diagnostics.hasUnseenCrash(ctx),
            showOnlyWhenTyping = settings.showOnlyWhenTyping,
            mode = mode,
            language = settings.language,
            serviceName = serviceName(settings.baseUrl),
            baseUrl = Endpoints.normalizeBase(settings.baseUrl),
            apiKeyHint = apiKey.takeIf { it.isNotBlank() }?.let { "••••${it.takeLast(4)}" },
            sttModel = settings.sttModel,
            connection = connection?.first,
            connectionOk = connection?.second,
            testing = testing,
            localModels = MODEL_CATALOG.map { m ->
                val (progress, error) = downloads[m.archive] ?: (null to null)
                LocalModelUi(m.archive, m.name, "${m.quality} · ${m.sizeMb} MB", m.archive in installed,
                    m.archive == settings.modelName && m.archive in installed, progress, error)
            },
            cleanupOn = settings.usePostProcessing,
            prompt = prompt,
            customPrompt = settings.prefs.getString(KEY_CUSTOM_PROMPT, null) ?: current.takeIf { prompt == PromptPreset.CUSTOM } ?: "",
            chatModel = settings.chatModel,
            historyOn = settings.historyEnabled,
            retentionDays = settings.retentionDays,
            picker = _state.value.picker,
            version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" },
        )
    }

    fun onEvent(e: SettingsEvent) {
        when (e) {
            is SettingsEvent.SetOnlyWhenTyping -> {
                settings.showOnlyWhenTyping = e.on
                WhisperAccessibilityService.instance?.refreshVisibility()
            }
            is SettingsEvent.SetMode -> { settings.mode = e.mode; WhisperAccessibilityService.instance?.reloadModel() }
            is SettingsEvent.SetLanguage -> { settings.language = e.code; WhisperAccessibilityService.instance?.reloadModel() }
            is SettingsEvent.ChooseService -> {
                settings.baseUrl = e.preset.baseUrl
                settings.sttModel = e.preset.sttModel
                settings.chatModel = e.preset.chatModel
                testConnection()
            }
            is SettingsEvent.SetCustomUrl -> { settings.baseUrl = Endpoints.normalizeBase(e.url); testConnection() }
            is SettingsEvent.SetApiKey -> { settings.apiKey = e.key; if (e.key.isNotBlank()) testConnection() else connection = null }
            SettingsEvent.TestConnection -> testConnection()
            is SettingsEvent.OpenPicker -> openPicker(e.target)
            SettingsEvent.RefreshPicker -> _state.value.picker?.let { loadPicker(it.target) }
            SettingsEvent.ClosePicker -> _state.update { it.copy(picker = null) }
            is SettingsEvent.PickModel -> {
                if (e.target == PickerTarget.TRANSCRIPTION) settings.sttModel = e.id else settings.chatModel = e.id
                _state.update { it.copy(picker = null) }
            }
            is SettingsEvent.LocalModelTap -> onLocalModel(e.archive)
            is SettingsEvent.DeleteLocalModel -> deleteLocalModel(e.archive)
            is SettingsEvent.SetCleanup -> settings.usePostProcessing = e.on
            is SettingsEvent.SetPrompt -> settings.prefs.edit().putString(KEY_PROMPT, when (e.preset) {
                PromptPreset.DEV -> PostProcessor.DEV_PROMPT
                PromptPreset.SIMPLE -> PostProcessor.SIMPLE_PROMPT
                PromptPreset.CUSTOM -> customPromptOrDefault()
            }).apply()
            is SettingsEvent.SetCustomPrompt -> {
                val text = e.text.ifBlank { PostProcessor.DEFAULT_PROMPT }
                settings.prefs.edit().putString(KEY_CUSTOM_PROMPT, text).putString(KEY_PROMPT, text).apply()
            }
            is SettingsEvent.SetHistory -> settings.historyEnabled = e.on
            is SettingsEvent.SetRetention -> {
                settings.retentionDays = e.days
                thread { HistoryStore.get(ctx).prune(e.days) }
            }
            SettingsEvent.ClearHistory -> thread { HistoryStore.get(ctx).clearAll() }
            SettingsEvent.DismissCrash, SettingsEvent.ViewCrash -> Diagnostics.markCrashesSeen(ctx)
            SettingsEvent.GrantMic, SettingsEvent.OpenAccessibility,
            SettingsEvent.OpenHistory, SettingsEvent.OpenDiagnostics -> Unit // handled by the Activity
        }
        refresh()
    }

    private fun customPromptOrDefault() = settings.prefs.getString(KEY_CUSTOM_PROMPT, null) ?: PostProcessor.DEFAULT_PROMPT

    private fun testConnection() {
        testing = true
        connection = null
        refresh()
        ModelLoader.refresh(settings) { result ->
            testing = false
            connection = ModelCatalog.describe(result, settings.sttModel) to (result.models != null)
            refresh()
        }
    }

    private fun openPicker(target: PickerTarget) {
        val current = if (target == PickerTarget.TRANSCRIPTION) settings.sttModel else settings.chatModel
        _state.update { it.copy(picker = PickerUi(target, current, settings.cachedModels, loading = true)) }
        loadPicker(target)
    }

    private fun loadPicker(target: PickerTarget) {
        _state.update { s -> s.copy(picker = s.picker?.copy(loading = true, error = null)) }
        ModelLoader.refresh(settings) { result ->
            _state.update { s ->
                val p = s.picker?.takeIf { it.target == target } ?: return@update s
                s.copy(picker = p.copy(models = result.models ?: p.models, loading = false, error = result.error))
            }
        }
    }

    private fun onLocalModel(archive: String) {
        val model = MODEL_CATALOG.firstOrNull { it.archive == archive } ?: return
        if (ModelDownloader.isInstalled(ctx, model)) {
            settings.modelName = archive
            WhisperAccessibilityService.instance?.reloadModel()
            return
        }
        if (downloads[archive]?.first != null) return // already downloading
        downloads[archive] = 0f to null
        ModelDownloader.download(ctx, model) { st ->
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.post {
                when (st) {
                    is DownloadState.Downloading -> downloads[archive] = st.progress to null
                    DownloadState.Extracting -> downloads[archive] = -1f to null
                    DownloadState.Done -> {
                        downloads.remove(archive)
                        settings.modelName = archive
                        WhisperAccessibilityService.instance?.reloadModel()
                    }
                    is DownloadState.Error -> {
                        downloads[archive] = null to st.message
                        Diagnostics.warn("Settings", "Model download failed: ${st.message}")
                    }
                }
                refresh()
            }
        }
    }

    private fun deleteLocalModel(archive: String) {
        val model = MODEL_CATALOG.firstOrNull { it.archive == archive } ?: return
        thread {
            WhisperAccessibilityService.instance?.engine?.unloadLocalModel()
            ModelDownloader.delete(ctx, model)
            android.os.Handler(android.os.Looper.getMainLooper()).post { refresh() }
        }
    }

    companion object {
        const val KEY_PROMPT = "post_processing_prompt"
        const val KEY_CUSTOM_PROMPT = "custom_post_processing_prompt"

        val SERVICES = listOf(
            ServicePreset("OpenAI", Endpoints.DEFAULT_BASE_URL, "whisper-1", "gpt-4o-mini"),
            ServicePreset("Groq", "https://api.groq.com/openai/v1", "whisper-large-v3-turbo", "llama-3.1-8b-instant"),
        )

        fun serviceName(baseUrl: String) =
            SERVICES.firstOrNull { Endpoints.normalizeBase(it.baseUrl) == Endpoints.normalizeBase(baseUrl) }?.name ?: "Custom"

        fun retentionLabel(days: Int) = HistoryPolicy.label(days)
    }
}
