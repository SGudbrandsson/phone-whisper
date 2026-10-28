package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

class FillersTest {
    @Test fun `english hesitations go`() {
        assertEquals("So we should, ship it today.", Fillers.remove("Uh, so we should, um, ship it uhm today.", "en"))
        assertEquals("I think... we're done.", Fillers.remove("I think... uh... we're done.", "en"))
    }

    @Test fun `icelandic um is a word and stays`() {
        assertEquals("Ég var að tala um þetta.", Fillers.remove("Ég var að tala um þetta.", "is"))
        assertEquals("Ég var að tala um þetta.", Fillers.remove("Ég var að tala um þetta.", ""))
        assertEquals("Ég var að tala um þetta.", Fillers.remove("Öö, ég var að tala um þetta.", "is"))
    }

    @Test fun `real words containing the sounds are untouched`() {
        val s = "Hummus, umbrella, uhlan, mummy and the ehf company."
        assertEquals(s, Fillers.remove(s, "en"))
    }

    @Test fun `filler only becomes empty`() {
        assertEquals("", Fillers.remove("Uh... um.", "en").trim('.'))
    }

    @Test fun `hmm and drawn out sounds`() {
        assertEquals("Okay let's go.", Fillers.remove("Hmm, okay uhhh let's go.", "is"))
    }
}
