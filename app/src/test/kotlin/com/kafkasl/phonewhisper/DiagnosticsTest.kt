package com.kafkasl.phonewhisper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        Diagnostics.install(app)
        Diagnostics.clearAll(app)
        Diagnostics.markCrashesSeen(app)
    }

    @Test fun `app installs the crash handler`() {
        assertTrue(app is PhoneWhisperApp)
        assertEquals("CrashHandler", Thread.getDefaultUncaughtExceptionHandler()!!::class.simpleName)
    }

    @Test fun `installing twice does not stack handlers`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Diagnostics.install(app)
        val after = Thread.getDefaultUncaughtExceptionHandler()
        val prevField = after!!::class.java.getDeclaredField("previous").apply { isAccessible = true }
        assertNotSame(before, prevField.get(after))
    }

    @Test fun `uncaught exception writes a report and chains to the previous handler`() {
        var chained: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> chained = e }
        Diagnostics.install(app)
        Diagnostics.warn("Test", "something went sideways")

        val boom = IllegalStateException("boom")
        val t = Thread { throw boom }
        t.start(); t.join()

        assertSame(boom, chained)
        val report = Diagnostics.crashReports(app).single().readText()
        assertTrue(report.contains("java.lang.IllegalStateException: boom"))
        assertTrue(report.contains("Android:"))
        assertTrue("recent events are included", report.contains("something went sideways"))
        assertTrue(Diagnostics.hasUnseenCrash(app))
    }

    @Test fun `banner state clears once seen`() {
        Diagnostics.recordCrash(app, Thread.currentThread(), RuntimeException("x"), now = System.currentTimeMillis())
        assertTrue(Diagnostics.hasUnseenCrash(app))
        Diagnostics.markCrashesSeen(app)
        assertFalse(Diagnostics.hasUnseenCrash(app))
    }

    @Test fun `old crash files are pruned`() {
        val start = 1_700_000_000_000L
        repeat(13) { Diagnostics.recordCrash(app, Thread.currentThread(), RuntimeException("n$it"), now = start + it * 1000L) }
        val files = Diagnostics.crashReports(app)
        assertEquals(10, files.size)
        assertTrue("newest first", files.first().readText().contains("n12"))
    }

    @Test fun `report has settings, events and logcat`() {
        // The key itself is never read by the report, only hasApiKey (no Keystore under Robolectric).
        Diagnostics.error("Test", "failure", RuntimeException("inner"))
        val report = Diagnostics.buildReport(app, logcat = "log line\n")
        assertTrue(report.contains("API key set: false"))
        assertTrue(report.contains("Mode: cloud_fallback"))
        assertTrue(report.contains("E/Test: failure"))
        assertTrue(report.contains("RuntimeException: inner"))
        assertTrue(report.contains("log line"))
    }

    @Test fun `fit trims the longest section from the start`() {
        val out = Diagnostics.fit("H\n", listOf("short\n", "x".repeat(500) + "END"), 200)
        assertTrue(out.length <= 200)
        assertTrue(out.startsWith("H\nshort\n"))
        assertTrue(out.endsWith("END"))
        assertTrue(out.contains("trimmed"))
    }

    @Test fun `fit leaves small reports alone`() {
        assertEquals("H\nab", Diagnostics.fit("H\n", listOf("a", "b"), 100))
    }
}
