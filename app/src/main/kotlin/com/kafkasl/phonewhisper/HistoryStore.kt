package com.kafkasl.phonewhisper

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** One dictation. Failed entries keep their audio ([audioPath]) so they can be retried. */
data class HistoryEntry(
    val id: Long,
    val createdAt: Long,
    val status: Status,
    val text: String?,
    val rawText: String?,
    val source: String?,
    val appPackage: String?,
    val error: String?,
    val audioPath: String?,
    val durationMs: Long,
) {
    enum class Status { OK, FAILED }
    val canRetry get() = status == Status.FAILED && audioPath != null && File(audioPath).exists()
}

/** Pure retention rules, unit tested. */
object HistoryPolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000
    val RETENTION_CHOICES = listOf(1, 7, 30, 0) // days; 0 = keep forever
    const val DEFAULT_RETENTION_DAYS = 7
    /** Recordings shorter than this are treated as accidental taps and not kept on failure. */
    const val MIN_FAILED_AUDIO_MS = 1000L

    /** Entries created before the returned time should be deleted; null means keep everything. */
    fun cutoff(nowMs: Long, retentionDays: Int): Long? =
        if (retentionDays <= 0) null else nowMs - retentionDays * DAY_MS

    fun label(days: Int) = when (days) {
        0 -> "Never"
        1 -> "After 1 day"
        else -> "After $days days"
    }

    fun durationMs(pcmBytes: Int, sampleRate: Int = 16000) = pcmBytes / 2 * 1000L / sampleRate
}

