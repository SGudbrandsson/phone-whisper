package com.kafkasl.phonewhisper

/**
 * Whether the user is typing, from the Android 13+ input-method callbacks and the keyboard
 * window. Plain state with no Android calls, so it can be unit tested.
 *
 * - An editor starting input shows the bubble at once, before the keyboard has animated in.
 * - The keyboard window then keeps it up; the keyboard going away hides it, even if the field
 *   still holds focus (tapping outside a field often leaves it focused).
 * - If no keyboard appears within [KEYBOARD_WAIT_MS] (hardware keyboard, an app that focuses a
 *   field without opening the keyboard), the bubble hides again.
 */
class TypingSignals {
    var imeVisible = false
    /**
     * Set once the system has given us input-method callbacks. From then on input ending hides
     * the bubble straight away, without waiting for the keyboard's slide-out to finish.
     */
    var hooksActive = false
    private var inputActive = false
    private var inputPackage: String? = null
    private var startedAt = 0L
    /** True once the keyboard has shown for the current editor; its later disappearance hides. */
    private var keyboardSeenForInput = false

    /** Package of the editor that currently has input, if any. */
    val activeInputPackage: String? get() = if (inputActive) inputPackage else null

    fun onStartInput(packageName: String?, now: Long) {
        inputActive = true
        inputPackage = packageName
        startedAt = now
        keyboardSeenForInput = imeVisible
    }

    fun onFinishInput() {
        inputActive = false
        inputPackage = null
    }

    fun isTyping(now: Long, ownPackage: String): Boolean {
        if (inputActive && inputPackage == ownPackage) return false // our own settings screen
        if (hooksActive && !inputActive) return false
        if (imeVisible) {
            if (inputActive) keyboardSeenForInput = true
            return true
        }
        // Keyboard is down. Only bridge the gap while it's still on its way for a new editor.
        return inputActive && !keyboardSeenForInput && now - startedAt < KEYBOARD_WAIT_MS
    }

    companion object {
        const val KEYBOARD_WAIT_MS = 1000L
    }
}
