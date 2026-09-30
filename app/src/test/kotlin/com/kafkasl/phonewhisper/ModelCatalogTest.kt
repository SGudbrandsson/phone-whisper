package com.kafkasl.phonewhisper

import com.kafkasl.phonewhisper.RemoteModel.Kind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelCatalogTest {

    private fun kinds(json: String) = ModelCatalog.parse(json).associate { it.id to it.kind }

    @Test fun `openai ids are classified by name`() {
        val k = kinds("""{"object":"list","data":[
            {"id":"whisper-1","object":"model","created":1677532384,"owned_by":"openai-internal"},
            {"id":"gpt-4o-transcribe","object":"model","created":1,"owned_by":"system"},
            {"id":"gpt-4o-mini-tts","object":"model","created":1,"owned_by":"system"},
            {"id":"gpt-4o-mini","object":"model","created":1,"owned_by":"system"},
            {"id":"gpt-4o-audio-preview","object":"model","created":1,"owned_by":"system"},
            {"id":"text-embedding-3-small","object":"model","created":1,"owned_by":"system"},
            {"id":"dall-e-3","object":"model","created":1,"owned_by":"system"},
            {"id":"omni-moderation-latest","object":"model","created":1,"owned_by":"system"},
            {"id":"gpt-4o-realtime-preview","object":"model","created":1,"owned_by":"system"}
        ]}""")
        assertEquals(Kind.STT, k["whisper-1"])
        assertEquals(Kind.STT, k["gpt-4o-transcribe"])
        assertEquals(Kind.OTHER, k["gpt-4o-mini-tts"])
        assertEquals(Kind.CHAT, k["gpt-4o-mini"])
        assertEquals(Kind.AUDIO_CHAT, k["gpt-4o-audio-preview"])
        assertEquals(Kind.OTHER, k["text-embedding-3-small"])
        assertEquals(Kind.OTHER, k["dall-e-3"])
        assertEquals(Kind.OTHER, k["omni-moderation-latest"])
        assertEquals(Kind.OTHER, k["gpt-4o-realtime-preview"])
    }

    @Test fun `groq models`() {
        val k = kinds("""{"object":"list","data":[
            {"id":"whisper-large-v3-turbo","object":"model","created":1,"owned_by":"OpenAI","active":true,"context_window":448},
            {"id":"distil-whisper-large-v3-en","object":"model","created":1,"owned_by":"Hugging Face","active":true},
            {"id":"llama-3.3-70b-versatile","object":"model","created":1,"owned_by":"Meta","active":true,"context_window":131072},
            {"id":"playai-tts","object":"model","created":1,"owned_by":"PlayAI","active":true},
            {"id":"meta-llama/llama-guard-4-12b","object":"model","created":1,"owned_by":"Meta","active":true}
        ]}""")
        assertEquals(Kind.STT, k["whisper-large-v3-turbo"])
        assertEquals(Kind.STT, k["distil-whisper-large-v3-en"])
        assertEquals(Kind.CHAT, k["llama-3.3-70b-versatile"])
        assertEquals(Kind.OTHER, k["playai-tts"])
        assertEquals(Kind.OTHER, k["meta-llama/llama-guard-4-12b"])
    }

    @Test fun `capabilities object decides before the name`() {
        val k = kinds("""{"object":"list","data":[
            {"id":"voxtral-mini-2507","capabilities":{"completion_chat":true,"audio":true,"audio_transcription":true}},
            {"id":"mistral-small-latest","capabilities":{"completion_chat":true,"vision":true,"audio":false}},
            {"id":"custom-listener","capabilities":{"completion_chat":true,"audio":true}},
            {"id":"voxtral-small-latest","capabilities":{"completion_chat":true,"audio":true}},
            {"id":"mistral-embed","capabilities":{"completion_chat":false}}
        ]}""")
        assertEquals(Kind.STT, k["voxtral-mini-2507"])
        assertEquals(Kind.CHAT, k["mistral-small-latest"])
        assertEquals(Kind.AUDIO_CHAT, k["custom-listener"])
        assertEquals(Kind.STT, k["voxtral-small-latest"])
        assertEquals(Kind.OTHER, k["mistral-embed"]) // no flags on, falls back to name
    }

    @Test fun `openrouter modalities`() {
        val k = kinds("""{"data":[
            {"id":"openai/gpt-4o-audio-preview","name":"GPT-4o Audio","architecture":{"modality":"text+audio->text","input_modalities":["text","audio"],"output_modalities":["text"]}},
            {"id":"anthropic/claude-sonnet-4","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},
            {"id":"google/gemini-2.5-flash-image","architecture":{"input_modalities":["text","image"],"output_modalities":["image","text"]}},
            {"id":"some/image-only","architecture":{"input_modalities":["text"],"output_modalities":["image"]}}
        ]}""")
        assertEquals(Kind.AUDIO_CHAT, k["openai/gpt-4o-audio-preview"])
        assertEquals(Kind.CHAT, k["anthropic/claude-sonnet-4"])
        assertEquals(Kind.CHAT, k["google/gemini-2.5-flash-image"])
        assertEquals(Kind.OTHER, k["some/image-only"])
    }

    @Test fun `gemini openai-compatible ids`() {
        val k = kinds("""{"object":"list","data":[
            {"id":"models/gemini-2.5-flash","object":"model","owned_by":"google"},
            {"id":"models/text-embedding-004","object":"model","owned_by":"google"},
            {"id":"models/gemini-2.5-flash-preview-tts","object":"model","owned_by":"google"},
            {"id":"models/imagen-3.0-generate-002","object":"model","owned_by":"google"}
        ]}""")
        assertEquals(Kind.AUDIO_CHAT, k["models/gemini-2.5-flash"])
        assertEquals(Kind.OTHER, k["models/text-embedding-004"])
        assertEquals(Kind.OTHER, k["models/gemini-2.5-flash-preview-tts"])
        assertEquals(Kind.OTHER, k["models/imagen-3.0-generate-002"])
    }

    @Test fun `self-hosted task field`() {
        val k = kinds("""{"object":"list","data":[
            {"id":"Systran/faster-distil-whisper-small.en","object":"model","owned_by":"Systran","task":"automatic-speech-recognition"},
            {"id":"nvidia/something","object":"model","task":"automatic-speech-recognition"},
            {"id":"hexgrad/Kokoro-82M","object":"model","task":"text-to-speech"}
        ]}""")
        assertEquals(Kind.STT, k["nvidia/something"])
        assertEquals(Kind.OTHER, k["hexgrad/Kokoro-82M"])
    }

    @Test fun `filters for the two pickers`() {
        val models = listOf(
            RemoteModel("whisper-1", Kind.STT), RemoteModel("gpt-4o-mini", Kind.CHAT),
            RemoteModel("gpt-4o-audio-preview", Kind.AUDIO_CHAT), RemoteModel("tts-1", Kind.OTHER),
        )
        assertEquals(listOf("whisper-1"), ModelCatalog.forTranscription(models, false).map { it.id })
        assertEquals("STT first when showing all", "whisper-1", ModelCatalog.forTranscription(models, true).first().id)
        assertEquals(4, ModelCatalog.forTranscription(models, true).size)
        assertEquals(listOf("gpt-4o-audio-preview", "gpt-4o-mini"), ModelCatalog.forCleanup(models, false).map { it.id })
    }

    @Test fun `search matches all terms in any order`() {
        val models = listOf(RemoteModel("whisper-large-v3-turbo", Kind.STT), RemoteModel("whisper-large-v3", Kind.STT))
        assertEquals(listOf("whisper-large-v3-turbo"), ModelCatalog.search(models, "turbo large").map { it.id })
        assertEquals(2, ModelCatalog.search(models, "  ").size)
    }

    @Test fun `cache round trips`() {
        val models = listOf(RemoteModel("a", Kind.STT, "x"), RemoteModel("b", Kind.CHAT))
        assertEquals(models, ModelCatalog.fromJson(ModelCatalog.toJson(models)))
        assertTrue(ModelCatalog.fromJson("garbage").isEmpty())
    }

    @Test fun `models endpoint url`() {
        assertEquals("https://api.groq.com/openai/v1/models", Endpoints.models("https://api.groq.com/openai/v1/"))
        assertEquals("https://h/v1", Endpoints.normalizeBase("https://h/v1/models"))
    }

    @Test fun `litellm model info modes`() {
        val info = ModelCatalog.parseLiteLlmInfo("""{"data":[
            {"model_name":"eleven","litellm_params":{"model":"elevenlabs/scribe_v1"},"model_info":{"mode":"audio_transcription"}},
            {"model_name":"fast","litellm_params":{"model":"groq/llama-3.1-8b-instant"},"model_info":{"mode":"chat","supports_audio_input":false}},
            {"model_name":"listen","litellm_params":{"model":"gemini/gemini-2.5-flash"},"model_info":{"mode":"chat","supports_audio_input":true}},
            {"model_name":"voice","litellm_params":{"model":"elevenlabs/eleven_multilingual_v2"},"model_info":{"mode":"audio_speech"}},
            {"model_name":"nomode","litellm_params":{"model":"openai/whisper-1"},"model_info":{}},
            {"model_name":"","litellm_params":{"model":"x"}}
        ]}""")
        assertEquals(mapOf("eleven" to Kind.STT, "fast" to Kind.CHAT, "listen" to Kind.AUDIO_CHAT,
            "voice" to Kind.OTHER, "nomode" to Kind.STT), info)
        assertTrue(ModelCatalog.parseLiteLlmInfo("not json").isEmpty())
    }

    @Test fun `litellm aliases get kinds from model info`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"eleven","object":"model","owned_by":"openai"},{"id":"fast","object":"model"}]}"""))
        server.enqueue(MockResponse().setBody("""{"data":[{"model_name":"eleven","litellm_params":{"model":"elevenlabs/scribe_v1"},"model_info":{"mode":"audio_transcription"}}]}"""))
        server.start()
        try {
            val r = ModelCatalog.fetchBlocking(server.url("/v1").toString(), "sk-litellm")
            assertEquals(mapOf("eleven" to Kind.STT, "fast" to Kind.CHAT), r.models!!.associate { it.id to it.kind })
            server.takeRequest()
            val info = server.takeRequest()
            assertEquals("/v1/model/info", info.path)
            assertEquals("Bearer sk-litellm", info.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun `missing model info is ignored`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"whisper-1"}]}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.start()
        try {
            val r = ModelCatalog.fetchBlocking(server.url("/v1").toString(), "")
            assertEquals(Kind.STT, r.models!!.single().kind)
        } finally { server.shutdown() }
    }

    @Test fun `http errors are readable`() {
        assertEquals("API key rejected: Incorrect API key provided",
            ModelCatalog.parseHttp(401, """{"error":{"message":"Incorrect API key provided"}}""").error)
        assertEquals("No /models at this address — check the endpoint URL", ModelCatalog.parseHttp(404, "<html>").error)
        assertEquals("Unexpected response from /models", ModelCatalog.parseHttp(200, "nope").error)
    }

    @Test fun `fetch sends the key and parses the list`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"whisper-1"},{"id":"gpt-4o-mini"}]}"""))
        server.enqueue(MockResponse().setResponseCode(404)) // not a LiteLLM proxy
        server.start()
        try {
            val latch = CountDownLatch(1)
            var result: ModelCatalog.FetchResult? = null
            ModelCatalog.fetch(server.url("/v1").toString(), "sk-test") { result = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            val req = server.takeRequest()
            assertEquals("/v1/models", req.path)
            assertEquals("Bearer sk-test", req.getHeader("Authorization"))
            assertEquals(2, result!!.models!!.size)
            assertEquals("Connected: 2 models (1 speech-to-text, 1 chat)", ModelCatalog.describe(result!!))
            assertEquals("Connected: 2 models (1 speech-to-text, 1 chat). \"x\" isn't listed.", ModelCatalog.describe(result!!, "x"))
        } finally { server.shutdown() }
    }
}