/** SQLite-backed dictation history. All calls are blocking; use off the main thread. */
class HistoryStore private constructor(private val ctx: Context) :
    SQLiteOpenHelper(ctx, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                text TEXT,
                raw_text TEXT,
                source TEXT,
                app_package TEXT,
                error TEXT,
                audio_path TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX history_created ON history(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private val audioDir get() = File(ctx.filesDir, "history_audio").apply { mkdirs() }

    fun addSuccess(text: String, rawText: String, source: String, appPackage: String?, durationMs: Long): Long =
        writableDatabase.insert("history", null, ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            put("status", HistoryEntry.Status.OK.name)
            put("text", text)
            if (rawText != text) put("raw_text", rawText)
            put("source", source)
            put("app_package", appPackage)
            put("duration_ms", durationMs)
        }).also { notifyChanged() }

    /** Saves a failed dictation together with its audio so it can be retried later. */
    fun addFailure(error: String, pcm: ByteArray, appPackage: String?): Long {
        val db = writableDatabase
        val id = db.insert("history", null, ContentValues().apply {
            put("created_at", System.currentTimeMillis())
            put("status", HistoryEntry.Status.FAILED.name)
            put("error", error)
            put("app_package", appPackage)
            put("duration_ms", HistoryPolicy.durationMs(pcm.size))
        })
        val file = File(audioDir, "$id.wav")
        file.writeBytes(WavWriter.encode(pcm))
        db.update("history", ContentValues().apply { put("audio_path", file.absolutePath) }, "id=?", arrayOf("$id"))
        notifyChanged()
        return id
    }

    /** Marks a failed entry as done after a successful retry and deletes its audio. */
    fun markRetried(id: Long, text: String, rawText: String, source: String) {
        get(id)?.audioPath?.let { File(it).delete() }
        writableDatabase.update("history", ContentValues().apply {
            put("status", HistoryEntry.Status.OK.name)
            put("text", text)
            if (rawText != text) put("raw_text", rawText) else putNull("raw_text")
            put("source", source)
            putNull("error")
            putNull("audio_path")
        }, "id=?", arrayOf("$id"))
        notifyChanged()
    }

    fun updateError(id: Long, error: String) {
        writableDatabase.update("history", ContentValues().apply { put("error", error) }, "id=?", arrayOf("$id"))
        notifyChanged()
    }

    fun get(id: Long): HistoryEntry? =
        readableDatabase.query("history", null, "id=?", arrayOf("$id"), null, null, null).use {
            if (it.moveToFirst()) it.toEntry() else null
        }

    fun recent(limit: Int = 500): List<HistoryEntry> =
        readableDatabase.query("history", null, null, null, null, null, "created_at DESC", "$limit").use { c ->
            buildList { while (c.moveToNext()) add(c.toEntry()) }
        }

    fun delete(id: Long) {
        get(id)?.audioPath?.let { File(it).delete() }
        writableDatabase.delete("history", "id=?", arrayOf("$id"))
        notifyChanged()
    }

    fun clearAll() {
        writableDatabase.delete("history", null, null)
        audioDir.listFiles()?.forEach { it.delete() }
        notifyChanged()
    }

    /** Deletes entries (and their audio) older than the retention setting. Returns rows removed. */
    fun prune(retentionDays: Int, nowMs: Long = System.currentTimeMillis()): Int {
        val cutoff = HistoryPolicy.cutoff(nowMs, retentionDays) ?: return 0
        val db = writableDatabase
        db.query("history", arrayOf("audio_path"), "created_at < ? AND audio_path IS NOT NULL",
            arrayOf("$cutoff"), null, null, null).use { c ->
            while (c.moveToNext()) File(c.getString(0)).delete()
        }
        val removed = db.delete("history", "created_at < ?", arrayOf("$cutoff"))
        // Remove audio files whose row is gone (e.g. after a crash mid-write). Skip recent files
        // so a failure being saved right now isn't mistaken for an orphan.
        val known = db.query("history", arrayOf("audio_path"), "audio_path IS NOT NULL", null, null, null, null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        audioDir.listFiles()
            ?.filter { it.absolutePath !in known && nowMs - it.lastModified() > HistoryPolicy.DAY_MS }
            ?.forEach { it.delete() }
        if (removed > 0) notifyChanged()
        return removed
    }

    private fun Cursor.toEntry() = HistoryEntry(
        id = getLong(getColumnIndexOrThrow("id")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        status = HistoryEntry.Status.valueOf(getString(getColumnIndexOrThrow("status"))),
        text = str("text"),
        rawText = str("raw_text"),
        source = str("source"),
        appPackage = str("app_package"),
        error = str("error"),
        audioPath = str("audio_path"),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
    )

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }

    companion object {
        @Volatile private var instance: HistoryStore? = null
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        fun get(ctx: Context): HistoryStore =
            instance ?: synchronized(this) { instance ?: HistoryStore(ctx.applicationContext).also { instance = it } }

        /** Listeners run on the thread that changed the store. */
        fun addListener(l: () -> Unit) = listeners.add(l)
        fun removeListener(l: () -> Unit) = listeners.remove(l)
        private fun notifyChanged() = listeners.forEach { it() }
    }
}

object WavReader {
    /** Returns the PCM payload of a WAV file written by [WavWriter] (or any canonical PCM WAV). */
    fun pcm(wav: ByteArray): ByteArray {
        require(wav.size >= 12 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") { "Not a WAV file" }
        var off = 12
        while (off + 8 <= wav.size) {
            val id = String(wav, off, 4, Charsets.US_ASCII)
            val size = (wav[off + 4].toInt() and 0xFF) or ((wav[off + 5].toInt() and 0xFF) shl 8) or
                ((wav[off + 6].toInt() and 0xFF) shl 16) or ((wav[off + 7].toInt() and 0xFF) shl 24)
            if (id == "data") return wav.copyOfRange(off + 8, minOf(wav.size, off + 8 + size))
            off += 8 + size + (size and 1)
        }
        throw IllegalArgumentException("WAV has no data chunk")
    }
}

object HistoryActions {
    /**
     * Re-transcribes a failed entry from its saved audio using the normal cloud/fallback path.
     * On success the entry is updated and its audio deleted; on failure the new error is stored.
     * [done] runs on a background thread. Returns null if the entry has no audio to retry.
     */
    fun retry(
        ctx: Context,
        engine: TranscriptionEngine,
        entry: HistoryEntry,
        done: (TranscriptionEngine.Outcome) -> Unit,
    ): TranscriptionEngine.Job? {
        val path = entry.audioPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val store = HistoryStore.get(ctx)
        val pcm = try { WavReader.pcm(file.readBytes()) } catch (e: Exception) {
            store.updateError(entry.id, "Saved audio unreadable: ${e.message}")
            return null
        }
        return engine.run(pcm) { outcome ->
            when (outcome) {
                is TranscriptionEngine.Outcome.Success ->
                    store.markRetried(entry.id, outcome.text, outcome.rawText, outcome.source.label)
                is TranscriptionEngine.Outcome.Failure -> store.updateError(entry.id, outcome.error)
            }
            done(outcome)
        }
    }

    /** "5 min ago" style label. */
    fun relativeTime(createdAt: Long, now: Long = System.currentTimeMillis()): String {
        val mins = (now - createdAt) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins min ago"
            mins < 24 * 60 -> "${mins / 60} h ago"
            else -> "${mins / (24 * 60)} d ago"
        }
    }
}
