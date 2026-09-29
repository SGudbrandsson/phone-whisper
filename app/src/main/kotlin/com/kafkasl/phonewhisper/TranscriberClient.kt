package com.kafkasl.phonewhisper

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Client for any OpenAI-compatible /audio/transcriptions endpoint. */
object TranscriberClient {

    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        /** ISO-639-1 language code; blank lets the server auto-detect. */
        val language: String = "",
    )

    /**
     * [networkFailure] is true when the server could not be reached at all (DNS, connect
     * timeout, connection reset, ...). That is the case where falling back to the local
     * model makes sense; an HTTP error such as a bad key is reported instead.
     */
    data class Result(val text: String?, val error: String?, val networkFailure: Boolean = false)

    // Short connect timeout so a dead Wi-Fi or unreachable server falls back quickly.
    var client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun parseResponse(json: String): Result = try {
        val obj = JSONObject(json)
        when {
            obj.has("text") -> Result(obj.getString("text"), null)
            obj.has("error") -> Result(null, errorMessage(obj))
            obj.has("detail") -> Result(null, obj.get("detail").toString()) // FastAPI-style servers
            else -> Result(null, "Unknown response")
        }
    } catch (e: Exception) {
        Result(null, e.message ?: "Parse error")
    }

    /** Parses a response, turning non-2xx replies without a usable body into "HTTP <code>". */
    fun parseHttp(code: Int, body: String): Result {
        val parsed = parseResponse(body)
        if (code in 200..299) return parsed
        return if (parsed.error != null && body.trimStart().startsWith("{")) {
            parsed.copy(error = "HTTP $code: ${parsed.error}")
        } else Result(null, "HTTP $code")
    }

    fun buildRequest(wavData: ByteArray, config: Config): Request {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", config.model)
            .apply { if (config.language.isNotBlank()) addFormDataPart("language", config.language) }
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "audio.wav", wavData.toRequestBody("audio/wav".toMediaType()))
            .build()

        return Request.Builder()
            .url(Endpoints.transcriptions(config.baseUrl))
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .post(body)
            .build()
    }

    /** Starts the request. The returned [Call] can be cancelled; a cancelled call does not invoke [callback]. */
    fun transcribe(wavData: ByteArray, config: Config, callback: (Result) -> Unit): Call {
        val call = client.newCall(buildRequest(wavData, config))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) return
                callback(Result(null, e.message ?: e.javaClass.simpleName, networkFailure = true))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.use { parseHttp(it.code, it.body?.string() ?: "") }
                if (!call.isCanceled()) callback(result)
            }
        })
        return call
    }

    private fun errorMessage(obj: JSONObject): String {
        val err = obj.get("error")
        return if (err is JSONObject) err.optString("message", err.toString()) else err.toString()
    }
}
