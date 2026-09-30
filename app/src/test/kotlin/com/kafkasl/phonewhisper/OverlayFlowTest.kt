package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import kotlin.math.sin

/**
 * Drives the overlay the way a user does: tap to record, tap to stop, cancel, drag,
 * long-press for history. Robolectric runs the real WindowManagerGlobal, so calling
 * updateViewLayout/removeView on a view that isn't attached throws here just as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var service: WhisperAccessibilityService
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        // The busy spinner and recording pulse animate forever; with a zero frame delay
        // Robolectric would run their frames endlessly without the clock moving.
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // A quiet sine wave; the short sleep keeps the recording thread from spinning.
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            var t = 0
            override fun readInByteArray(data: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                val n = minOf(size, 640) // 20 ms per read, like a real microphone
                Thread.sleep(20)
                var i = offset
                while (i + 1 < offset + n) {
                    val s = (sin(t++ / 8.0) * 3000).toInt()
                    data[i] = s.toByte(); data[i + 1] = (s shr 8).toByte()
                    i += 2
                }
                return n
            }
        })
        // Robolectric starts with no network capabilities, which the app treats as offline.
        val cm = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,
            ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) })
        server = MockWebServer().also { it.start() }
        AppSettings(app).apply {
            baseUrl = server.url("/v1").toString() // self-hosted style: no key needed
            mode = TranscriptionMode.CLOUD_ONLY
            showOnlyWhenTyping = false
            historyEnabled = true
        }
        HistoryStore.get(app).clearAll()
        service = Robolectric.setupService(WhisperAccessibilityService::class.java)
        service.onServiceConnected()
        idle()
    }

    @After fun tearDown() {
        service.onDestroy()
        idle()
        ShadowAudioRecord.clearSource()
        server.shutdown()
    }

    @Test fun `tap records, tap stops, text is transcribed and saved`() {
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        tapBubble()
        assertEquals(WhisperAccessibilityService.State.RECORDING, service.state)
        assertTrue("pill shown while recording", service.pillView!!.isAttachedToWindow)

        recordFor(300)
        tapBubble()
        waitUntilIdle()

        assertFalse("pill hidden after transcription", service.pillView!!.isAttachedToWindow)
        assertEquals("hello world", HistoryStore.get(app).recent().single().text)
        assertEquals(1, server.requestCount)
    }

    @Test fun `failed transcription returns to idle and keeps audio for retry`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        tapBubble()
        recordFor(1200) // long enough to be worth keeping
        tapBubble()
        waitUntilIdle()

        assertFalse(service.pillView!!.isAttachedToWindow)
        val entry = HistoryStore.get(app).recent().single()
        assertEquals(HistoryEntry.Status.FAILED, entry.status)
        assertTrue(entry.canRetry)
    }

    @Test fun `cancel while recording discards the dictation`() {
        tapBubble()
        recordFor(200)
        cancelButton().performClick()
        idle()

        assertEquals(WhisperAccessibilityService.State.IDLE, service.state)
        assertFalse(service.pillView!!.isAttachedToWindow)
        assertEquals("Cancelled", service.feedbackView!!.text.toString())
        assertEquals(0, server.requestCount)
        assertTrue(HistoryStore.get(app).recent().isEmpty())
    }

    @Test fun `cancel while transcribing ignores the late result`() {
        server.enqueue(MockResponse().setBody("""{"text":"too late"}""").setHeadersDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS))
        tapBubble()
        recordFor(200)
        tapBubble()
        assertEquals(WhisperAccessibilityService.State.TRANSCRIBING, service.state)
        assertTrue(service.pillView!!.isAttachedToWindow)
        cancelButton().performClick()
        idle()
        assertEquals(WhisperAccessibilityService.State.IDLE, service.state)
        assertFalse(service.pillView!!.isAttachedToWindow)

        Thread.sleep(800)
        idle()
        assertTrue(HistoryStore.get(app).recent().isEmpty())
    }

    @Test fun `record twice in a row`() {
        repeat(2) {
            tapBubble()
            recordFor(100)
            cancelButton().performClick()
            idle()
        }
        server.enqueue(MockResponse().setBody("""{"text":"third"}"""))
        tapBubble()
        recordFor(200)
        tapBubble()
        waitUntilIdle()
        assertEquals("third", HistoryStore.get(app).recent().single().text)
    }

    @Test fun `dragging the bubble while recording moves the pill`() {
        tapBubble()
        val before = pillX()
        drag(dx = -400f, dy = 200f)
        assertEquals(WhisperAccessibilityService.State.RECORDING, service.state)
        assertTrue(service.pillView!!.isAttachedToWindow)
        assertNotEquals(before, pillX())
        cancelButton().performClick()
        idle()
    }

    @Test fun `dragging while idle does not touch the hidden pill`() {
        drag(dx = -400f, dy = 100f)
        assertFalse(service.pillView!!.isAttachedToWindow)
    }

    @Test fun `long press opens quick history and close dismisses it`() {
        HistoryStore.get(app).addSuccess("earlier", "earlier", "Cloud", null, 1000)
        val bubble = service.overlayView!!
        bubble.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 0f, 0f))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        waitFor { service.sheetView != null }
        assertTrue(service.sheetView!!.isAttachedToWindow)
        bubble.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 0f, 0f))
        assertEquals("long press must not start recording", WhisperAccessibilityService.State.IDLE, service.state)

        val sheet = service.sheetView!!
        sheet.dispatchTouchEvent(event(MotionEvent.ACTION_OUTSIDE, -10f, -10f))
        idle()
        assertNull(service.sheetView)
        assertFalse(sheet.isAttachedToWindow)
    }

    @Test fun `bubble hides and shows with typing state`() {
        AppSettings(app).showOnlyWhenTyping = true
        service.refreshVisibility()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(View.GONE, service.overlayView!!.visibility)
        AppSettings(app).showOnlyWhenTyping = false
        service.refreshVisibility()
        idle()
        assertEquals(View.VISIBLE, service.overlayView!!.visibility)
    }

    @Test fun `no microphone permission shows a toast instead of recording`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        tapBubble()
        assertEquals(WhisperAccessibilityService.State.IDLE, service.state)
        assertFalse(service.pillView!!.isAttachedToWindow)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("permission"))
    }

    @Test fun `destroying the service mid-recording removes every window`() {
        tapBubble()
        val bubble = service.overlayView!!
        val pill = service.pillView!!
        val feedback = service.feedbackView!!
        service.onDestroy()
        idle()
        assertFalse(bubble.isAttachedToWindow)
        assertFalse(pill.isAttachedToWindow)
        assertFalse(feedback.isAttachedToWindow)
        service = Robolectric.setupService(WhisperAccessibilityService::class.java) // for tearDown
        service.onServiceConnected()
        idle()
    }

    // --- helpers ---

    /** Runs pending main-thread work and a few frames, so windows get attached and laid out. */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun event(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private fun tapBubble() {
        val v = service.overlayView!!
        v.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f))
        v.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 10f, 10f))
        idle()
    }

    private fun drag(dx: Float, dy: Float) {
        val v = service.overlayView!!
        v.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 500f, 500f))
        v.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 500f + dx / 2, 500f + dy / 2))
        v.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 500f + dx, 500f + dy))
        v.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 500f + dx, 500f + dy))
        idle()
    }

    private fun pillX() = (service.pillView!!.layoutParams as android.view.WindowManager.LayoutParams).x

    private fun cancelButton(): View = service.pillView!!.getChildAt(0)

    private fun recordFor(ms: Long) {
        Thread.sleep(ms)
        idle()
    }

    private fun waitUntilIdle() = waitFor { service.state == WhisperAccessibilityService.State.IDLE }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (cond()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }
}
