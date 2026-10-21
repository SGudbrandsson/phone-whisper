package com.kafkasl.phonewhisper

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Talks to a mock ElevenLabs realtime endpoint and a mock upload endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RealtimeSessionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    /** How the fake ElevenLabs behaves for the next session. */
    private enum class Mode { OK, AUTH_ERROR, SILENT }
    @Volatile private var mode = Mode.OK
    private val audioBytes = AtomicInteger()
    private val messages = CopyOnWriteArrayList<JSONObject>()
    private val wsRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val uploads = LinkedBlockingQueue<RecordedRequest>()
    private val uploadReplies = LinkedBlockingQueue<MockResponse>()

    @Before fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/v1/speech-to-text/realtime")) {
                        wsRequests += request
                        return MockResponse().withWebSocketUpgrade(fakeElevenLabs())
                    }
                    uploads += request
                    return uploadReplies.poll(5, TimeUnit.SECONDS) ?: MockResponse().setResponseCode(503)
                }
            }
            start()
        }
        RealtimeSession.DEFAULT_BASE = server.url("/v1/speech-to-text/realtime").toString()
    }

    @After fun tearDown() {
        RealtimeSession.DEFAULT_BASE = RealtimeSession.ELEVENLABS_URL
        server.shutdown()
    }

    private fun fakeElevenLabs() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            when (mode) {
                Mode.AUTH_ERROR -> webSocket.send("""{"message_type":"auth_error","error":"Invalid API key"}""")
                else -> webSocket.send("""{"message_type":"session_started","session_id":"s1","config":{}}""")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = JSONObject(text)
            messages += msg
            audioBytes.addAndGet(Base64.decode(msg.getString("audio_base_64"), Base64.DEFAULT).size)
            if (mode == Mode.OK) {
                webSocket.send("""{"message_type":"partial_transcript","text":"hello"}""")
                if (msg.getBoolean("commit")) webSocket.send("""{"message_type":"committed_transcript","text":"hello world"}""")
            }
        }
    }

    private fun finish(s: RealtimeSession, timeoutMs: Long = RealtimeSession.FINAL_TIMEOUT_MS): Pair<String?, String?> {
        val latch = CountDownLatch(1)
        var out: Pair<String?, String?> = null to null
        s.finish(timeoutMs) { t, e -> out = t to e; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return out
    }

    @Test fun `streams chunks, commits on finish and returns the final text`() {
        val partials = CopyOnWriteArrayList<String>()
        val s = RealtimeSession.open("xi-key", "is", listOf("AIHub", "Þórunn"), noVerbatim = true, onPartial = { partials += it })
        val second = ByteArray(32000) // 1 s of audio, pushed like the recorder does
        repeat(10) { s.push(second.copyOfRange(it * 3200, (it + 1) * 3200), 3200) }
        val (text, error) = finish(s)

        assertEquals("hello world", text)
        assertNull(error)
        // All audio, plus 10 ms of silence when the commit has nothing else to carry.
        assertTrue("all audio is sent: ${audioBytes.get()}", audioBytes.get() in 32000..32320)
        assertTrue("sent in ~200 ms chunks", messages.size in 5..6)
        assertTrue(messages.dropLast(1).none { it.getBoolean("commit") })
        assertTrue(messages.last().getBoolean("commit"))
        assertEquals(16000, messages.first().getInt("sample_rate"))
        assertEquals("input_audio_chunk", messages.first().getString("message_type"))
        assertTrue(partials.isNotEmpty())

        val req = wsRequests.single()
        assertEquals("xi-key", req.getHeader("xi-api-key"))
        val url = req.requestUrl!!
        assertEquals("scribe_v2_realtime", url.queryParameter("model_id"))
        assertEquals("pcm_16000", url.queryParameter("audio_format"))
        assertEquals("manual", url.queryParameter("commit_strategy"))
        assertEquals("is", url.queryParameter("language_code"))
        assertEquals("true", url.queryParameter("no_verbatim"))
        assertEquals(listOf("AIHub", "Þórunn"), url.queryParameterValues("keyterms"))
    }

    @Test fun `finish with nothing pending still commits`() {
        val s = RealtimeSession.open("k", "", emptyList())
        s.push(ByteArray(6400), 6400) // exactly one chunk, sent at once
        assertEquals("hello world", finish(s).first)
        assertTrue(messages.last().getBoolean("commit"))
    }

    @Test fun `auth error reports failure`() {
        mode = Mode.AUTH_ERROR
        val s = RealtimeSession.open("bad", "", emptyList())
        Thread.sleep(300)
        val (text, error) = finish(s)
        assertNull(text)
        assertTrue(error!!.contains("auth_error"))
    }

    @Test fun `no final transcript times out`() {
        mode = Mode.SILENT
        val s = RealtimeSession.open("k", "", emptyList())
        s.push(ByteArray(3200), 3200)
        val (text, error) = finish(s, timeoutMs = 300)
        assertNull(text)
        assertTrue(error!!.contains("No final transcript"))
    }

    @Test fun `keyterms respect ElevenLabs limits`() {
        val terms = RealtimeSession.keyterms(listOf(
            VocabEntry("AIHub"), VocabEntry("x".repeat(60)), VocabEntry("one two three four five six"),
            VocabEntry("bad[term]"), VocabEntry("AIHub"),
        ) + (1..200).map { VocabEntry("w$it") })
        assertEquals("AIHub", terms.first())
        assertEquals(RealtimeSession.MAX_KEYTERMS, terms.size)
        assertTrue(terms.none { it.length >= 50 || '[' in it })
    }

    // --- Engine integration ---

    private fun online() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,
            ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) })
    }

    private fun engineWith(liveKey: Boolean): TranscriptionEngine {
        online()
        AppSettings(app).apply {
            baseUrl = server.url("/v1").toString(); mode = TranscriptionMode.CLOUD_ONLY
            usePostProcessing = false; liveTranscription = true; compressAudio = false
            vocabulary = emptyList()
        }
        return TranscriptionEngine(app).also { e ->
            if (liveKey) e.liveKeyForTest = "xi-key"
        }
    }

    private fun run(engine: TranscriptionEngine, live: RealtimeSession?): TranscriptionEngine.Outcome {
        val latch = CountDownLatch(1)
        var out: TranscriptionEngine.Outcome? = null
        engine.run(ByteArray(32000), live) { out = it; latch.countDown() }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        return out!!
    }

    @Test fun `engine uses the live transcript and uploads nothing`() {
        val engine = engineWith(liveKey = true)
        val live = engine.startLive()!!
        live.push(ByteArray(32000), 32000)
        val out = run(engine, live) as TranscriptionEngine.Outcome.Success
        assertEquals("hello world", out.text)
        assertEquals(TranscriptionEngine.Source.CLOUD_LIVE, out.source)
        assertTrue(uploads.isEmpty())
    }

    @Test fun `engine falls back to uploading when live fails`() {
        mode = Mode.AUTH_ERROR
        uploadReplies += MockResponse().setBody("""{"text":"from upload"}""")
        val engine = engineWith(liveKey = true)
        val live = engine.startLive()!!
        live.push(ByteArray(32000), 32000)
        val out = run(engine, live) as TranscriptionEngine.Outcome.Success
        assertEquals("from upload", out.text)
        assertEquals(TranscriptionEngine.Source.CLOUD, out.source)
        assertEquals(1, uploads.size)
    }

    @Test fun `no live session without an ElevenLabs key`() {
        assertNull(engineWith(liveKey = false).startLive())
    }

    @Test fun `unreadable compressed audio is retried as wav and remembered`() {
        uploadReplies += MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Invalid file format: could not decode audio"}}""")
        uploadReplies += MockResponse().setBody("""{"text":"ok"}""")
        uploadReplies += MockResponse().setBody("""{"text":"ok"}""")
        val engine = engineWith(liveKey = false)
        val settings = AppSettings(app).apply { compressAudio = true; compressionRejectedHere = false }
        engine.encoder = { EncodedAudio(ByteArray(500) { 7 }, "audio.ogg", "audio/ogg") }

        assertEquals("ok", (run(engine, null) as TranscriptionEngine.Outcome.Success).text)
        assertTrue(uploads.poll()!!.body.readUtf8().contains("filename=\"audio.ogg\""))
        assertTrue(uploads.poll()!!.body.readUtf8().contains("filename=\"audio.wav\""))
        assertTrue(settings.compressionRejectedHere)

        run(engine, null)
        assertTrue("WAV from now on", uploads.poll()!!.body.readUtf8().contains("filename=\"audio.wav\""))
        settings.compressionRejectedHere = false
    }
}
