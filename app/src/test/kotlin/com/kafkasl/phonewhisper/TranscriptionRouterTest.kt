package com.kafkasl.phonewhisper

import com.kafkasl.phonewhisper.TranscriptionRouter.Plan
import org.junit.Assert.*
import org.junit.Test

class TranscriptionRouterTest {

    private fun plan(
        mode: TranscriptionMode,
        online: Boolean = true,
        key: Boolean = true,
        local: Boolean = true,
        base: String = Endpoints.DEFAULT_BASE_URL,
    ) = TranscriptionRouter.plan(mode, online, key, local, base)

    @Test fun `fallback mode uses cloud with local fallback when online`() {
        assertEquals(Plan.Cloud(fallbackToLocal = true), plan(TranscriptionMode.CLOUD_WITH_FALLBACK))
    }

    @Test fun `fallback mode goes straight to local when offline`() {
        assertEquals(Plan.Local, plan(TranscriptionMode.CLOUD_WITH_FALLBACK, online = false))
    }

    @Test fun `fallback mode without local model still uses cloud but cannot fall back`() {
        assertEquals(Plan.Cloud(fallbackToLocal = false), plan(TranscriptionMode.CLOUD_WITH_FALLBACK, local = false))
    }

    @Test fun `fallback mode offline without local model is unavailable`() {
        assertTrue(plan(TranscriptionMode.CLOUD_WITH_FALLBACK, online = false, local = false) is Plan.Unavailable)
    }

    @Test fun `fallback mode without key uses local`() {
        assertEquals(Plan.Local, plan(TranscriptionMode.CLOUD_WITH_FALLBACK, key = false))
    }

    @Test fun `self-hosted endpoint works without key`() {
        assertEquals(
            Plan.Cloud(fallbackToLocal = true),
            plan(TranscriptionMode.CLOUD_WITH_FALLBACK, key = false, base = "http://192.168.1.10:8000/v1")
        )
    }

    @Test fun `cloud only never uses local`() {
        assertEquals(Plan.Cloud(fallbackToLocal = false), plan(TranscriptionMode.CLOUD_ONLY))
        assertTrue(plan(TranscriptionMode.CLOUD_ONLY, online = false) is Plan.Unavailable)
    }

    @Test fun `local only ignores network`() {
        assertEquals(Plan.Local, plan(TranscriptionMode.LOCAL_ONLY, online = false, key = false))
        assertTrue(plan(TranscriptionMode.LOCAL_ONLY, local = false) is Plan.Unavailable)
    }
}

class EndpointsTest {
    @Test fun `normalizes trailing slash and pasted paths`() {
        assertEquals("https://api.groq.com/openai/v1", Endpoints.normalizeBase("https://api.groq.com/openai/v1/"))
        assertEquals("https://h/v1", Endpoints.normalizeBase(" https://h/v1/audio/transcriptions "))
        assertEquals("https://h/v1", Endpoints.normalizeBase("https://h/v1/chat/completions"))
    }

    @Test fun `adds scheme and defaults blank`() {
        assertEquals("https://h.example/v1", Endpoints.normalizeBase("h.example/v1"))
        assertEquals("http://10.0.0.2:8000/v1", Endpoints.normalizeBase("http://10.0.0.2:8000/v1"))
        assertEquals(Endpoints.DEFAULT_BASE_URL, Endpoints.normalizeBase("  "))
    }

    @Test fun `builds endpoint urls`() {
        assertEquals("https://h/v1/audio/transcriptions", Endpoints.transcriptions("https://h/v1/"))
        assertEquals("https://h/v1/chat/completions", Endpoints.chatCompletions("https://h/v1"))
    }
}
