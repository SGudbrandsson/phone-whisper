package com.kafkasl.phonewhisper

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A model returned by an OpenAI-compatible `GET /models`. */
data class RemoteModel(val id: String, val kind: Kind, val ownedBy: String? = null) {
    enum class Kind {
        /** Serves /audio/transcriptions (Whisper, gpt-4o-transcribe, Voxtral, ...). */
        STT,
        /** A chat model that accepts audio input (gpt-4o-audio-preview, Gemini, ...). */
        AUDIO_CHAT,
        CHAT,
        /** TTS, embeddings, images, moderation, realtime: useless for either picker. */
        OTHER,
    }
}

/**
 * Lists and classifies models so the settings can offer a picker instead of a text field.
 * Classification uses provider metadata where it exists and falls back to the model name:
 * - `task` (self-hosted servers such as speaches: "automatic-speech-recognition")
 * - `capabilities` (Mistral: a transcription flag, `completion_chat`, `audio`)
 * - `architecture.input_modalities` / `output_modalities` (OpenRouter)
 * OpenAI, Groq and Gemini's OpenAI-compatible endpoint return only ids, so names decide there.
 */
object ModelCatalog {

    data class FetchResult(val models: List<RemoteModel>?, val error: String?)

    private val OTHER_NAMES = listOf(
        "tts", "text-to-speech", "embed", "dall-e", "image", "imagen", "moderation", "rerank",
        "realtime", "davinci", "babbage", "sora", "veo", "guard", "search-preview", "aqa",
    )
    private val STT_NAMES = listOf(
        "whisper", "transcribe", "transcription", "stt", "speech", "voxtral", "parakeet", "canary", "scribe",
    )
    private val AUDIO_CHAT_NAMES = listOf("audio", "gemini", "phi-4-multimodal", "qwen2-audio", "qwen2.5-omni", "omni")

