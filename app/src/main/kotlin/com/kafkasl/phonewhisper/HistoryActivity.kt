package com.kafkasl.phonewhisper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

/** Lists past dictations: tap to copy, retry failed ones, long-press to copy raw text or delete. */
class HistoryActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { HistoryStore.get(this) }
    private val settings by lazy { AppSettings(this) }
    private var entries: List<HistoryEntry> = emptyList()
    private val retrying = mutableSetOf<Long>()
    private lateinit var adapter: HistoryAdapter
    private lateinit var empty: TextView
    private lateinit var subtitle: TextView
    private val onChange: () -> Unit = { handler.post { load() } }

    /** Uses the overlay service's engine when running, so a loaded local model is reused. */
    private val engine: TranscriptionEngine by lazy {
        WhisperAccessibilityService.instance?.engine ?: TranscriptionEngine(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(48), dp(12), dp(4))
            addView(TextView(context).apply {
                text = "History"
                textSize = 32f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Clear all"
                setOnClickListener { confirmClearAll() }
            })
        }
        root.addView(headerRow)

        subtitle = TextView(this).apply {
            textSize = 14f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), 0, dp(24), dp(12))
        }
        root.addView(subtitle)

        empty = TextView(this).apply {
            text = "No dictations yet."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setPadding(dp(24), dp(48), dp(24), dp(48))
            visibility = View.GONE
        }
        root.addView(empty)

        adapter = HistoryAdapter()
        root.addView(ListView(this).apply {
            adapter = this@HistoryActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> onTap(entries[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onLongPress(entries[pos]); true }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        HistoryStore.addListener(onChange)
        thread { store.prune(settings.retentionDays); handler.post { load() } }
    }

    override fun onStop() {
        HistoryStore.removeListener(onChange)
        super.onStop()
    }

    private fun load() {
        thread {
            val list = store.recent()
            handler.post {
                entries = list
                adapter.notifyDataSetChanged()
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                val failed = list.count { it.status == HistoryEntry.Status.FAILED }
                val clear = if (settings.retentionDays == 0) "Kept until you clear it"
                    else "Auto-cleared after ${settings.retentionDays} day${if (settings.retentionDays == 1) "" else "s"}"
                subtitle.text = buildString {
                    append("${list.size} item${if (list.size == 1) "" else "s"}")
                    if (failed > 0) append(" · $failed failed")
                    append(" · $clear")
                }
            }
        }
    }

    private fun onTap(e: HistoryEntry) {
        when {
            e.status == HistoryEntry.Status.OK && e.text != null -> copy(e.text, "Copied")
            e.canRetry -> retry(e)
            else -> toast("Audio for this recording wasn't kept")
        }
    }

    private fun onLongPress(e: HistoryEntry) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        e.text?.let { t -> actions += "Copy" to { copy(t, "Copied") } }
        e.rawText?.let { raw -> actions += "Copy raw transcript (before cleanup)" to { copy(raw, "Raw transcript copied") } }
        if (e.canRetry) actions += "Retry" to { retry(e) }
        actions += "Delete" to { thread { store.delete(e.id) } }
        AlertDialog.Builder(this)
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun retry(e: HistoryEntry) {
        if (!retrying.add(e.id)) return
        adapter.notifyDataSetChanged()
        thread {
            val job = HistoryActions.retry(this, engine, e) { outcome ->
                handler.post {
                    retrying.remove(e.id)
                    when (outcome) {
                        is TranscriptionEngine.Outcome.Success -> copy(outcome.text, "Retry succeeded — copied")
                        is TranscriptionEngine.Outcome.Failure -> toast("Retry failed: ${outcome.error}")
                    }
                    load()
                }
            }
            if (job == null) handler.post { retrying.remove(e.id); toast("Saved audio is missing"); load() }
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("Clear all history?")
            .setMessage("Deletes every saved transcript and failed recording.")
            .setPositiveButton("Clear") { _, _ -> thread { store.clearAll() } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copy(text: String, msg: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("phonewhisper", text))
        toast(msg)
    }

    private inner class HistoryAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = entries[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val e = entries[position]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@HistoryActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(14), dp(24), dp(14))
                addView(TextView(context).apply { tag = "meta"; textSize = 12f })
                addView(TextView(context).apply {
                    tag = "body"; textSize = 16f; maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                })
                addView(TextView(context).apply { tag = "action"; textSize = 13f; setPadding(0, dp(4), 0, 0) })
            }
            val meta = row.findViewWithTag<TextView>("meta")
            val body = row.findViewWithTag<TextView>("body")
            val action = row.findViewWithTag<TextView>("action")

            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.createdAt))
            val parts = listOfNotNull(time, appLabel(e.appPackage), e.source, "${e.durationMs / 1000}s")
            meta.text = parts.joinToString(" · ")
            meta.setTextColor(attrColor(android.R.attr.textColorSecondary))

            val failed = e.status == HistoryEntry.Status.FAILED
            body.text = if (failed) e.error ?: "Failed" else e.text
            body.setTextColor(if (failed) 0xFFD93025.toInt() else attrColor(android.R.attr.textColorPrimary))

            action.setTextColor(attrColor(com.google.android.material.R.attr.colorPrimary))
            action.text = when {
                e.id in retrying -> "Retrying…"
                failed && e.canRetry -> "Tap to retry"
                failed -> "Audio not kept"
                else -> "Tap to copy"
            }
            return row
        }
    }

    private val labelCache = mutableMapOf<String, String>()

    private fun appLabel(pkg: String?): String? {
        pkg ?: return null
        return labelCache.getOrPut(pkg) {
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            catch (_: Exception) { pkg.substringAfterLast('.') }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }
}
