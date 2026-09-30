package com.kafkasl.phonewhisper

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * In-app crash reports and a persistent log of non-fatal problems, so a report can be copied
 * from the phone without adb. Crashes go to `filesDir/crashes/`, events to
 * `filesDir/diagnostics/events.log`. Nothing here records transcript text or the API key.
 */
object Diagnostics {
    private const val TAG = "Diagnostics"
    private const val MAX_CRASH_FILES = 10
    private const val MAX_EVENTS_BYTES = 128 * 1024L
    const val LOGCAT_LINES = 300
    /** Keeps shared text below what clipboard and share intents handle reliably. */
    const val MAX_REPORT_CHARS = 150_000
    private const val PREFS_SEEN = "crash_seen_until"

    @Volatile private var appContext: Context? = null

    /** Call once from [Application.onCreate]. Chains to the previous handler after writing. */
    fun install(ctx: Context) {
        appContext = ctx.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // Re-installing (e.g. a new Application per Robolectric test) must not stack handlers.
        val previous = if (current is CrashHandler) current.previous else current
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(previous))
    }

    private class CrashHandler(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                appContext?.let { recordCrash(it, t, e) }
            } catch (_: Throwable) {
                // Never let the reporter hide the original crash.
            }
            previous?.uncaughtException(t, e)
        }
    }

    // --- Non-fatal events ---

    fun info(tag: String, msg: String) { Log.i(tag, msg); append("I", tag, msg, null) }
    fun warn(tag: String, msg: String, t: Throwable? = null) { Log.w(tag, msg, t); append("W", tag, msg, t) }
    fun error(tag: String, msg: String, t: Throwable? = null) { Log.e(tag, msg, t); append("E", tag, msg, t) }

    private val eventLock = Any()

    private fun append(level: String, tag: String, msg: String, t: Throwable?) {
        val ctx = appContext ?: return
        try {
            synchronized(eventLock) {
                val file = eventsFile(ctx)
                file.parentFile?.mkdirs()
                if (file.length() > MAX_EVENTS_BYTES) {
                    // Keep one older generation so a report still has some history after rotation.
                    file.renameTo(File(file.parentFile, "events.1.log"))
                }
                file.appendText(formatEvent(System.currentTimeMillis(), level, tag, msg, t))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write diagnostics event", e)
        }
    }

    internal fun formatEvent(time: Long, level: String, tag: String, msg: String, t: Throwable?): String {
        val sb = StringBuilder().append(timestamp(time)).append(' ').append(level).append('/').append(tag)
            .append(": ").append(msg).append('\n')
        if (t != null) sb.append(stackTrace(t).prependIndent("    ")).append('\n')
        return sb.toString()
    }

    fun recentEvents(ctx: Context): String {
        val dir = eventsFile(ctx).parentFile ?: return ""
        return listOf(File(dir, "events.1.log"), eventsFile(ctx))
            .filter { it.exists() }
            .joinToString("") { it.readText() }
    }

    // --- Crashes ---

    fun recordCrash(ctx: Context, thread: Thread, e: Throwable, now: Long = System.currentTimeMillis()): File {
        val dir = crashDir(ctx).apply { mkdirs() }
        val file = File(dir, "crash-${fileStamp(now)}.txt")
        val text = buildString {
            append("=== Phone Whisper crash ===\n")
            append("Time: ").append(timestamp(now)).append('\n')
            append("Thread: ").append(thread.name).append('\n')
            append(deviceInfo(ctx)).append('\n')
            append("--- Stack trace ---\n").append(stackTrace(e)).append('\n')
            append("--- Recent events ---\n").append(recentEvents(ctx).takeLast(20_000)).append('\n')
            append("--- Logcat (this process, last $LOGCAT_LINES lines) ---\n").append(readOwnLogcat())
        }
        file.writeText(text)
        crashDir(ctx).listFiles()?.sortedByDescending { it.name }?.drop(MAX_CRASH_FILES)?.forEach { it.delete() }
        return file
    }

    /** Newest first. */
    fun crashReports(ctx: Context): List<File> =
        crashDir(ctx).listFiles { f -> f.name.startsWith("crash-") }?.sortedByDescending { it.name } ?: emptyList()

    /** True when a crash was recorded after the user last looked at or dismissed the banner. */
    fun hasUnseenCrash(ctx: Context): Boolean {
        val newest = crashReports(ctx).firstOrNull() ?: return false
        return newest.lastModified() > prefs(ctx).getLong(PREFS_SEEN, 0)
    }

    fun markCrashesSeen(ctx: Context) {
        val newest = crashReports(ctx).firstOrNull()?.lastModified() ?: return
        prefs(ctx).edit().putLong(PREFS_SEEN, newest).apply()
    }

    fun clearAll(ctx: Context) {
        crashDir(ctx).listFiles()?.forEach { it.delete() }
        eventsFile(ctx).parentFile?.listFiles()?.forEach { it.delete() }
    }

    // --- Report ---

    /** Everything useful for debugging, as one pasteable block. Call off the main thread. */
    fun buildReport(ctx: Context, logcat: String = readOwnLogcat()): String {
        val crashes = crashReports(ctx)
        val header = buildString {
            append("=== Phone Whisper diagnostics ===\n")
            append("Generated: ").append(timestamp(System.currentTimeMillis())).append('\n')
            append(deviceInfo(ctx)).append('\n')
            append(settingsSummary(ctx)).append('\n')
            append("Crash reports: ").append(crashes.size).append('\n')
        }
        val sections = mutableListOf<String>()
        crashes.take(3).forEach { sections += "\n" + it.readText() }
        sections += "\n--- Recent events ---\n" + recentEvents(ctx).ifBlank { "(none)\n" }
        sections += "\n--- Logcat (this process, last $LOGCAT_LINES lines) ---\n" + logcat
        return fit(header, sections, MAX_REPORT_CHARS)
    }

    /**
     * Joins [header] and [sections], trimming the start of the longest section first so the
     * newest lines survive, until the result fits in [max] characters.
     */
    internal fun fit(header: String, sections: List<String>, max: Int): String {
        val parts = sections.toMutableList()
        var total = header.length + parts.sumOf { it.length }
        while (total > max) {
            val i = parts.indices.maxByOrNull { parts[it].length } ?: break
            val over = total - max
            val keep = (parts[i].length - over - 40).coerceAtLeast(0)
            val trimmed = "…(trimmed)\n" + parts[i].takeLast(keep)
            if (trimmed.length >= parts[i].length) break
            total -= parts[i].length - trimmed.length
            parts[i] = trimmed
        }
        return header + parts.joinToString("")
    }

    fun deviceInfo(ctx: Context): String {
        val (name, code) = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.versionName to pi.longVersionCode
        } catch (_: Exception) { "?" to 0L }
        return "App: ${ctx.packageName} $name ($code)\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n" +
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
    }

    private fun settingsSummary(ctx: Context): String = try {
        val s = AppSettings(ctx)
        "Mode: ${s.mode.key}\n" +
            "Endpoint: ${Endpoints.normalizeBase(s.baseUrl)}\n" +
            "API key set: ${s.hasApiKey}\n" +
            "STT model: ${s.sttModel}\n" +
            "Language: ${s.language.ifBlank { "auto" }}\n" +
            "Cleanup: ${if (s.usePostProcessing) "on (${s.chatModel})" else "off"}\n" +
            "Local models: ${LocalTranscriber.availableModels(ctx).joinToString().ifBlank { "none" }}\n" +
            "Accessibility service running: ${WhisperAccessibilityService.instance != null}"
    } catch (e: Exception) {
        "Settings unavailable: ${e.message}"
    }

    /** An app may read its own log lines (Android 4.1+). Returns an explanation on failure. */
    fun readOwnLogcat(lines: Int = LOGCAT_LINES): String = try {
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "-t", "$lines")
            .redirectErrorStream(true).start()
        var out = ""
        val reader = Thread { out = proc.inputStream.bufferedReader().readText() }.apply { start() }
        reader.join(TimeUnit.SECONDS.toMillis(3))
        if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroy()
        out.ifBlank { "(logcat returned nothing)\n" }
    } catch (e: Exception) {
        "(logcat unavailable: ${e.message})\n"
    }

    // --- helpers ---

    private fun crashDir(ctx: Context) = File(ctx.filesDir, "crashes")
    private fun eventsFile(ctx: Context) = File(File(ctx.filesDir, "diagnostics"), "events.log")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun stackTrace(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    private fun timestamp(ms: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(ms))

    private fun fileStamp(ms: Long) =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
