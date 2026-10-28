package com.kafkasl.phonewhisper

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VocabularyTest {
    private val vocab = listOf(
        VocabEntry("AIHub", listOf("ai hub", "eye hub")),
        VocabEntry("Siggi"),
        VocabEntry("Þórunn", listOf("thorunn")),
    )

    @Test fun `variants are replaced and spelling is enforced`() {
        assertEquals("Ask AIHub and Siggi, then AIHub again.",
            Vocabulary.apply("Ask eye hub and siggi, then aihub again.", vocab))
    }

    @Test fun `only whole words change`() {
        assertEquals("siggiville and the chair hub", Vocabulary.apply("siggiville and the chair hub", vocab))
    }

    @Test fun `icelandic letters and case work`() {
        assertEquals("Hringdu í Þórunn", Vocabulary.apply("Hringdu í thorunn", vocab))
        assertEquals("Þórunn", Vocabulary.apply("þórunn", vocab))
    }

    @Test fun `multi word variants allow any spacing`() {
        assertEquals("AIHub is up", Vocabulary.apply("ai   hub is up", vocab))
    }

    @Test fun `hint prompt is a capped comma list`() {
        assertEquals("AIHub, Siggi, Þórunn", Vocabulary.hintPrompt(vocab))
        assertNull(Vocabulary.hintPrompt(emptyList()))
        val many = (1..500).map { VocabEntry("word$it") }
        assertTrue(Vocabulary.hintPrompt(many)!!.length <= Vocabulary.MAX_HINT_CHARS)
    }

    @Test fun `json round trip drops blanks`() {
        assertEquals(vocab, Vocabulary.fromJson(Vocabulary.toJson(vocab)))
        assertEquals(listOf(VocabEntry("x")), Vocabulary.fromJson("""[{"term":" "},{"term":"x","heardAs":["", "x"]}]"""))
        assertTrue(Vocabulary.fromJson("junk").isEmpty())
    }

    @Test fun `variants input parsing`() {
        assertEquals(listOf("ai hub", "eye hub"), Vocabulary.parseVariants(" ai hub, eye hub ;ai hub,"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VocabularyEngineTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun online() {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,
            ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) })
    }

    private fun transcribe(engine: TranscriptionEngine): TranscriptionEngine.Outcome {
        val latch = CountDownLatch(1)
        var out: TranscriptionEngine.Outcome? = null
        engine.run(ByteArray(32000)) { out = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return out!!
    }

    @Test fun `hints are sent, a refusal is retried without them and remembered`() {
        online()
        val server = MockWebServer()
        // What LiteLLM returns when a provider lacks a parameter.
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"message":"litellm.UnsupportedParamsError: Setting prompt is not supported by elevenlabs"}}"""))
        server.enqueue(MockResponse().setBody("""{"text":"ask eye hub"}"""))
        server.enqueue(MockResponse().setBody("""{"text":"ask eye hub"}"""))
        server.start()
        try {
            val settings = AppSettings(app).apply {
                baseUrl = server.url("/v1").toString(); mode = TranscriptionMode.CLOUD_ONLY
                sttModel = "elevenlabs/scribe_v2"; usePostProcessing = false
                vocabulary = listOf(VocabEntry("AIHub", listOf("eye hub")))
                sendVocabularyHints = true; hintsRejectedHere = false
            }
            val engine = TranscriptionEngine(app)
            val first = transcribe(engine) as TranscriptionEngine.Outcome.Success
            assertEquals("ask AIHub", first.text)
            assertEquals("ask eye hub", first.rawText)
            assertTrue(server.takeRequest().body.readUtf8().contains("AIHub"))
            assertFalse("retry has no prompt", server.takeRequest().body.readUtf8().contains("name=\"prompt\""))
            assertTrue(settings.hintsRejectedHere)

            transcribe(engine)
            assertFalse("not sent again to this model", server.takeRequest().body.readUtf8().contains("name=\"prompt\""))
        } finally {
            server.shutdown()
            AppSettings(app).apply { hintsRejectedHere = false; vocabulary = emptyList() }
        }
    }
}
