package com.kafkasl.phonewhisper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kafkasl.phonewhisper.ModelCatalog
import com.kafkasl.phonewhisper.RemoteModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(picker: PickerUi, on: (SettingsEvent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { on(SettingsEvent.ClosePicker) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ModelPickerContent(picker, on)
    }
}

/** The sheet's body, separate so it can be rendered in screenshot tests without a window. */
@Composable
fun ModelPickerContent(picker: PickerUi, on: (SettingsEvent) -> Unit, initialQuery: String = "", initialShowAll: Boolean = false) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var showAll by rememberSaveable { mutableStateOf(initialShowAll) }
    var typing by remember { mutableStateOf(false) }

    val filtered = if (picker.target == PickerTarget.TRANSCRIPTION) ModelCatalog.forTranscription(picker.models, showAll)
        else ModelCatalog.forCleanup(picker.models, showAll)
    val shown = ModelCatalog.search(filtered, query)
    val hidden = picker.models.size - filtered.size
    val kindName = if (picker.target == PickerTarget.TRANSCRIPTION) "speech-to-text" else "chat"

    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(picker.target.title, style = MaterialTheme.typography.titleLarge)
                Text("Current: ${picker.current}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (picker.loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
            else IconButton(onClick = { on(SettingsEvent.RefreshPicker) }) { Icon(Icons.Default.Refresh, "Refresh list") }
        }

        OutlinedTextField(
            query, { query = it },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") } },
            placeholder = { Text("Search models") },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = showAll, onClick = { showAll = !showAll },
                label = { Text("Show all models") },
                leadingIcon = if (showAll) ({ Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            )
            AssistChip(onClick = { typing = true }, label = { Text("Type a name") })
        }

        val status = when {
            picker.error != null && picker.models.isEmpty() -> picker.error
            picker.error != null -> "${picker.error} · showing saved list"
            picker.models.isEmpty() && picker.loading -> "Loading models…"
            picker.models.isEmpty() -> "This server listed no models. Use \"Type a name\"."
            filtered.isEmpty() -> "No $kindName models found. Try \"Show all models\"."
            !showAll && hidden > 0 -> "${filtered.size} $kindName models · $hidden others hidden"
            else -> "${shown.size} models"
        }
        Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (picker.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(shown, key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(m.id == picker.current, role = Role.RadioButton) { on(SettingsEvent.PickModel(picker.target, m.id)) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.id, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        kindLabel(m, picker.target)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.size(12.dp))
                    RadioButton(selected = m.id == picker.current, onClick = null)
                }
            }
        }
    }

    if (typing) {
        var name by remember { mutableStateOf(picker.current) }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text(picker.target.title) },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Model name") },
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    typing = false
                    on(SettingsEvent.PickModel(picker.target, name.trim()))
                }) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

private fun kindLabel(m: RemoteModel, target: PickerTarget): String? = when (m.kind) {
    RemoteModel.Kind.STT -> if (target == PickerTarget.TRANSCRIPTION) null else "Speech-to-text"
    RemoteModel.Kind.CHAT -> if (target == PickerTarget.CLEANUP) null else "Chat model: can't transcribe"
    RemoteModel.Kind.AUDIO_CHAT -> if (target == PickerTarget.CLEANUP) "Accepts audio" else "Audio chat model: not supported for transcription yet"
    RemoteModel.Kind.OTHER -> "Not speech-to-text or chat"
}
