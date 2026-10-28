package com.kafkasl.phonewhisper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Scrolling bar waveform of recent microphone levels, newest on the right. */
class WaveformView(context: Context) : View(context) {

    private val levels = FloatArray(BARS)
    private var head = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

    /** Adds a level in 0..1 and redraws. Call on the main thread. */
    fun push(level: Float) {
        levels[head] = level.coerceIn(0f, 1f)
        head = (head + 1) % BARS
        invalidate()
    }

    fun clear() { levels.fill(0f); head = 0; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val slot = width.toFloat() / BARS
        val barW = max(1f, slot * 0.6f)
        val minH = barW
        for (i in 0 until BARS) {
            val level = levels[(head + i) % BARS]
            val h = max(minH, level * height)
            val cx = slot * i + slot / 2
            rect.set(cx - barW / 2, (height - h) / 2, cx + barW / 2, (height + h) / 2)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, paint)
        }
    }

    companion object {
        const val BARS = 28
    }
}

/** Microphone level metering, kept free of Android types so it can be unit tested. */
object AudioLevel {
    /** RMS of 16-bit little-endian PCM mapped to 0..1 on a dB scale (-50 dB .. 0 dB). */
    fun levelOf(pcm: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val s = ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)).toShort().toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / samples) / 32768.0
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return min(1f, max(0f, ((db + 50) / 50).toFloat()))
    }
}
