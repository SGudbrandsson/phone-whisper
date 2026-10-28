package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's external files dir.
 */
class LocalTranscriber private constructor(
    private val recognizer: OfflineRecognizer,
    val modelName: String,
    /** Language the model was loaded for (Whisper only); a change needs a reload. */
    val language: String,
    private val isWhisper: Boolean,
) {
    private var released = false

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    @Synchronized
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        check(!released) { "Model was unloaded" }
        // Whisper decodes at most 30 s per stream; longer dictations go in chunks.
        val chunk = if (isWhisper) WHISPER_CHUNK_SECONDS * sampleRate else samples.size
        return chunkRanges(samples.size, chunk).joinToString(" ") { range ->
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples.copyOfRange(range.first, range.last + 1), sampleRate)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            text
        }.trim()
    }

    /** Frees the native model. Waits for any transcription in progress to finish. */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        recognizer.release()
    }

    companion object {
        private const val TAG = "LocalTranscriber"
        private const val WHISPER_CHUNK_SECONDS = 28

        internal fun chunkRanges(size: Int, chunk: Int): List<IntRange> =
            if (size <= 0) emptyList() else (0 until size step chunk.coerceAtLeast(1)).map { it until minOf(size, it + chunk) }

        /** English-only Whisper exports end in ".en"; the rest are multilingual. */
        internal fun isMultilingualWhisper(modelName: String) = !modelName.endsWith(".en")

        /** Find available model dirs under the app's files/models/ dir */
        fun availableModels(ctx: Context): List<String> {
            val modelsDir = File(ctx.filesDir, "models")
            if (!modelsDir.exists()) return emptyList()
            return modelsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
        }

        /** Create a LocalTranscriber for the given model directory name. Returns null on failure. */
        fun create(ctx: Context, modelName: String, language: String = AppSettings(ctx).language): LocalTranscriber? {
            val modelDir = File(ctx.filesDir, "models/$modelName")
            if (!modelDir.exists()) {
                Diagnostics.error(TAG, "Model dir not found: $modelDir")
                return null
            }

            val whisperLanguage = if (isMultilingualWhisper(modelName)) language else "en"
            val config = detectModelConfig(modelDir, whisperLanguage) ?: run {
                Diagnostics.error(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: $modelName")
                LocalTranscriber(recognizer, modelName, language, config.modelConfig.whisper.encoder.isNotEmpty())
            } catch (e: Exception) {
                Diagnostics.error(TAG, "Failed to load model $modelName", e)
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        private fun detectModelConfig(dir: File, whisperLanguage: String): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val names = dir.list()?.toList() ?: return null
            val tokens = tokensFile(names)?.let { "$p/$it" } ?: return null
            fun file(prefix: String) = findFile(names, prefix)?.let { "$p/$it" }

            // Moonshine (has preprocess.onnx)
            if (File("$p/preprocess.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = file("encode") ?: return null,
                            uncachedDecoder = file("uncached_decode") ?: return null,
                            cachedDecoder = file("cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val whisperEncoder = file("encoder")
            val whisperDecoder = file("decoder")
            if (whisperEncoder != null && whisperDecoder != null && file("joiner") == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = whisperEncoder,
                            decoder = whisperDecoder,
                            language = whisperLanguage, // "" lets Whisper detect it
                            task = "transcribe",
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            val encoder = file("encoder")
            val decoder = file("decoder")
            val joiner = file("joiner")
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = 2,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = file("model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = 2,
                    )
                )
            }

            return null
        }

        /** tokens.txt, or Whisper's "<size>-tokens.txt". */
        internal fun tokensFile(names: List<String>): String? =
            names.firstOrNull { it == "tokens.txt" } ?: names.firstOrNull { it.endsWith("-tokens.txt") }

        /**
         * First model file whose name is [prefix] at the start or after a "-" (Whisper exports
         * are "base.en-encoder.onnx"), preferring int8. The boundary keeps "cached_decode"
         * from matching "uncached_decode".
         */
        internal fun findFile(names: List<String>, prefix: String): String? {
            val pattern = Regex("(^|-)" + Regex.escape(prefix) + "[.]")
            val candidates = names.filter { pattern.containsMatchIn(it) && (it.endsWith(".onnx") || it.endsWith(".ort")) }
            return candidates.firstOrNull { it.contains("int8") } ?: candidates.firstOrNull()
        }

        /** Deletes float models that have an int8 twin; only int8 is loaded. Saves 100+ MB. */
        internal fun pruneUnusedFloatModels(dir: File) {
            val names = dir.list()?.toSet() ?: return
            names.filter { it.endsWith(".onnx") && !it.contains("int8") }
                .filter { it.removeSuffix(".onnx") + ".int8.onnx" in names }
                .forEach { File(dir, it).delete() }
        }
    }
}
