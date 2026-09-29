package com.kafkasl.phonewhisper

import android.content.Context
import android.content.SharedPreferences

/** Where transcription runs. */
enum class TranscriptionMode(val key: String, val label: String, val description: String) {
    CLOUD_WITH_FALLBACK(
        "cloud_fallback",
        "Cloud, local when offline",
        "Uses the cloud endpoint; falls back to the local model when there is no network"
    ),
    CLOUD_ONLY("cloud", "Cloud only", "Always uses the cloud endpoint"),
    LOCAL_ONLY("local", "Local only", "Audio never leaves the device");

    companion object {
        fun fromKey(key: String?): TranscriptionMode? = values().firstOrNull { it.key == key }
    }
}

/** Typed access to the app's settings. The API key is stored encrypted via [SecretStore]. */
class AppSettings(private val ctx: Context) {

    val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secrets by lazy { SecretStore(prefs) }

    var mode: TranscriptionMode
        get() {
            TranscriptionMode.fromKey(prefs.getString(KEY_MODE, null))?.let { return it }
            // Migrate the old boolean switch. Before this setting existed, local was the default.
            return if (prefs.contains(LEGACY_USE_LOCAL)) {
                if (prefs.getBoolean(LEGACY_USE_LOCAL, true)) TranscriptionMode.LOCAL_ONLY
                else TranscriptionMode.CLOUD_WITH_FALLBACK
            } else TranscriptionMode.CLOUD_WITH_FALLBACK
        }
        set(value) { prefs.edit().putString(KEY_MODE, value.key).remove(LEGACY_USE_LOCAL).apply() }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: Endpoints.DEFAULT_BASE_URL
        set(value) { prefs.edit().putString(KEY_BASE_URL, value.trim()).apply() }

    var sttModel: String
        get() = prefs.getString(KEY_STT_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_STT_MODEL
        set(value) { prefs.edit().putString(KEY_STT_MODEL, value.trim()).apply() }

    /** ISO-639-1 code such as "en" or "is"; blank means auto-detect. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value.trim().lowercase()).apply() }

    var chatModel: String
        get() = prefs.getString(KEY_CHAT_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CHAT_MODEL
        set(value) { prefs.edit().putString(KEY_CHAT_MODEL, value.trim()).apply() }

    var apiKey: String
        get() {
            // One-time migration of the plaintext key written by earlier versions.
            prefs.getString(LEGACY_API_KEY, null)?.let { legacy ->
                if (legacy.isNotBlank()) secrets.put(KEY_API_KEY_ENC, legacy)
                prefs.edit().remove(LEGACY_API_KEY).apply()
            }
            return secrets.get(KEY_API_KEY_ENC) ?: ""
        }
        set(value) {
            if (value.isBlank()) secrets.remove(KEY_API_KEY_ENC) else secrets.put(KEY_API_KEY_ENC, value.trim())
        }

    val hasApiKey get() = apiKey.isNotBlank()

    var usePostProcessing: Boolean
        get() = prefs.getBoolean("use_post_processing", false)
        set(value) { prefs.edit().putBoolean("use_post_processing", value).apply() }

    val postProcessingPrompt: String
        get() = prefs.getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

    var modelName: String
        get() = prefs.getString("model_name", "") ?: ""
        set(value) { prefs.edit().putString("model_name", value).apply() }

    var historyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_HISTORY_ENABLED, value).apply() }

    /** Days to keep history; 0 keeps it forever. */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, HistoryPolicy.DEFAULT_RETENTION_DAYS)
        set(value) { prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply() }

    fun cloudConfig() = TranscriberClient.Config(baseUrl, apiKey, sttModel, language)

    companion object {
        const val PREFS_NAME = "phonewhisper"
        const val DEFAULT_STT_MODEL = "whisper-1"
        const val DEFAULT_CHAT_MODEL = "gpt-4o-mini"

        private const val KEY_MODE = "transcription_mode"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_STT_MODEL = "stt_model"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_CHAT_MODEL = "chat_model"
        private const val KEY_API_KEY_ENC = "api_key_enc"
        private const val KEY_HISTORY_ENABLED = "history_enabled"
        private const val KEY_RETENTION_DAYS = "history_retention_days"
        private const val LEGACY_USE_LOCAL = "use_local"
        private const val LEGACY_API_KEY = "api_key"
    }
}
