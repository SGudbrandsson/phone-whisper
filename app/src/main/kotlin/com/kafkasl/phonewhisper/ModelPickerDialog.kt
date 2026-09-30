package com.kafkasl.phonewhisper

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** Fetches `GET /models` for the current endpoint and caches the result in [AppSettings]. */
object ModelLoader {
    private val main = Handler(Looper.getMainLooper())

    /** [done] runs on the main thread. */
    fun refresh(settings: AppSettings, done: (ModelCatalog.FetchResult) -> Unit) {
        val base = settings.baseUrl
        Thread {
            val key = settings.apiKey // Keystore access: keep off the main thread
            ModelCatalog.fetch(base, key) { result ->
                if (result.models != null && Endpoints.normalizeBase(settings.baseUrl) == Endpoints.normalizeBase(base)) {
                    settings.cachedModels = result.models
                } else if (result.error != null) {
                    Diagnostics.warn("ModelLoader", "GET /models failed: ${result.error}")
                }
                main.post { done(result) }
            }
        }.start()
    }
}

/**
 * Searchable model list for one purpose (transcription or cleanup). Shows the cached list at
 * once and refreshes it from the server; "Show all" lifts the filter, "Type a name" is the
 * escape hatch for servers without /models.
 */
class ModelPickerDialog(
    private val activity: Activity,
    private val settings: AppSettings,
    private val title: String,
    private val current: String,
    private val filter: (List<RemoteModel>, Boolean) -> List<RemoteModel>,
    private val onPick: (String) -> Unit,
) {
    private var all: List<RemoteModel> = settings.cachedModels
    private var shown: List<RemoteModel> = emptyList()
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var showAll: CheckBox
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var listView: ListView
    private lateinit var dialog: AlertDialog

    fun show() {
        val dp = activity.resources.displayMetrics.density
        fun px(n: Int) = (n * dp).toInt()

        search = EditText(activity).apply {
            hint = "Search models"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        showAll = CheckBox(activity).apply {
            text = "Show all models"
            setOnCheckedChangeListener { _, _ -> render() }
        }
        status = TextView(activity).apply { setPadding(0, px(4), 0, px(4)) }
        adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_single_choice, mutableListOf())
        listView = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            adapter = this@ModelPickerDialog.adapter
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(320))
            setOnItemClickListener { _, _, pos, _ -> pick(shown[pos].id) }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(search); addView(showAll); addView(status); addView(listView)
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(content)
            .setNeutralButton("Type a name…", null)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Refresh", null)
            .create()
        dialog.setOnShowListener {
            // Keep the dialog open for Refresh; the default handler would dismiss it.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { load() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { dialog.dismiss(); typeName() }
        }
        dialog.show()
        render()
        load()
    }

    private fun load() {
        status.text = if (all.isEmpty()) "Loading models…" else "Refreshing…"
        ModelLoader.refresh(settings) { result ->
            if (!dialog.isShowing) return@refresh
            result.models?.let { all = it }
            render(error = result.error)
        }
    }

    private fun render(error: String? = null) {
        if (!::adapter.isInitialized) return
        val filtered = filter(all, showAll.isChecked)
        shown = ModelCatalog.search(filtered, search.text.toString())
        adapter.clear()
        adapter.addAll(shown.map { labelFor(it) })
        adapter.notifyDataSetChanged()
        val hidden = all.size - filtered.size
        status.text = when {
            error != null -> "$error${if (all.isNotEmpty()) " (showing saved list)" else ""}"
            all.isEmpty() -> "Loading models…"
            filtered.isEmpty() -> "No matching models on this server. Tick \"Show all models\" or type a name."
            hidden > 0 && !showAll.isChecked -> "${filtered.size} models · $hidden others hidden"
            else -> "${filtered.size} models"
        }
        listView.clearChoices()
        shown.indexOfFirst { it.id == current }.takeIf { it >= 0 }?.let { listView.setItemChecked(it, true) }
    }

    private fun labelFor(m: RemoteModel) = when (m.kind) {
        RemoteModel.Kind.STT, RemoteModel.Kind.CHAT -> m.id
        RemoteModel.Kind.AUDIO_CHAT -> "${m.id}  · audio chat"
        RemoteModel.Kind.OTHER -> "${m.id}  · other"
    }

    private fun pick(id: String) {
        onPick(id)
        dialog.dismiss()
    }

    private fun typeName() {
        val input = EditText(activity).apply {
            setText(current)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onPick) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
