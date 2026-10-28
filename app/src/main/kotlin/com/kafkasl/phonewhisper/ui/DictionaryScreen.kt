package com.kafkasl.phonewhisper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.kafkasl.phonewhisper.VocabEntry
import com.kafkasl.phonewhisper.Vocabulary

data class DictionaryUiState(
    val entries: List<VocabEntry> = emptyList(),
    val sendHints: Boolean = true,
    /** Model name when the current endpoint refused hints, else null. */
    val hintsRejectedFor: String? = null,
)

/** -1 = new entry. */
private data class Editing(val index: Int, val entry: VocabEntry)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(
    state: DictionaryUiState,
    onBack: () -> Unit,
    onSave: (index: Int, entry: VocabEntry) -> Unit,
    onDelete: (index: Int) -> Unit,
    onSendHints: (Boolean) -> Unit,
    onRetryHints: () -> Unit,
    initialQuery: String = "",
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var editing by remember { mutableStateOf<Editing?>(null) }
    var query by remember { mutableStateOf(initialQuery) }
    val shown = state.entries.withIndex().filter { (_, e) ->
        query.isBlank() || e.term.contains(query, true) || e.heardAs.any { it.contains(query, true) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Dictionary") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Editing(-1, VocabEntry("")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Add word") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Text(
                    "Names and words you want spelled your way. They're fixed in every transcript, " +
                        "offline too, and can be sent to the cloud model as hints.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            item {
                SettingsGroup("Cloud hints") {
                    SwitchRow("Send words to the cloud model", state.sendHints, onSendHints,
                        subtitle = "Helps it hear names right. Up to ${Vocabulary.MAX_HINT_CHARS} characters are sent")
                    state.hintsRejectedFor?.let { model ->
                        GroupDivider()
                        SettingsRow(
                            "Hints turned off for $model",
                            subtitle = "This server refused them, so only local fixes apply. Tap to try again",
                            subtitleColor = MaterialTheme.colorScheme.error,
                            onClick = onRetryHints,
                        )
                    }
                }
            }
            if (state.entries.size > 6) item {
                OutlinedTextField(
                    query, { query = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search ${state.entries.size} words") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (state.entries.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No words yet", style = MaterialTheme.typography.titleMedium)
                    Text("For example a company name, a colleague, or a word the model keeps getting wrong.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            itemsIndexed(shown, key = { _, iv -> iv.index }) { _, (index, entry) ->
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clickable { editing = Editing(index, entry) },
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Text(entry.term, style = MaterialTheme.typography.titleMedium)
                        if (entry.heardAs.isNotEmpty()) {
                            Spacer(Modifier.size(6.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Fixes", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.CenterVertically))
                                entry.heardAs.forEach { variant ->
                                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(variant, style = MaterialTheme.typography.labelLarge,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { e ->
        EntryDialog(
            e,
            existing = state.entries.filterIndexed { i, _ -> i != e.index }.map { it.term.lowercase() }.toSet(),
            onDismiss = { editing = null },
            onSave = { onSave(e.index, it); editing = null },
            onDelete = { onDelete(e.index); editing = null },
        )
    }
}

@Composable
private fun EntryDialog(
    editing: Editing, existing: Set<String>,
    onDismiss: () -> Unit, onSave: (VocabEntry) -> Unit, onDelete: () -> Unit,
) {
    var term by remember { mutableStateOf(editing.entry.term) }
    var variants by remember { mutableStateOf(editing.entry.heardAs.joinToString(", ")) }
    val duplicate = term.trim().lowercase() in existing
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing.index < 0) "Add word" else "Edit word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    term, { term = it },
                    label = { Text("Word or phrase") },
                    placeholder = { Text("e.g. AIHub") },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = { if (duplicate) Text("Already in your dictionary") else Text("Written exactly like this") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    variants, { variants = it },
                    label = { Text("Also heard as (optional)") },
                    placeholder = { Text("e.g. ai hub, eye hub") },
                    supportingText = { Text("Comma-separated. These get replaced with the word") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = term.isNotBlank() && !duplicate,
                onClick = { onSave(VocabEntry(term.trim(), Vocabulary.parseVariants(variants).filter { it != term.trim() })) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (editing.index >= 0) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
