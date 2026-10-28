package com.kafkasl.phonewhisper.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import com.kafkasl.phonewhisper.AppSettings
import com.kafkasl.phonewhisper.HistoryActions
import com.kafkasl.phonewhisper.HistoryEntry
import com.kafkasl.phonewhisper.HistoryStore
import com.kafkasl.phonewhisper.TranscriptionEngine
import com.kafkasl.phonewhisper.WhisperAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

data class HistoryItemUi(val entry: HistoryEntry, val appLabel: String?)

data class HistoryUiState(
    val items: List<HistoryItemUi> = emptyList(),
    val loaded: Boolean = false,
    val retrying: Set<Long> = emptySet(),
    val retentionDays: Int = 7,
    val message: String? = null,
)

/** Loads history and runs retries. Lives across rotation, so a retry isn't lost or leaked. */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val store = HistoryStore.get(app)
    private val settings = AppSettings(app)
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state
    private val onChange: () -> Unit = { main.post { load() } }
    private val labels = HashMap<String, String>()

    /** The overlay service's engine when running, so an already-loaded local model is reused. */
    private var ownEngine: TranscriptionEngine? = null
    private val engine: TranscriptionEngine by lazy {
        WhisperAccessibilityService.instance?.engine ?: TranscriptionEngine(ctx).also { ownEngine = it }
    }

    init {
        HistoryStore.addListener(onChange)
        thread { store.prune(settings.retentionDays); main.post { load() } }
    }

    fun load() {
        thread {
            val items = store.recent().map { HistoryItemUi(it, label(it.appPackage)) }
            main.post { _state.update { it.copy(items = items, loaded = true, retentionDays = settings.retentionDays) } }
        }
    }

    private fun label(pkg: String?): String? {
        pkg ?: return null
        return synchronized(labels) {
            labels.getOrPut(pkg) {
                try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg.substringAfterLast('.') }
            }
        }
    }

    fun tap(e: HistoryEntry) = when {
        e.status == HistoryEntry.Status.OK && e.text != null -> copy(e.text, "Copied")
        e.canRetry -> retry(e)
        else -> say("Audio for this recording wasn't kept")
    }

    fun copy(text: String, msg: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("phonewhisper", text))
        say(msg)
    }

    fun retry(e: HistoryEntry) {
        if (e.id in _state.value.retrying) return
        _state.update { it.copy(retrying = it.retrying + e.id) }
        thread {
            val job = HistoryActions.retry(ctx, engine, e) { outcome ->
                main.post {
                    _state.update { it.copy(retrying = it.retrying - e.id) }
                    when (outcome) {
                        is TranscriptionEngine.Outcome.Success -> copy(outcome.text, "Retry worked, text copied")
                        is TranscriptionEngine.Outcome.Failure -> say("Retry failed: ${outcome.error}")
                    }
                }
            }
            if (job == null) main.post { _state.update { it.copy(retrying = it.retrying - e.id) }; say("Saved audio is missing") }
        }
    }

    fun delete(e: HistoryEntry) = thread { store.delete(e.id) }
    fun clearAll() = thread { store.clearAll() }
    fun messageShown() = _state.update { it.copy(message = null) }
    private fun say(msg: String) = _state.update { it.copy(message = msg) }

    override fun onCleared() {
        HistoryStore.removeListener(onChange)
        // Free a local model this screen loaded itself; the service's engine is left alone.
        ownEngine?.let { e -> thread { e.unloadLocalModel() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onTap: (HistoryEntry) -> Unit,
    onCopy: (String, String) -> Unit,
    onRetry: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onClearAll: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onMessageShown() }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("History") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (state.items.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                val failed = state.items.count { it.entry.status == HistoryEntry.Status.FAILED }
                val clear = if (state.retentionDays == 0) "Kept until you clear it"
                    else "Auto-cleared after ${state.retentionDays} day${if (state.retentionDays == 1) "" else "s"}"
                Text(
                    buildString {
                        append("${state.items.size} item${if (state.items.size == 1) "" else "s"}")
                        if (failed > 0) append(" · $failed failed")
                        append(" · $clear")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            if (state.loaded && state.items.isEmpty()) item {
                Text("No dictations yet. They show up here after you use the mic bubble.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(48.dp))
            }
            items(state.items, key = { it.entry.id }) { item ->
                HistoryCard(item, item.entry.id in state.retrying, onTap, onCopy, onRetry, onDelete)
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all history?") },
        text = { Text("Deletes every saved transcript and failed recording.") },
        confirmButton = { TextButton(onClick = { onClearAll(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    item: HistoryItemUi, retrying: Boolean,
    onTap: (HistoryEntry) -> Unit, onCopy: (String, String) -> Unit,
    onRetry: (HistoryEntry) -> Unit, onDelete: (HistoryEntry) -> Unit,
) {
    val e = item.entry
    val failed = e.status == HistoryEntry.Status.FAILED
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 16.dp)) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (failed) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onTap(e) }, onLongClick = { menu = true }),
        ) {
            Column(Modifier.padding(16.dp)) {
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.createdAt))
                Text(
                    listOfNotNull(time, item.appLabel, e.source, "${e.durationMs / 1000}s").joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                if (failed) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(e.error ?: "Failed", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else Text(e.text.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)

                if (failed) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    when {
                        retrying -> {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Retrying…", style = MaterialTheme.typography.labelLarge)
                        }
                        e.canRetry -> FilledTonalButton(onClick = { onRetry(e) }) { Text("Retry") }
                        else -> Text("Audio not kept", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            e.text?.let { t -> DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; onCopy(t, "Copied") }) }
            e.rawText?.let { raw ->
                DropdownMenuItem(text = { Text("Copy raw transcript") }, onClick = { menu = false; onCopy(raw, "Raw transcript copied") })
            }
            if (e.canRetry) DropdownMenuItem(text = { Text("Retry") }, onClick = { menu = false; onRetry(e) })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete(e) })
        }
    }
}
