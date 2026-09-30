package com.kafkasl.phonewhisper

/** Builds URLs for OpenAI-compatible APIs (OpenAI, Groq, self-hosted faster-whisper, ...). */
object Endpoints {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    private val KNOWN_SUFFIXES = listOf("/audio/transcriptions", "/chat/completions", "/models")

    /**
     * Normalise what the user typed: trims whitespace and trailing slashes, and strips a
     * pasted endpoint path so "https://host/v1/audio/transcriptions" becomes "https://host/v1".
     */
    fun normalizeBase(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return DEFAULT_BASE_URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        for (suffix in KNOWN_SUFFIXES) {
            if (url.endsWith(suffix)) url = url.removeSuffix(suffix).trimEnd('/')
        }
        return url
    }

    fun transcriptions(base: String) = normalizeBase(base) + "/audio/transcriptions"
    fun chatCompletions(base: String) = normalizeBase(base) + "/chat/completions"
    fun models(base: String) = normalizeBase(base) + "/models"
}

/** Decides which engine handles a recording. Pure logic so it can be unit tested. */
object TranscriptionRouter {

    sealed class Plan {
        /** Try cloud first; if [fallbackToLocal], use the local model when the network fails. */
        data class Cloud(val fallbackToLocal: Boolean) : Plan()
        object Local : Plan()
        data class Unavailable(val reason: String) : Plan()
    }

    fun plan(
        mode: TranscriptionMode,
        online: Boolean,
        hasApiKey: Boolean,
        hasLocalModel: Boolean,
        baseUrl: String = Endpoints.DEFAULT_BASE_URL,
    ): Plan {
        // Self-hosted servers often run without a key; only the public OpenAI API strictly needs one.
        val keyRequired = Endpoints.normalizeBase(baseUrl) == Endpoints.DEFAULT_BASE_URL
        val cloudConfigured = hasApiKey || !keyRequired
        return when (mode) {
            TranscriptionMode.LOCAL_ONLY ->
                if (hasLocalModel) Plan.Local else Plan.Unavailable("Download a local model in Phone Whisper")

            TranscriptionMode.CLOUD_ONLY -> when {
                !cloudConfigured -> Plan.Unavailable("Set API key in Phone Whisper")
                !online -> Plan.Unavailable("No network connection")
                else -> Plan.Cloud(fallbackToLocal = false)
            }

            TranscriptionMode.CLOUD_WITH_FALLBACK -> when {
                online && cloudConfigured -> Plan.Cloud(fallbackToLocal = hasLocalModel)
                hasLocalModel -> Plan.Local
                !online -> Plan.Unavailable("Offline and no local model downloaded")
                else -> Plan.Unavailable("Set API key or download a local model")
            }
        }
    }
}
