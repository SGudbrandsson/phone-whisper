package com.kafkasl.phonewhisper

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryStoreTest {
    private lateinit var store: HistoryStore
    private val pcm = ByteArray(64000) { (it % 100).toByte() } // 2 s

    @Before fun setUp() {
        store = HistoryStore.get(ApplicationProvider.getApplicationContext())
        store.clearAll()
    }

    @Test fun `success is stored with raw text only when cleanup changed it`() {
        store.addSuccess("Hello.", "hello", "Cloud", "com.example", 1500)
        store.addSuccess("Same", "Same", "Local", null, 800)
        val all = store.recent()
        assertEquals(2, all.size)
        val cleaned = all.first { it.text == "Hello." }
        assertEquals("hello", cleaned.rawText)
        assertEquals("com.example", cleaned.appPackage)
        assertNull(all.first { it.text == "Same" }.rawText)
        assertTrue(all.none { it.canRetry })
    }

    @Test fun `failure keeps audio that round trips and can be retried`() {
        val id = store.addFailure("HTTP 500", pcm, "com.example")
        val e = store.get(id)!!
        assertEquals(HistoryEntry.Status.FAILED, e.status)
        assertTrue(e.canRetry)
        assertEquals(2000L, e.durationMs)
        assertArrayEquals(pcm, WavReader.pcm(File(e.audioPath!!).readBytes()))
    }

    @Test fun `retry success clears error and deletes audio`() {
        val id = store.addFailure("offline", pcm, null)
        val audio = File(store.get(id)!!.audioPath!!)
        store.markRetried(id, "Fixed", "fixed", "Cloud")
        val e = store.get(id)!!
        assertEquals(HistoryEntry.Status.OK, e.status)
        assertEquals("Fixed", e.text)
        assertNull(e.error)
        assertNull(e.audioPath)
        assertFalse(audio.exists())
    }

    @Test fun `prune removes old entries and their audio`() {
        val id = store.addFailure("old", pcm, null)
        val audio = File(store.get(id)!!.audioPath!!)
        store.addSuccess("recent", "recent", "Cloud", null, 100)
        val eightDaysLater = System.currentTimeMillis() + 8 * HistoryPolicy.DAY_MS
        assertEquals(0, store.prune(0, eightDaysLater)) // "never" keeps all
        assertEquals(0, store.prune(30, eightDaysLater))
        assertEquals(2, store.prune(7, eightDaysLater))
        assertTrue(store.recent().isEmpty())
        assertFalse(audio.exists())
    }

    @Test fun `delete and clear all`() {
        val a = store.addFailure("x", pcm, null)
        store.addSuccess("y", "y", "Cloud", null, 1)
        val audio = File(store.get(a)!!.audioPath!!)
        store.delete(a)
        assertNull(store.get(a))
        assertFalse(audio.exists())
        store.clearAll()
        assertTrue(store.recent().isEmpty())
    }
}
