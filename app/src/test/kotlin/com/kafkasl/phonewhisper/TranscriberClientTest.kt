package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TranscriberClientTest {

    @Test fun `parses success response`() {
        val r = TranscriberClient.parseResponse("""{"text": "Hello world"}""")
        assertEquals("Hello world", r.text)
        assertNull(r.error)
    }

    @Test fun `parses error response`() {
        val r = TranscriberClient.parseResponse("""{"error":{"message":"Invalid key","type":"auth"}}""")
        assertNull(r.text)
        assertEquals("Invalid key", r.error)
    }

    @Test fun `handles unknown format`() {
        val r = TranscriberClient.parseResponse("""{"foo":"bar"}""")
        assertNull(r.text)
        assertNotNull(r.error)
    }

    @Test fun `handles malformed json`() {
        val r = TranscriberClient.parseResponse("not json")
        assertNull(r.text)
        assertNotNull(r.error)
    }
}

class TranscriberClientHttpTest {

    private fun transcribeBlocking(config: TranscriberClient.Config): TranscriberClient.Result {
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: TranscriberClient.Result? = null
        TranscriberClient.transcribe(ByteArray(64), config) { out = it; latch.countDown() }
        assertTrue("timed out", latch.await(10, java.util.concurrent.TimeUnit.SECONDS))
        return out!!
    }

    @Test fun `sends model language and key to configured endpoint`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"text":"Góðan daginn"}"""))
        server.start()
        try {
            val base = server.url("/v1/").toString()
            val r = transcribeBlocking(TranscriberClient.Config(base, "k123", "whisper-large-v3-turbo", "is"))
            assertEquals("Góðan daginn", r.text)
            val req = server.takeRequest()
            assertEquals("/v1/audio/transcriptions", req.path)
            assertEquals("Bearer k123", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body.contains("whisper-large-v3-turbo"))
            assertTrue(body.contains("name=\"language\""))
        } finally { server.shutdown() }
    }

    @Test fun `omits auth header and language when blank`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"text":"hi"}"""))
        server.start()
        try {
            transcribeBlocking(TranscriberClient.Config(server.url("/v1").toString(), "", "m"))
            val req = server.takeRequest()
            assertNull(req.getHeader("Authorization"))
            assertFalse(req.body.readUtf8().contains("name=\"language\""))
        } finally { server.shutdown() }
    }

    @Test fun `http error is not a network failure`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401)
            .setBody("""{"error":{"message":"Invalid key"}}"""))
        server.start()
        try {
            val r = transcribeBlocking(TranscriberClient.Config(server.url("/v1").toString(), "bad", "m"))
            assertNull(r.text)
            assertFalse(r.networkFailure)
            assertEquals("HTTP 401: Invalid key", r.error)
        } finally { server.shutdown() }
    }

    @Test fun `non-json error page reports status code`() {
        assertEquals("HTTP 502", TranscriberClient.parseHttp(502, "<html>Bad gateway</html>").error)
    }

    @Test fun `unreachable server is a network failure`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        val base = server.url("/v1").toString()
        server.shutdown() // nothing listens on this port any more
        val r = transcribeBlocking(TranscriberClient.Config(base, "k", "m"))
        assertTrue(r.networkFailure)
        assertNull(r.text)
    }
}