    fun parse(json: String): List<RemoteModel> {
        val root = JSONObject(json)
        val data: JSONArray = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val out = LinkedHashMap<String, RemoteModel>()
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            val id = obj.optString("id").ifBlank { obj.optString("name") }.trim()
            if (id.isEmpty()) continue
            out[id] = RemoteModel(id, classify(obj), obj.optString("owned_by").ifBlank { null })
        }
        return out.values.toList()
    }

    fun classify(obj: JSONObject): RemoteModel.Kind {
        val id = obj.optString("id").ifBlank { obj.optString("name") }
        fromTask(obj.optString("task"))?.let { return it }
        obj.optJSONObject("capabilities")?.let { caps -> fromCapabilities(caps, id)?.let { return it } }
        obj.optJSONObject("architecture")?.let { arch -> fromModalities(arch)?.let { return it } }
        return fromName(id)
    }

    private fun fromTask(task: String): RemoteModel.Kind? = when {
        task.isBlank() -> null
        task.contains("speech-recognition") || task.contains("transcri") -> RemoteModel.Kind.STT
        task.contains("text-to-speech") || task.contains("embedding") || task.contains("feature-extraction") -> RemoteModel.Kind.OTHER
        task.contains("text-generation") || task.contains("chat") -> RemoteModel.Kind.CHAT
        else -> null
    }

    private fun fromCapabilities(caps: JSONObject, id: String): RemoteModel.Kind? {
        val on = caps.keys().asSequence().filter { caps.optBoolean(it, false) }.map { it.lowercase() }.toSet()
        if (on.isEmpty()) return null
        if (on.any { "transcri" in it }) return RemoteModel.Kind.STT
        val chat = "completion_chat" in on || "chat" in on
        if (chat) return when {
            // Voxtral chats about audio and also serves /audio/transcriptions.
            fromName(id) == RemoteModel.Kind.STT -> RemoteModel.Kind.STT
            on.any { it == "audio" || it.startsWith("audio_input") } -> RemoteModel.Kind.AUDIO_CHAT
            else -> RemoteModel.Kind.CHAT
        }
        return null
    }

    private fun fromModalities(arch: JSONObject): RemoteModel.Kind? {
        val input = arch.optJSONArray("input_modalities")?.strings() ?: return null
        val output = arch.optJSONArray("output_modalities")?.strings() ?: listOf("text")
        if ("text" !in output) return RemoteModel.Kind.OTHER // image or audio generators
        return if ("audio" in input) RemoteModel.Kind.AUDIO_CHAT else RemoteModel.Kind.CHAT
    }

    fun fromName(id: String): RemoteModel.Kind {
        val n = id.lowercase()
        return when {
            OTHER_NAMES.any { it in n } -> RemoteModel.Kind.OTHER
            STT_NAMES.any { it in n } -> RemoteModel.Kind.STT
            AUDIO_CHAT_NAMES.any { it in n } -> RemoteModel.Kind.AUDIO_CHAT
            else -> RemoteModel.Kind.CHAT
        }
    }

    /** Models for the transcription picker: speech-to-text only unless [showAll]. */
    fun forTranscription(models: List<RemoteModel>, showAll: Boolean): List<RemoteModel> =
        if (showAll) models.sortedWith(compareBy({ it.kind != RemoteModel.Kind.STT }, { it.id }))
        else models.filter { it.kind == RemoteModel.Kind.STT }.sortedBy { it.id }

    /** Models for the cleanup picker: chat models (audio-capable ones included) unless [showAll]. */
    fun forCleanup(models: List<RemoteModel>, showAll: Boolean): List<RemoteModel> {
        val chat = setOf(RemoteModel.Kind.CHAT, RemoteModel.Kind.AUDIO_CHAT)
        return if (showAll) models.sortedWith(compareBy({ it.kind !in chat }, { it.id }))
        else models.filter { it.kind in chat }.sortedBy { it.id }
    }

    fun search(models: List<RemoteModel>, query: String): List<RemoteModel> {
        val terms = query.lowercase().split(' ', '-', '/', '_').filter { it.isNotBlank() }
        if (terms.isEmpty()) return models
        return models.filter { m -> val id = m.id.lowercase(); terms.all { it in id } }
    }

    // --- Network ---

    fun buildRequest(baseUrl: String, apiKey: String): Request = Request.Builder()
        .url(Endpoints.models(baseUrl))
        .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
        .get()
        .build()

    fun parseHttp(code: Int, body: String): FetchResult {
        if (code in 200..299) {
            return try {
                FetchResult(parse(body), null)
            } catch (e: Exception) {
                FetchResult(null, "Unexpected response from /models")
            }
        }
        val detail = try {
            val obj = JSONObject(body)
            when (val err = obj.opt("error")) {
                is JSONObject -> err.optString("message")
                is String -> err
                else -> obj.optString("detail").ifBlank { obj.optString("message") }
            }
        } catch (_: Exception) { "" }
        val hint = when (code) {
            401, 403 -> "API key rejected"
            404 -> "No /models at this address — check the endpoint URL"
            else -> "HTTP $code"
        }
        return FetchResult(null, if (detail.isNotBlank()) "$hint: $detail" else hint)
    }

    /** Fetches `GET {base}/models`. [callback] runs on a background thread. */
    fun fetch(baseUrl: String, apiKey: String, callback: (FetchResult) -> Unit): Call {
        val call = TranscriberClient.client.newCall(buildRequest(baseUrl, apiKey))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) callback(FetchResult(null, "Could not connect: ${e.message ?: e.javaClass.simpleName}"))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = response.use { parseHttp(it.code, it.body?.string() ?: "") }
                if (!call.isCanceled()) callback(result)
            }
        })
        return call
    }

    /** One line for "Test connection". */
    fun describe(result: FetchResult, selectedStt: String? = null): String {
        val models = result.models ?: return result.error ?: "Unknown error"
        val stt = models.count { it.kind == RemoteModel.Kind.STT }
        val chat = models.count { it.kind == RemoteModel.Kind.CHAT || it.kind == RemoteModel.Kind.AUDIO_CHAT }
        val base = "Connected: ${models.size} models ($stt speech-to-text, $chat chat)"
        return if (selectedStt != null && models.none { it.id == selectedStt }) "$base. \"$selectedStt\" isn't listed." else base
    }

    // --- Cache, so the picker opens instantly and works offline ---

    fun toJson(models: List<RemoteModel>): String = JSONArray().apply {
        models.forEach { put(JSONObject().put("id", it.id).put("kind", it.kind.name).put("owned_by", it.ownedBy ?: "")) }
    }.toString()

    fun fromJson(json: String): List<RemoteModel> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemoteModel(o.getString("id"), RemoteModel.Kind.valueOf(o.getString("kind")), o.optString("owned_by").ifBlank { null })
        }
    } catch (_: Exception) { emptyList() }

    private fun JSONArray.strings() = (0 until length()).map { optString(it).lowercase() }
}
