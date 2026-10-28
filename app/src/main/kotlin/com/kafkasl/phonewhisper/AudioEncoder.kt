package com.kafkasl.phonewhisper

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** Audio ready to upload, with the filename and type servers use to detect the format. */
class EncodedAudio(val bytes: ByteArray, val fileName: String, val mimeType: String) {
    companion object {
        fun wav(pcm: ByteArray) = EncodedAudio(WavWriter.encode(pcm), "audio.wav", "audio/wav")
    }
}

/**
 * Compresses dictation audio with the phone's Opus encoder into an Ogg file, about 10x smaller
 * than WAV at speech quality (24 kbit/s). Returns null if the device can't encode, and the
 * caller uploads WAV instead.
 */
object AudioEncoder {
    private const val BIT_RATE = 24_000
    private const val TIMEOUT_US = 10_000L

    fun opusOgg(ctx: Context, pcm: ByteArray, sampleRate: Int = 16000): EncodedAudio? {
        if (pcm.isEmpty()) return null
        val out = File.createTempFile("dictation", ".ogg", ctx.cacheDir)
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
            var track = -1
            var offset = 0
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 15_000_000_000L // a stuck codec must not hang dictation
            while (true) {
                check(System.nanoTime() < deadline) { "encoder timed out" }
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = minOf(buf.remaining(), pcm.size - offset) and 1.inv() // whole samples
                        val timeUs = offset / 2 * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            codec.queueInputBuffer(i, 0, n, timeUs, 0)
                            offset += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    o >= 0 -> {
                        val data = codec.getOutputBuffer(o)!!
                        // The Opus header travels in the track format; don't write it as audio.
                        if (info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            muxer.writeSampleData(track, data, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            muxer.stop()
            val bytes = out.readBytes()
            if (bytes.size < 64) null else EncodedAudio(bytes, "audio.ogg", "audio/ogg")
        } catch (e: Exception) {
            Diagnostics.warn("AudioEncoder", "Opus encoding failed, uploading WAV", e)
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            try { muxer?.release() } catch (_: Exception) {}
            out.delete()
        }
    }
}
