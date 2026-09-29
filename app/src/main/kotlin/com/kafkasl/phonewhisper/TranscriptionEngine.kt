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

    enum class Source(val label: String) { CLOUD("Cloud"), LOCAL("Local"), LOCAL_FALLBACK("Local (offline)") }

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
        fun cancel() { cancelled = true; call?.cancel() }
    }

    private val settings = AppSettings(ctx)
    private val modelLock = Any()
    @Volatile private var local: LocalTranscriber? = null

    fun hasLocalModel() = LocalTranscriber.availableModels(ctx).isNotEmpty()

    /**
     * Transcribes [pcm] (16 kHz mono 16-bit). [onStatus] reports progress such as a fallback;
     * [done] is called exactly once, on a background thread, unless the job is cancelled.
     */
    fun run(pcm: ByteArray, onStatus: (String) -> Unit = {}, done: (Outcome) -> Unit): Job {
        val job = Job()
        val finish: (Outcome) -> Unit = { if (!job.cancelled) done(it) }

        val plan = TranscriptionRouter.plan(
            mode = settings.mode,
            online = Connectivity.isOnline(ctx),
            hasApiKey = settings.hasApiKey,
            hasLocalModel = hasLocalModel(),
            baseUrl = settings.baseUrl,
        )
        Log.i(TAG, "Transcription plan: $plan")
        when (plan) {
            is TranscriptionRouter.Plan.Cloud -> runCloud(pcm, plan.fallbackToLocal, job, onStatus, finish)
            TranscriptionRouter.Plan.Local -> runLocal(pcm, Source.LOCAL, job, finish)
            is TranscriptionRouter.Plan.Unavailable -> thread { finish(Outcome.Failure(plan.reason)) }
        }
        return job
    }

    private fun runCloud(
        pcm: ByteArray, fallbackToLocal: Boolean, job: Job,
        onStatus: (String) -> Unit, finish: (Outcome) -> Unit,
    ) {
        job.call = TranscriberClient.transcribe(WavWriter.encode(pcm), settings.cloudConfig()) { result ->
            job.call = null
            when {
                job.cancelled -> Unit
                !result.text.isNullOrBlank() -> cleanup(result.text.trim(), Source.CLOUD, job, finish)
                result.networkFailure && fallbackToLocal -> {
                    Log.i(TAG, "Cloud unreachable (${result.error}); falling back to local")
                    onStatus("Offline — local model…")
                    runLocal(pcm, Source.LOCAL_FALLBACK, job, finish)
                }
                result.text != null -> finish(Outcome.Failure("No speech detected"))
                else -> finish(Outcome.Failure(result.error ?: "Empty transcript"))
            }
        }
    }

    private fun runLocal(pcm: ByteArray, source: Source, job: Job, finish: (Outcome) -> Unit) {
        thread {
            try {
                val model = obtainLocalModel()
                    ?: return@thread finish(Outcome.Failure("Local model could not be loaded"))
                if (job.cancelled) return@thread
                val samples = pcmToFloat(pcm)
                val t0 = System.currentTimeMillis()
                val text = model.transcribe(samples, SAMPLE_RATE)
                Log.i(TAG, "Local transcription: ${System.currentTimeMillis() - t0}ms, ${samples.size / SAMPLE_RATE}s audio")
                if (text.isBlank()) finish(Outcome.Failure("No speech detected"))
                else cleanup(text, source, job, finish)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                finish(Outcome.Failure("Local error: ${e.message}"))
            }
        }
    }

    private fun cleanup(text: String, source: Source, job: Job, finish: (Outcome) -> Unit) {
        if (!settings.usePostProcessing) return finish(Outcome.Success(text, text, source, null))

        // Cleanup needs the network; skip it rather than fail when offline.
        val online = source != Source.LOCAL_FALLBACK && Connectivity.isOnline(ctx)
        val keyMissing = !settings.hasApiKey &&
            Endpoints.normalizeBase(settings.baseUrl) == Endpoints.DEFAULT_BASE_URL
        if (!online) return finish(Outcome.Success(text, text, source, "Cleanup skipped (offline)"))
        if (keyMissing) return finish(Outcome.Success(text, text, source, "Cleanup needs an API key"))

        job.call = PostProcessor.process(
            text, settings.postProcessingPrompt, settings.baseUrl, settings.apiKey, settings.chatModel
        ) { result ->
            job.call = null
            if (job.cancelled) return@process
            if (!result.text.isNullOrBlank()) finish(Outcome.Success(result.text, text, source, null))
            else finish(Outcome.Success(text, text, source, "Cleanup failed — raw text used"))
        }
    }

    /** Returns the loaded local model, loading it if needed. Blocking; call off the main thread. */
    fun obtainLocalModel(): LocalTranscriber? = synchronized(modelLock) {
        val available = LocalTranscriber.availableModels(ctx)
        val wanted = settings.modelName.takeIf { it in available } ?: available.firstOrNull() ?: return null
        local?.let { if (it.modelName == wanted) return it; it.release() }
        local = null
        val t0 = System.currentTimeMillis()
        local = LocalTranscriber.create(ctx, wanted)
        Log.i(TAG, "Loaded local model $wanted in ${System.currentTimeMillis() - t0}ms")
        local
    }

    fun unloadLocalModel() = synchronized(modelLock) {
        local?.release()
        local = null
    }

    companion object {
        private const val TAG = "TranscriptionEngine"
        const val SAMPLE_RATE = 16000

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
