package com.kafkasl.phonewhisper

/**
 * Removes hesitation sounds ("uh", "uhm", "öö") from a transcript. ElevenLabs Realtime does
 * this itself with `no_verbatim`; this catches what other engines leave in.
 *
 * Conservative on purpose: only sounds that aren't words. "um" is an Icelandic word ("about"),
 * so it is removed only when the language is explicitly English.
 */
object Fillers {
    private const val ANY_LANGUAGE = "u+h+m*|u+m{2,}|e+r+m+|e+h+m+|e+h|h+m{2,}|m{2,}|ö{2,}"

    fun remove(text: String, language: String): String {
        if (text.isBlank()) return text
        val sounds = if (language.equals("en", ignoreCase = true)) "$ANY_LANGUAGE|u+m" else ANY_LANGUAGE
        // A filler plus the comma or dots that usually follow it: "Uh, so" -> "so".
        val filler = Regex("(?<![\\p{L}\\p{N}])(?:$sounds)(?![\\p{L}\\p{N}])(?:\\s*(?:,|\\.\\.\\.|…))?", RegexOption.IGNORE_CASE)
        var out = filler.replace(text, "")
        out = out.replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("\\s+([,.;:!?])"), "$1")
            .replace(Regex(",\\s*([,.;:!?])"), "$1") // "so, , then" or "so,." left behind
            .replace(Regex("^[\\s,;]+"), "")
            .trim()
        // Keep the sentence capitalized when the filler started it.
        if (out.isNotEmpty() && text.trimStart().firstOrNull()?.isUpperCase() == true && out[0].isLowerCase()) {
            out = out[0].uppercaseChar() + out.substring(1)
        }
        return out
    }
}
