package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kafkasl.phonewhisper.ui.DiagnosticsScreen
import com.kafkasl.phonewhisper.ui.PhoneWhisperTheme
import kotlin.concurrent.thread

/** Crash reports and recent logs as one block of text, with Copy and Share. */
class DiagnosticsActivity : ComponentActivity() {

    private var report by mutableStateOf<String?>(null)
    private var crashCount by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Diagnostics.markCrashesSeen(this)
        setContent {
            PhoneWhisperTheme {
                DiagnosticsScreen(
                    report = report,
                    crashCount = crashCount,
                    onBack = ::finish,
                    onCopy = ::copy,
                    onShare = ::share,
                    onClear = { Diagnostics.clearAll(this); load() },
                    onTestCrash = { throw RuntimeException("Test crash from the diagnostics screen") },
                )
            }
        }
        load()
    }

    private fun load() {
        report = null
        thread {
            val text = Diagnostics.buildReport(this)
            val count = Diagnostics.crashReports(this).size
            runOnUiThread { report = text; crashCount = count }
        }
    }

    private fun copy() {
        val text = report ?: return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Phone Whisper diagnostics", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = report ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Phone Whisper diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Share diagnostics"))
    }
}
