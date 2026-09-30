package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

/** Shows crash reports and recent logs as one block of text, with Copy and Share. */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var body: TextView
    private lateinit var summary: TextView
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics.markCrashesSeen(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
            setPadding(dp(16), dp(48), dp(16), 0)
        }
        root.addView(TextView(this).apply {
            text = "Diagnostics"
            textSize = 32f
            setPadding(dp(8), 0, 0, dp(4))
        })
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), 0, dp(8), dp(12))
            text = "Collecting…"
        }
        root.addView(summary)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, style: Int, onClick: () -> Unit) = MaterialButton(this, null, style).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
        }
        buttons.addView(button("Copy", com.google.android.material.R.attr.materialButtonStyle) { copy() })
        buttons.addView(button("Share", com.google.android.material.R.attr.materialButtonOutlinedStyle) { share() })
        buttons.addView(button("Clear", com.google.android.material.R.attr.borderlessButtonStyle) { confirmClear() })
        root.addView(buttons)

        body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(8), dp(12), dp(8), dp(24))
        }
        root.addView(ScrollView(this).apply {
            addView(HorizontalScrollView(context).apply { addView(body) })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "Test crash reporting"
            setOnClickListener { confirmTestCrash() }
        })
        setContentView(root)
        load()
    }

    private fun load() {
        thread {
            val text = Diagnostics.buildReport(this)
            val crashes = Diagnostics.crashReports(this).size
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                report = text
                body.text = text
                summary.text = (if (crashes == 0) "No crashes recorded." else "$crashes crash report${if (crashes == 1) "" else "s"}.") +
                    " Copy or share this to get help. It has no API key or transcript text."
            }
        }
    }

    private fun copy() {
        if (report.isEmpty()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Phone Whisper diagnostics", report))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        if (report.isEmpty()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Phone Whisper diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(send, "Share diagnostics"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Delete crash reports and logs?")
            .setPositiveButton("Delete") { _, _ -> Diagnostics.clearAll(this); load() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmTestCrash() {
        AlertDialog.Builder(this)
            .setTitle("Crash the app now?")
            .setMessage("Checks that crash reports work. Reopen Phone Whisper afterwards and you should see the crash banner.")
            .setPositiveButton("Crash") { _, _ -> throw RuntimeException("Test crash from the diagnostics screen") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attrColor(attr: Int) = TypedValue().also { theme.resolveAttribute(attr, it, true) }.let {
        if (it.resourceId != 0) getColor(it.resourceId) else it.data
    }
}
