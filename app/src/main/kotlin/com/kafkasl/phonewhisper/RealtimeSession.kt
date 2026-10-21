package com.kafkasl.phonewhisper

import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams audio to ElevenLabs Scribe v2 Realtime while the user speaks, so the text is ready
 * moments after they stop. One session per dictation:
 * [push] audio as it is recorded, then [finish] sends a manual commit and waits for the final
 * committed transcript. Any problem (auth, network, timeout, empty result) reports null so the
 * caller can fall back to uploading the whole recording.
 *
 * Protocol: wss://api.elevenlabs.io/v1/speech-to-text/realtime, header `xi-api-key`,
 * client messages `{"message_type":"input_audio_chunk","audio_base_64":..,"commit":..,"sample_rate":..}`,
 * server messages `session_started`, `partial_transcript`, `committed_transcript`, or an error
 * type with an `error` field.
 */
class RealtimeSession private constructor(
    private val onPartial: (String) -> Unit,
) {
    private val lock = Any()
    private val pending = ByteArrayOutputStream()
    private val committed = StringBuilder()
    private var ws: WebSocket? = null
    private var closed = false // no more audio accepted
    private var commitSent = false
    private var failure: String? = null
    private var waiter: ((String?, String?) -> Unit)? = null
    private val delivered = AtomicBoolean(false)
    @Volatile var sessionStarted = false
        private set

    /** Adds recorded 16 kHz 16-bit mono PCM; sent in ~200 ms chunks. */
    fun push(pcm: ByteArray, length: Int) = synchronized(lock) {
        if (closed || failure != null) return
        pending.write(pcm, 0, length)
        if (pending.size() >= CHUNK_BYTES) sendPending(commit = false)
    }

    /**
     * Commits the audio and calls [done] once with (text, null) or (null, reason), on an OkHttp
     * thread. Waits at most [timeoutMs] for the final transcript.
     */
    fun finish(timeoutMs: Long = FINAL_TIMEOUT_MS, done: (text: String?, error: String?) -> Unit) {
        val earlier = synchronized(lock) {
            closed = true
            waiter = done
            failure ?: run {
                // A commit must carry audio; 10 ms of silence when everything was already sent.
                if (pending.size() == 0) pending.write(ByteArray(320))
                commitSent = true
                sendPending(commit = true)
                null
            }
        }
        // Outside the lock: the callback may start a whole upload.
        if (earlier != null) return deliver(null, earlier)
        timer.schedule({ deliver(null, "No final transcript within ${timeoutMs} ms") }, timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun cancel() {
        synchronized(lock) { closed = true; waiter = null }
        delivered.set(true)
        ws?.cancel()
    }

    private fun sendPending(commit: Boolean) {
        val bytes = pending.toByteArray()
        pending.reset()
        val msg = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("commit", commit)
            .put("sample_rate", SAMPLE_RATE)
        // OkHttp queues messages sent before the handshake completes.
        if (ws?.send(msg.toString()) != true) fail("Could not send audio to the live transcriber")
    }

    private fun fail(reason: String) {
        synchronized(lock) { if (failure == null) failure = reason }
        deliver(null, reason)
    }

    private fun deliver(text: String?, error: String?) {
        val cb = synchronized(lock) { waiter } ?: return // not finished yet: remembered in [failure]
        if (!delivered.compareAndSet(false, true)) return
        ws?.close(1000, null)
        cb(text, error)
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = try { JSONObject(text) } catch (_: Exception) { return }
            when (val type = obj.optString("message_type")) {
                "session_started" -> sessionStarted = true
                "partial_transcript" -> onPartial(obj.optString("text"))
                "committed_transcript" -> {
                    val segment = obj.optString("text").trim()
                    val result = synchronized(lock) {
                        if (segment.isNotEmpty()) {
                            if (committed.isNotEmpty()) committed.append(' ')
                            committed.append(segment)
                        }
                        if (commitSent) committed.toString() else null
                    }
                    // Our commit was the last one, so this is the end of the dictation.
                    if (result != null) {
                        if (result.isBlank()) deliver(null, "Live transcript was empty") else deliver(result, null)
                    }
                }
                "committed_transcript_with_timestamps", "warning" -> Unit
                else -> if (obj.has("error")) fail("$type: ${obj.optString("error")}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(when (response?.code) {
                401, 403 -> "ElevenLabs key rejected"
                null -> "Live connection failed: ${t.message ?: t.javaClass.simpleName}"
                else -> "Live connection failed: HTTP ${response.code}"
            })
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (code != 1000) fail("Live connection closed ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        /** 200 ms of audio per message. */
        const val CHUNK_BYTES = SAMPLE_RATE * 2 / 5
        const val FINAL_TIMEOUT_MS = 4000L
        const val ELEVENLABS_URL = "https://api.elevenlabs.io/v1/speech-to-text/realtime"
        /** Overridden in tests to point at a mock server. */
        @Volatile internal var DEFAULT_BASE = ELEVENLABS_URL
        /** Over 100 keyterms ElevenLabs bills a 20 s minimum per session; stay under. */
        const val MAX_KEYTERMS = 100

        private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "realtime-timeout").apply { isDaemon = true }
        }

        var client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()

        /** Keyterms must be under 50 characters and at most 5 words. */
        fun keyterms(vocab: List<VocabEntry>): List<String> = vocab.map { it.term.trim() }
            .filter { it.isNotEmpty() && it.length < 50 && it.split(Regex("\\s+")).size <= 5 && '[' !in it && ']' !in it }
            .distinct().take(MAX_KEYTERMS)

        fun url(base: String = DEFAULT_BASE, language: String, keyterms: List<String>): HttpUrl =
            base.toHttpUrl().newBuilder()
                .addQueryParameter("model_id", "scribe_v2_realtime")
                .addQueryParameter("audio_format", "pcm_16000")
                .addQueryParameter("commit_strategy", "manual")
                .apply { if (language.isNotBlank()) addQueryParameter("language_code", language) }
                .apply { keyterms.forEach { addQueryParameter("keyterms", it) } }
                .build()

        /** Opens a session; audio can be pushed right away. */
        fun open(apiKey: String, language: String, keyterms: List<String>, base: String = DEFAULT_BASE,
                 onPartial: (String) -> Unit = {}): RealtimeSession {
            val session = RealtimeSession(onPartial)
            val request = Request.Builder().url(url(base, language, keyterms)).header("xi-api-key", apiKey.trim()).build()
            session.ws = client.newWebSocket(request, session.listener)
            return session
        }

        /** Checks the key by opening a session and closing it once it starts. */
        fun test(apiKey: String, base: String = DEFAULT_BASE, done: (ok: Boolean, message: String) -> Unit) {
            val answered = AtomicBoolean(false)
            fun answer(ok: Boolean, msg: String) { if (answered.compareAndSet(false, true)) done(ok, msg) }
            val request = Request.Builder().url(url(base, "", emptyList())).header("xi-api-key", apiKey.trim()).build()
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val obj = try { JSONObject(text) } catch (_: Exception) { return }
                    if (obj.optString("message_type") == "session_started") answer(true, "Live transcription works")
                    else if (obj.has("error")) answer(false, "${obj.optString("message_type")}: ${obj.optString("error")}")
                    webSocket.close(1000, null)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    answer(false, if (response?.code == 401 || response?.code == 403) "ElevenLabs key rejected"
                        else "Could not connect: ${response?.code?.let { "HTTP $it" } ?: t.message}")
            })
            timer.schedule({ answer(false, "No answer from ElevenLabs") }, 8, TimeUnit.SECONDS)
        }
    }
}
