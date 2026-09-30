package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LocalModelFilesTest {

    @Test fun `whisper exports with a size prefix are found`() {
        val names = listOf("base.en-encoder.onnx", "base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "base.en-tokens.txt")
        assertEquals("base.en-encoder.int8.onnx", LocalTranscriber.findFile(names, "encoder"))
        assertEquals("base.en-decoder.int8.onnx", LocalTranscriber.findFile(names, "decoder"))
        assertNull(LocalTranscriber.findFile(names, "joiner"))
        assertEquals("base.en-tokens.txt", LocalTranscriber.tokensFile(names))
    }

    @Test fun `turbo layout`() {
        val names = listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt")
        assertEquals("turbo-encoder.int8.onnx", LocalTranscriber.findFile(names, "encoder"))
        assertEquals("turbo-tokens.txt", LocalTranscriber.tokensFile(names))
        assertTrue(LocalTranscriber.isMultilingualWhisper("sherpa-onnx-whisper-turbo"))
        assertFalse(LocalTranscriber.isMultilingualWhisper("sherpa-onnx-whisper-base.en"))
    }

    @Test fun `moonshine decoders are not confused`() {
        val names = listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt")
        assertEquals("encode.int8.onnx", LocalTranscriber.findFile(names, "encode"))
        assertEquals("cached_decode.int8.onnx", LocalTranscriber.findFile(names, "cached_decode"))
        assertEquals("uncached_decode.int8.onnx", LocalTranscriber.findFile(names, "uncached_decode"))
        assertNull("encode must not match encoder", LocalTranscriber.findFile(listOf("encoder.onnx"), "encode"))
    }

    @Test fun `parakeet and ctc layouts`() {
        val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        assertEquals("joiner.int8.onnx", LocalTranscriber.findFile(names, "joiner"))
        assertEquals("tokens.txt", LocalTranscriber.tokensFile(names))
        assertEquals("model.int8.onnx", LocalTranscriber.findFile(listOf("model.int8.onnx", "tokens.txt"), "model"))
    }

    @Test fun `float twins of int8 models are pruned`() {
        val dir = Files.createTempDirectory("m").toFile()
        try {
            listOf("base.en-encoder.onnx", "base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "preprocess.onnx", "base.en-tokens.txt")
                .forEach { File(dir, it).writeText("x") }
            LocalTranscriber.pruneUnusedFloatModels(dir)
            assertEquals(setOf("base.en-encoder.int8.onnx", "base.en-decoder.int8.onnx", "preprocess.onnx", "base.en-tokens.txt"),
                dir.list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `long audio is split into whisper-sized chunks`() {
        assertEquals(listOf(0 until 10), LocalTranscriber.chunkRanges(10, 28))
        assertEquals(listOf(0 until 28, 28 until 56, 56 until 60), LocalTranscriber.chunkRanges(60, 28))
        assertTrue(LocalTranscriber.chunkRanges(0, 28).isEmpty())
    }
}
