package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/**
 * Turns recorded PCM into text: picks cloud or local per [TranscriptionRouter], falls back to
 * the local model when the cloud can't be reached, and runs the optional cleanup step.
 * Used by the overlay service for dictation and by the history screen for retries.
 */
class TranscriptionEngine(private val ctx: Context) {

    enum class Source(val label: String) {
        CLOUD("Cloud"), CLOUD_LIVE("Cloud (live)"), LOCAL("Local"), LOCAL_FALLBACK("Local (offline)")
    }

    sealed class Outcome {
        /** [rawText] is the transcript before cleanup; equal to [text] when cleanup didn't run. */
        data class Success(val text: String, val rawText: String, val source: Source, val note: String?) : Outcome()
        data class Failure(val error: String) : Outcome()
    }

    /** Handle for one transcription; [cancel] stops network calls and suppresses the callback. */
    class Job {
        @Volatile var cancelled = false
            private set
        @Volatile internal var call: okhttp3.Call? = null
        @Volatile internal var live: RealtimeSession? = null
        fun cancel() { cancelled = true; call?.cancel(); live?.cancel() }
    }

    private val settings = AppSettings(ctx)
    private val modelLock = Any()
    @Volatile private var local: LocalTranscriber? = null

    fun hasLocalModel() = LocalTranscriber.availableModels(ctx).isNotEmpty()

    /**
     * Transcribes [pcm] (16 kHz mono 16-bit). [onStatus] reports progress such as a fallback;
     * [done] is called exactly once, on a background thread, unless the job is cancelled.
     */
    /**
     * Opens a live ElevenLabs session for a dictation that is starting, or null when live
     * transcription is off, has no key, or there's no network. Feed it with [RealtimeSession.push]
     * and pass it to [run].
     */
    fun startLive(): RealtimeSession? {
        if (!settings.liveTranscription || settings.mode == TranscriptionMode.LOCAL_ONLY) return null
        val key = (liveKeyForTest ?: settings.elevenLabsKey).ifBlank { return null }
        if (!Connectivity.isOnline(ctx)) return null
        return try {
            RealtimeSession.open(key, settings.language, RealtimeSession.keyterms(settings.vocabulary), settings.removeFillers)
        } catch (e: Exception) {
            Diagnostics.warn(TAG, "Could not open live session", e)
            null
        }
    }

    /** Robolectric has no Keystore, so tests supply the key here. */
    @androidx.annotation.VisibleForTesting internal var liveKeyForTest: String? = null

    /** Converts PCM for upload. Swappable in tests. */
    internal var encoder: (ByteArray) -> EncodedAudio? = { pcm -> AudioEncoder.opusOgg(ctx, pcm) }

    fun run(pcm: ByteArray, live: RealtimeSession? = null, onStatus: (String) -> Unit = {}, done: (Outcome) -> Unit): Job {
        val job = Job()
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val finish: (Outcome) -> Unit = { outcome ->
            if (!job.cancelled && delivered.compareAndSet(false, true)) {
                when (outcome) {
                    is Outcome.Failure -> Diagnostics.warn(TAG, "Transcription failed: ${outcome.error}")
                    is Outcome.Success -> Diagnostics.info(TAG, "Transcribed via ${outcome.source.label}" +
                        (outcome.note?.let { " ($it)" } ?: ""))
                }
                try { done(outcome) } catch (e: Exception) { Diagnostics.error(TAG, "Outcome handler failed", e) }
            }
        }

        if (live != null) {
            // The live session already has the audio: only the final commit is left.
            job.live = live
            val t0 = System.currentTimeMillis()
            live.finish { text, error ->
                job.live = null
                if (job.cancelled) return@finish
                if (text != null) {
                    Diagnostics.info(TAG, "Live transcript ${System.currentTimeMillis() - t0} ms after stop " +
                        "(${pcm.size / 2 / SAMPLE_RATE.toFloat()} s audio)")
                    cleanup(text, Source.CLOUD_LIVE, job, finish)
                } else {
                    Diagnostics.warn(TAG, "Live transcription failed ($error); uploading the recording instead")
                    onStatus("Uploading…")
                    plan(pcm, job, onStatus, finish)
                }
            }
            return job
        }
        plan(pcm, job, onStatus, finish)
        return job
    }

