package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class AudioLevelTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[i * 2] = (s and 0xFF).toByte(); b[i * 2 + 1] = (s shr 8).toByte() }
    }

    @Test fun `silence is zero`() = assertEquals(0f, AudioLevel.levelOf(pcm(0, 0, 0, 0), 8))

    @Test fun `full scale is one`() = assertEquals(1f, AudioLevel.levelOf(pcm(32767, -32768, 32767, -32768), 8), 0.01f)

    @Test fun `louder is higher`() {
        val quiet = AudioLevel.levelOf(pcm(300, -300, 300, -300), 8)
        val loud = AudioLevel.levelOf(pcm(8000, -8000, 8000, -8000), 8)
        assertTrue(quiet in 0.01f..0.99f)
        assertTrue(loud > quiet)
    }

    @Test fun `respects length`() = assertEquals(0f, AudioLevel.levelOf(pcm(0, 0, 32767, 32767), 4))
}
