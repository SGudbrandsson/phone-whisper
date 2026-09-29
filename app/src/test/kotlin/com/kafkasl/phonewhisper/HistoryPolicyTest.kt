package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class HistoryPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun `cutoff subtracts retention days`() {
        assertEquals(now - 7 * HistoryPolicy.DAY_MS, HistoryPolicy.cutoff(now, 7))
        assertEquals(now - HistoryPolicy.DAY_MS, HistoryPolicy.cutoff(now, 1))
    }

    @Test fun `zero days keeps everything`() = assertNull(HistoryPolicy.cutoff(now, 0))

    @Test fun `default is seven days and offered`() {
        assertEquals(7, HistoryPolicy.DEFAULT_RETENTION_DAYS)
        assertTrue(7 in HistoryPolicy.RETENTION_CHOICES)
    }

    @Test fun `duration from pcm size`() {
        assertEquals(1000L, HistoryPolicy.durationMs(32000)) // 1 s of 16 kHz 16-bit mono
        assertEquals(0L, HistoryPolicy.durationMs(0))
    }

    @Test fun `relative time labels`() {
        assertEquals("just now", HistoryActions.relativeTime(now - 10_000, now))
        assertEquals("5 min ago", HistoryActions.relativeTime(now - 5 * 60_000, now))
        assertEquals("3 h ago", HistoryActions.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2 d ago", HistoryActions.relativeTime(now - 2 * HistoryPolicy.DAY_MS, now))
    }
}

class WavReaderTest {
    @Test fun `round trips WavWriter output`() {
        val pcm = ByteArray(1000) { (it % 251).toByte() }
        assertArrayEquals(pcm, WavReader.pcm(WavWriter.encode(pcm)))
    }

    @Test fun `skips extra chunks before data`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = WavWriter.encode(pcm)
        val list = "LIST".toByteArray() + byteArrayOf(2, 0, 0, 0, 9, 9)
        val withList = wav.copyOfRange(0, 36) + list + wav.copyOfRange(36, wav.size)
        assertArrayEquals(pcm, WavReader.pcm(withList))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects non wav`() { WavReader.pcm("hello world!".toByteArray()) }
}
