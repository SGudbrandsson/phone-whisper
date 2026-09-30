package com.kafkasl.phonewhisper

import android.Manifest
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

/** Renders the overlay bubble and recording pill with real window views. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class OverlayScreenshotTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var service: WhisperAccessibilityService

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(data: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                Thread.sleep(20); return minOf(size, 640)
            }
        })
        AppSettings(app).apply { showOnlyWhenTyping = false; baseUrl = "http://127.0.0.1:9/v1"; mode = TranscriptionMode.CLOUD_ONLY }
        service = Robolectric.setupService(WhisperAccessibilityService::class.java)
        service.onServiceConnected()
        idle()
    }

    @After fun tearDown() { service.onDestroy(); idle(); ShadowAudioRecord.clearSource() }

    /** Draws the overlay windows side by side, as on screen, onto a light and a dark backdrop. */
    private fun save(name: String, vararg views: View) {
        val gap = 24; val pad = 32
        val w = views.sumOf { it.width } + gap * (views.size - 1) + pad * 2
        val h = views.maxOf { it.height } + pad * 2
        val bmp = Bitmap.createBitmap(w, h * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        listOf(0xFFF1F3F4.toInt(), 0xFF202124.toInt()).forEachIndexed { i, bg ->
            c.save(); c.translate(0f, (i * h).toFloat())
            c.clipRect(0, 0, w, h); c.drawColor(bg)
            var x = pad
            views.forEach { v ->
                c.save(); c.translate(x.toFloat(), (pad + (h - 2 * pad - v.height) / 2).toFloat()); v.draw(c); c.restore()
                x += v.width + gap
            }
            c.restore()
        }
        File("build/outputs/roborazzi").mkdirs()
        File("build/outputs/roborazzi/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    private fun tap(v: View) {
        val now = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 10f, 10f, 0))
        idle()
    }

    @Test fun bubble_idle() {
        save("overlay_bubble_idle", service.overlayView!!)
    }

    @Test fun pill_recording() {
        tap(service.overlayView!!)
        val wave = service.waveformForTest!!
        listOf(0.1f, 0.3f, 0.6f, 0.8f, 0.5f, 0.9f, 0.4f, 0.7f, 0.3f, 0.2f, 0.5f, 0.8f, 0.6f, 0.4f, 0.2f, 0.1f, 0.3f, 0.6f)
            .forEach { wave.push(it) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        save("overlay_recording", service.pillView!!, service.overlayView!!)
    }

    @Test fun pill_transcribing() {
        tap(service.overlayView!!)
        Thread.sleep(200); idle()
        tap(service.overlayView!!)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        save("overlay_transcribing", service.pillView!!, service.overlayView!!)
    }
}
