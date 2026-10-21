package com.kafkasl.phonewhisper

import org.junit.Assert.*
import org.junit.Test

class TypingSignalsTest {
    private val own = "com.kafkasl.phonewhisper"

    @Test fun `editor start shows at once, before the keyboard arrives`() {
        val t = TypingSignals()
        t.onStartInput("com.slack", now = 0)
        assertTrue(t.isTyping(now = 10, ownPackage = own))
    }

    @Test fun `no keyboard within the wait hides again`() {
        val t = TypingSignals()
        t.onStartInput("com.slack", now = 0)
        assertFalse(t.isTyping(now = TypingSignals.KEYBOARD_WAIT_MS + 1, ownPackage = own))
    }

    @Test fun `keyboard closing hides even if the field keeps focus`() {
        val t = TypingSignals()
        t.onStartInput("com.slack", now = 0)
        t.imeVisible = true
        assertTrue(t.isTyping(now = 100, ownPackage = own))
        t.imeVisible = false // tapped outside the field: keyboard gone, input not finished
        assertFalse(t.isTyping(now = 200, ownPackage = own))
        t.imeVisible = true // tapped the field again
        assertTrue(t.isTyping(now = 300, ownPackage = own))
    }

    @Test fun `moving between fields keeps showing`() {
        val t = TypingSignals()
        t.imeVisible = true
        t.onStartInput("com.slack", now = 0)
        t.onFinishInput()
        t.onStartInput("com.slack", now = 50)
        assertTrue(t.isTyping(now = 60, ownPackage = own))
    }

    @Test fun `finish without keyboard hides`() {
        val t = TypingSignals()
        t.onStartInput("com.slack", now = 0)
        t.onFinishInput()
        assertFalse(t.isTyping(now = 10, ownPackage = own))
        assertNull(t.activeInputPackage)
    }

    @Test fun `with hooks, input ending hides before the keyboard has slid away`() {
        val t = TypingSignals().apply { hooksActive = true }
        t.imeVisible = true
        t.onStartInput("com.slack", now = 0)
        assertTrue(t.isTyping(now = 10, ownPackage = own))
        t.onFinishInput() // keyboard window still listed while it animates out
        assertFalse(t.isTyping(now = 20, ownPackage = own))
    }

    @Test fun `without hooks the keyboard alone decides`() {
        val t = TypingSignals()
        t.imeVisible = true
        assertTrue(t.isTyping(now = 0, ownPackage = own))
    }

    @Test fun `never shows over our own settings`() {
        val t = TypingSignals()
        t.onStartInput(own, now = 0)
        t.imeVisible = true
        assertFalse(t.isTyping(now = 10, ownPackage = own))
    }
}
