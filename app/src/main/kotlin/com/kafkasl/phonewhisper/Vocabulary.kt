package com.kafkasl.phonewhisper

import org.json.JSONArray
import org.json.JSONObject

/** A dictionary word: how it should be written, and what the model tends to hear instead. */
data class VocabEntry(val term: String, val heardAs: List<String> = emptyList())

/**
 * The user's dictionary. Pure logic (org.json only), so it is unit tested on the JVM.
 *
 * - [hintPrompt] goes to the transcription model as a spelling hint (`prompt` field).
 * - [apply] fixes the transcript locally, for any engine: "also heard as" variants become the
 *   term, and the term's own spelling/casing is enforced ("aihub" -> "AIHub").
 * - [cleanupInstruction] is appended to the cleanup prompt.
 */
object Vocabulary {
    /** Whisper reads at most ~224 prompt tokens; stay well under for every provider. */
    const val MAX_HINT_CHARS = 600

    fun toJson(entries: List<VocabEntry>): String = JSONArray().apply {
        entries.forEach { e -> put(JSONObject().put("term", e.term).put("heardAs", JSONArray(e.heardAs))) }
    }.toString()

    fun fromJson(json: String?): List<VocabEntry> = try {
        val arr = JSONArray(json ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val term = o.optString("term").trim()
            if (term.isEmpty()) return@mapNotNull null
            val heard = o.optJSONArray("heardAs")?.let { a -> (0 until a.length()).map { a.optString(it).trim() } }.orEmpty()
            VocabEntry(term, heard.filter { it.isNotEmpty() && !it.equals(term, ignoreCase = false) })
        }
    } catch (_: Exception) { emptyList() }

    /** "AIHub, Siggi, Reykjavík" — the format Whisper-style models pick spellings up from. */
    fun hintPrompt(entries: List<VocabEntry>): String? {
        if (entries.isEmpty()) return null
        val sb = StringBuilder()
        for (e in entries) {
            val next = if (sb.isEmpty()) e.term else ", ${e.term}"
            if (sb.length + next.length > MAX_HINT_CHARS) break
            sb.append(next)
        }
        return sb.toString().ifEmpty { null }
    }

    fun cleanupInstruction(entries: List<VocabEntry>): String? =
        hintPrompt(entries)?.let { "\n\nThese words and names must be spelled exactly like this: $it" }

    /** Replaces misheard variants and normalizes the spelling of each term, on word boundaries. */
    fun apply(text: String, entries: List<VocabEntry>): String {
        if (entries.isEmpty() || text.isEmpty()) return text
        var out = text
        // Longest phrases first, so "open ai hub" wins over "ai hub".
        val rules = entries.flatMap { e -> (e.heardAs + e.term).map { it to e.term } }
            .sortedByDescending { it.first.length }
        for ((from, to) in rules) {
            out = boundary(from).replace(out) { m -> if (m.value == to) m.value else to }
        }
        return out
    }

    /** Case-insensitive, whole words only; works for Icelandic letters (þ, ð, æ, ...). */
    private fun boundary(phrase: String): Regex {
        val words = phrase.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        return Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", setOf(RegexOption.IGNORE_CASE))
    }

    /** Parses "a, b; c" from the "also heard as" field. */
    fun parseVariants(input: String): List<String> =
        input.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