    private fun plan(pcm: ByteArray, job: Job, onStatus: (String) -> Unit, finish: (Outcome) -> Unit) {
        // Planning touches the Keystore and disk, so keep it off the caller's (main) thread.
        thread {
            try {
                val plan = TranscriptionRouter.plan(
                    mode = settings.mode,
                    online = Connectivity.isOnline(ctx),
                    hasApiKey = settings.hasApiKey,
                    hasLocalModel = hasLocalModel(),
                    baseUrl = settings.baseUrl,
                )
                Log.i(TAG, "Transcription plan: $plan")
                if (job.cancelled) return@thread
                when (plan) {
                    is TranscriptionRouter.Plan.Cloud -> runCloud(pcm, plan.fallbackToLocal, job, onStatus, finish)
                    TranscriptionRouter.Plan.Local -> runLocal(pcm, Source.LOCAL, job, finish)
                    is TranscriptionRouter.Plan.Unavailable -> finish(Outcome.Failure(plan.reason))
                }
            } catch (e: Exception) {
                Diagnostics.error(TAG, "Transcription failed to start", e)
                finish(Outcome.Failure(e.message ?: "Transcription failed"))
            }
        }
    }

    private fun runCloud(
        pcm: ByteArray, fallbackToLocal: Boolean, job: Job,
        onStatus: (String) -> Unit, finish: (Outcome) -> Unit,
    ) {
        val e0 = System.currentTimeMillis()
        val audio = (if (settings.compressAudio && !settings.compressionRejectedHere) encoder(pcm) else null)
            ?: EncodedAudio.wav(pcm)
        if (audio.fileName != "audio.wav") Log.i(TAG, "Encoded ${audio.bytes.size / 1024} KB in ${System.currentTimeMillis() - e0} ms")
        sendCloud(pcm, audio, settings.cloudConfig(), fallbackToLocal, job, onStatus, finish)
    }

    private fun sendCloud(
        pcm: ByteArray, audio: EncodedAudio, config: TranscriberClient.Config, fallbackToLocal: Boolean, job: Job,
        onStatus: (String) -> Unit, finish: (Outcome) -> Unit,
    ) {
        val t0 = System.currentTimeMillis()
        TranscriberClient.transcribe(audio, config, onCall = { job.call = it; if (job.cancelled) it.cancel() }) { result ->
            job.call = null
            // Upload + server time, to see where dictation latency goes (diagnostics report).
            Diagnostics.info(TAG, "Cloud request: ${System.currentTimeMillis() - t0} ms for " +
                "${pcm.size / 2 / SAMPLE_RATE.toFloat()} s audio (${audio.bytes.size / 1024} KB ${audio.fileName.substringAfter('.')})")
            when {
                job.cancelled -> Unit
                audio.fileName != "audio.wav" && result.looksLikeFormatRejection -> {
                    Diagnostics.warn(TAG, "Server couldn't read compressed audio (${result.error}); retrying as WAV")
                    settings.compressionRejectedHere = true
                    sendCloud(pcm, EncodedAudio.wav(pcm), config, fallbackToLocal, job, onStatus, finish)
                }
                config.prompt != null && result.looksLikeParamRejection -> {
                    // Don't let the dictionary break dictation: drop the hints here from now on.
                    Diagnostics.warn(TAG, "Server refused dictionary hints (${result.error}); retrying without them")
                    settings.hintsRejectedHere = true
                    sendCloud(pcm, audio, config.copy(prompt = null), fallbackToLocal, job, onStatus, finish)
                }
                !result.text.isNullOrBlank() -> cleanup(result.text.trim(), Source.CLOUD, job, finish)
                result.networkFailure && fallbackToLocal -> {
                    Diagnostics.warn(TAG, "Cloud unreachable (${result.error}); falling back to local")
                    onStatus("Offline — local model…")
                    runLocal(pcm, Source.LOCAL_FALLBACK, job, finish)
                }
                result.text != null -> finish(Outcome.Failure("No speech detected"))
                else -> finish(Outcome.Failure(result.error ?: "Empty transcript"))
            }
        }
    }

    private fun runLocal(pcm: ByteArray, source: Source, job: Job, finish: (Outcome) -> Unit) {
        main.removeCallbacks(idleUnload) // in use again: keep it loaded
        thread {
            try {
                val samples = pcmToFloat(pcm)
                val t0 = System.currentTimeMillis()
                val text = try {
                    transcribeWithLocalModel(samples) ?: return@thread finish(Outcome.Failure("Local model could not be loaded"))
                } catch (_: IllegalStateException) {
                    // The model was swapped (settings change) mid-decode; load the new one and retry once.
                    if (job.cancelled) return@thread
                    transcribeWithLocalModel(samples) ?: return@thread finish(Outcome.Failure("Local model could not be loaded"))
                }
                Log.i(TAG, "Local transcription: ${System.currentTimeMillis() - t0}ms, ${samples.size / SAMPLE_RATE}s audio")
                scheduleIdleUnload()
                if (text.isBlank()) finish(Outcome.Failure("No speech detected"))
                else cleanup(text, source, job, finish)
            } catch (e: Exception) {
                Diagnostics.error(TAG, "Local transcription failed", e)
                finish(Outcome.Failure("Local error: ${e.message}"))
            }
        }
    }

    private fun transcribeWithLocalModel(samples: FloatArray): String? =
        obtainLocalModel()?.transcribe(samples, SAMPLE_RATE)

    /** [raw] is the model's output; dictionary corrections are applied before and after cleanup. */
    private fun cleanup(raw: String, source: Source, job: Job, finish: (Outcome) -> Unit) {
        val vocab = settings.vocabulary
        val text = Vocabulary.apply(if (settings.removeFillers) Fillers.remove(raw, settings.language) else raw, vocab)
        if (!settings.usePostProcessing) return finish(Outcome.Success(text, raw, source, null))

        // Cleanup needs the network; skip it rather than fail when offline.
        val online = source != Source.LOCAL_FALLBACK && Connectivity.isOnline(ctx)
        val keyMissing = !settings.hasApiKey &&
            Endpoints.normalizeBase(settings.baseUrl) == Endpoints.DEFAULT_BASE_URL
        if (!online) return finish(Outcome.Success(text, raw, source, "Cleanup skipped (offline)"))
        if (keyMissing) return finish(Outcome.Success(text, raw, source, "Cleanup needs an API key"))

        if (job.cancelled) return
        PostProcessor.process(
            text, settings.postProcessingPrompt + Vocabulary.cleanupInstruction(vocab).orEmpty(),
            settings.baseUrl, settings.apiKey, settings.chatModel,
            onCall = { job.call = it; if (job.cancelled) it.cancel() },
        ) { result ->
            job.call = null
            if (job.cancelled) return@process
            if (!result.text.isNullOrBlank()) finish(Outcome.Success(Vocabulary.apply(result.text, vocab), raw, source, null))
            else {
                Diagnostics.warn(TAG, "Cleanup failed: ${result.error}")
                finish(Outcome.Success(text, raw, source, "Cleanup failed — raw text used"))
            }
        }
    }

    /** Returns the loaded local model, loading it if needed. Blocking; call off the main thread. */
    fun obtainLocalModel(): LocalTranscriber? = synchronized(modelLock) {
        val available = LocalTranscriber.availableModels(ctx)
        val wanted = settings.modelName.takeIf { it in available } ?: available.firstOrNull() ?: return null
        val language = settings.language
        local?.let { if (it.modelName == wanted && it.language == language) return it; it.release() }
        local = null
        val t0 = System.currentTimeMillis()
        local = LocalTranscriber.create(ctx, wanted, language)
        Log.i(TAG, "Loaded local model $wanted in ${System.currentTimeMillis() - t0}ms")
        local
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleUnload = Runnable {
        if (settings.mode != TranscriptionMode.LOCAL_ONLY) thread { unloadLocalModel(); Log.i(TAG, "Unloaded idle fallback model") }
    }

    /**
     * A model loaded as an offline fallback is freed after [IDLE_UNLOAD_MS] without use, so it
     * doesn't hold memory all day after one dead-Wi-Fi moment. Local-only mode keeps it loaded
     * so dictation stays fast.
     */
    private fun scheduleIdleUnload() {
        main.removeCallbacks(idleUnload)
        if (settings.mode != TranscriptionMode.LOCAL_ONLY) main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
    }

    /** True while a local model is in memory (for tests and diagnostics). */
    val isLocalModelLoaded get() = local != null

    fun unloadLocalModel(): Unit = synchronized(modelLock) {
        main.removeCallbacks(idleUnload)
        local?.release()
        local = null
    }

    companion object {
        private const val TAG = "TranscriptionEngine"
        const val SAMPLE_RATE = 16000
        const val IDLE_UNLOAD_MS = 2 * 60 * 1000L

        fun pcmToFloat(pcm: ByteArray): FloatArray {
            val samples = FloatArray(pcm.size / 2)
            for (i in samples.indices) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
            }
            return samples
        }
    }
}
