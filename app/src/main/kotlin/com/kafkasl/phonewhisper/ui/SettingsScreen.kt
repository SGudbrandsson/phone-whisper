package com.kafkasl.phonewhisper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kafkasl.phonewhisper.HistoryPolicy
import com.kafkasl.phonewhisper.R
import com.kafkasl.phonewhisper.TranscriptionMode

private enum class Dialog { MODE, LANGUAGE, SERVICE, CUSTOM_URL, API_KEY, PROMPT, RETENTION, CLEAR_HISTORY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    on: (SettingsEvent) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    var dialog by rememberSaveable { mutableStateOf<Dialog?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("Phone Whisper") },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.crashed) item { CrashBanner(on) }
            item { StatusCard(state, on) }

            item {
                SettingsGroup("Dictation") {
                    SettingsRow("Mode", subtitle = state.mode.label, onClick = { dialog = Dialog.MODE })
                    GroupDivider()
                    SettingsRow("Language", subtitle = languageLabel(state.language),
                        onClick = { dialog = Dialog.LANGUAGE })
                    GroupDivider()
                    SwitchRow("Show bubble only when typing", state.showOnlyWhenTyping, { on(SettingsEvent.SetOnlyWhenTyping(it)) },
                        subtitle = "Hidden unless the keyboard is up or a text field is focused")
                }
            }

            if (state.showCloud) item {
                SettingsGroup("Cloud endpoint") {
                    SettingsRow("Service", subtitle = "${state.serviceName} · ${state.baseUrl}", onClick = { dialog = Dialog.SERVICE })
                    GroupDivider()
                    SettingsRow("API key",
                        subtitle = state.apiKeyHint?.let { "$it · stored encrypted" } ?: "Not set",
                        onClick = { dialog = Dialog.API_KEY })
                    if (state.usesCloud) {
                        GroupDivider()
                        SettingsRow("Transcription model", subtitle = state.sttModel,
                            onClick = { on(SettingsEvent.OpenPicker(PickerTarget.TRANSCRIPTION)) }) {
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    GroupDivider()
                    ConnectionRow(state, on)
                }
            }

            if (state.showLocal) item {
                SettingsGroup(if (state.mode == TranscriptionMode.LOCAL_ONLY) "On-device model" else "Offline model") {
                    state.localModels.forEachIndexed { i, m ->
                        if (i > 0) GroupDivider()
                        LocalModelRow(m, on)
                    }
                }
            }

            item {
                SettingsGroup("Cleanup") {
                    SwitchRow("Clean up transcripts", state.cleanupOn, { on(SettingsEvent.SetCleanup(it)) },
                        subtitle = "Fixes punctuation and names with a chat model. Skipped offline")
                    if (state.cleanupOn) {
                        PromptPreset.entries.forEach { p ->
                            GroupDivider()
                            SettingsRow(
                                p.title,
                                subtitle = if (p == PromptPreset.CUSTOM && state.customPrompt.isNotBlank()) state.customPrompt.replace('\n', ' ') else p.subtitle,
                                subtitleMaxLines = 1,
                                modifier = Modifier.selectable(state.prompt == p, role = Role.RadioButton) { on(SettingsEvent.SetPrompt(p)) },
                            ) {
                                if (p == PromptPreset.CUSTOM) {
                                    TextButton(onClick = { dialog = Dialog.PROMPT }) { Text("Edit") }
                                }
                                RadioButton(selected = state.prompt == p, onClick = null)
                            }
                        }
                        GroupDivider()
                        SettingsRow("Cleanup model", subtitle = state.chatModel,
                            onClick = { on(SettingsEvent.OpenPicker(PickerTarget.CLEANUP)) }) {
                            Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                SettingsGroup("History") {
                    SettingsRow("View history",
                        subtitle = "Copy past dictations, retry failed ones. Long-press the bubble for recent ones",
                        onClick = { on(SettingsEvent.OpenHistory) })
                    GroupDivider()
                    SwitchRow("Save history", state.historyOn, { on(SettingsEvent.SetHistory(it)) },
                        subtitle = "Transcripts, plus audio of failed dictations for retry")
                    if (state.historyOn) {
                        GroupDivider()
                        SettingsRow("Auto-clear", subtitle = HistoryPolicy.label(state.retentionDays), onClick = { dialog = Dialog.RETENTION })
                        GroupDivider()
                        SettingsRow("Clear history now", subtitle = "Deletes all transcripts and saved audio",
                            onClick = { dialog = Dialog.CLEAR_HISTORY })
                    }
                }
            }

            item {
                SettingsGroup("Diagnostics") {
                    SettingsRow("Crash reports & diagnostics",
                        subtitle = "Copy or share logs when something goes wrong", onClick = { on(SettingsEvent.OpenDiagnostics) })
                }
            }

            item {
                Text(
                    "Phone Whisper ${state.version}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                )
            }
        }
    }

    state.picker?.let { ModelPickerSheet(it, on) }

    when (dialog) {
        Dialog.MODE -> ChoiceDialog("Transcription mode", TranscriptionMode.entries, state.mode,
            label = { it.label }, detail = { it.description }, onDismiss = { dialog = null }) { on(SettingsEvent.SetMode(it)) }
        Dialog.LANGUAGE -> LanguageDialog(state.language, onDismiss = { dialog = null }) { on(SettingsEvent.SetLanguage(it)) }
        Dialog.SERVICE -> ServiceDialog(state, onDismiss = { dialog = null }, onCustom = { dialog = Dialog.CUSTOM_URL }) {
            on(SettingsEvent.ChooseService(it))
        }
        Dialog.CUSTOM_URL -> TextDialog("Server URL", state.baseUrl, "https://your-server/v1",
            note = "Any OpenAI-compatible server (LiteLLM, speaches, …). http:// sends audio and key unencrypted.",
            keyboard = KeyboardType.Uri, onDismiss = { dialog = null }) { on(SettingsEvent.SetCustomUrl(it)) }
        Dialog.API_KEY -> TextDialog("API key for ${state.serviceName}", "", "Paste API key",
            note = "Leave blank if your server needs none. Stored encrypted.", password = true,
            extra = if (state.apiKeyHint != null) ("Remove" to { on(SettingsEvent.SetApiKey("")) }) else null,
            onDismiss = { dialog = null }) { on(SettingsEvent.SetApiKey(it)) }
        Dialog.PROMPT -> TextDialog("Custom cleanup prompt", state.customPrompt, "Prompt", singleLine = false,
            onDismiss = { dialog = null }) { on(SettingsEvent.SetCustomPrompt(it)) }
        Dialog.RETENTION -> ChoiceDialog("Auto-clear history", HistoryPolicy.RETENTION_CHOICES, state.retentionDays,
            label = { HistoryPolicy.label(it) }, onDismiss = { dialog = null }) { on(SettingsEvent.SetRetention(it)) }
        Dialog.CLEAR_HISTORY -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Clear all history?") },
            text = { Text("Deletes every saved transcript and failed recording.") },
            confirmButton = { TextButton(onClick = { on(SettingsEvent.ClearHistory); dialog = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun CrashBanner(on: (SettingsEvent) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.size(12.dp))
                Text("Phone Whisper crashed last time", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Text("A crash report was saved. Open it to copy or share it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 4.dp, start = 36.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { on(SettingsEvent.DismissCrash) }) { Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer) }
                TextButton(onClick = { on(SettingsEvent.ViewCrash) }) { Text("View report", color = MaterialTheme.colorScheme.onErrorContainer) }
            }
        }
    }
}

@Composable
private fun StatusCard(state: SettingsUiState, on: (SettingsEvent) -> Unit) {
    val ready = state.ready
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_mic), null, modifier = Modifier.size(28.dp),
                    tint = if (ready) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(if (ready) "Ready to dictate" else "Finish setup", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium)
                    Text(if (ready) "Tap the floating mic in any text field" else "A few things are still needed",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            StatusLine(state.micGranted, if (state.micGranted) "Microphone allowed" else "Microphone permission needed",
                action = if (state.micGranted) null else "Allow", onAction = if (state.micGranted) null else ({ on(SettingsEvent.GrantMic) }))
            StatusLine(state.accessibilityOn, if (state.accessibilityOn) "Accessibility service on" else "Accessibility service off",
                action = if (state.accessibilityOn) null else "Turn on", onAction = if (state.accessibilityOn) null else ({ on(SettingsEvent.OpenAccessibility) }))
            StatusLine(state.engineReady, state.engineDetail)
            state.offlineHint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 22.dp, top = 2.dp))
            }
        }
    }
}

@Composable
private fun ConnectionRow(state: SettingsUiState, on: (SettingsEvent) -> Unit) {
    SettingsRow(
        "Test connection",
        subtitle = when {
            state.testing -> "Connecting…"
            state.connection != null -> state.connection
            else -> "Lists the server's models to check the URL and key"
        },
        subtitleColor = when (state.connectionOk) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        subtitleMaxLines = 3,
        onClick = { on(SettingsEvent.TestConnection) },
    ) {
        if (state.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun LocalModelRow(m: LocalModelUi, on: (SettingsEvent) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    SettingsRow(
        m.name,
        subtitle = when {
            m.error != null -> "Download failed: ${m.error}"
            m.progress == -1f -> "Unpacking…"
            m.progress != null -> "Downloading ${(m.progress * 100).toInt()}%"
            else -> m.detail
        },
        subtitleColor = if (m.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (m.installed) Modifier.selectable(m.selected, role = Role.RadioButton) { on(SettingsEvent.LocalModelTap(m.archive)) } else Modifier,
        below = m.progress?.let { p ->
            {
                Spacer(Modifier.height(8.dp))
                if (p < 0f) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }
        },
    ) {
        when {
            m.installed -> {
                if (!m.selected) IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, "Delete ${m.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RadioButton(selected = m.selected, onClick = null)
            }
            m.progress != null -> Unit
            else -> FilledTonalButton(onClick = { on(SettingsEvent.LocalModelTap(m.archive)) },
                contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Get") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${m.name}?") },
        text = { Text("Frees ${m.detail.substringAfterLast("· ")}. You can download it again later.") },
        confirmButton = { TextButton(onClick = { on(SettingsEvent.DeleteLocalModel(m.archive)); confirmDelete = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

// --- Dialogs ---

@Composable
private fun <T> ChoiceDialog(
    title: String, options: List<T>, selected: T,
    label: (T) -> String, detail: (T) -> String? = { null },
    onDismiss: () -> Unit, onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(o == selected, role = Role.RadioButton) { onPick(o); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(label(o), style = MaterialTheme.typography.bodyLarge)
                            detail(o)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val LANGUAGES = listOf("" to "Auto-detect", "en" to "English", "is" to "Icelandic")

fun languageLabel(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second
    ?.let { if (code.isEmpty()) it else "$it ($code)" } ?: code

@Composable
private fun LanguageDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf(if (LANGUAGES.any { it.first == current }) "" else current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Language") },
        text = {
            Column {
                LANGUAGES.forEach { (code, name) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(code == current, role = Role.RadioButton) { onPick(code); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = code == current, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(if (code.isEmpty()) name else "$name ($code)")
                    }
                }
                OutlinedTextField(custom, { custom = it.trim().lowercase().take(8) }, singleLine = true,
                    label = { Text("Other code (ISO 639-1)") }, placeholder = { Text("e.g. da, de") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Used by the cloud model and multilingual offline Whisper.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(enabled = custom.isNotBlank(), onClick = { onPick(custom); onDismiss() }) { Text("Use code") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ServiceDialog(state: SettingsUiState, onDismiss: () -> Unit, onCustom: () -> Unit, onPick: (ServicePreset) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud service") },
        text = {
            Column {
                SettingsViewModel.SERVICES.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().selectable(state.serviceName == p.name, role = Role.RadioButton) { onPick(p); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = state.serviceName == p.name, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(p.name)
                            Text(p.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().selectable(state.serviceName == "Custom", role = Role.RadioButton) { onCustom() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.serviceName == "Custom", onClick = null)
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Custom server…")
                        Text("LiteLLM, self-hosted Whisper, other OpenAI-compatible APIs",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(
    title: String, initial: String, placeholder: String,
    note: String? = null, password: Boolean = false, singleLine: Boolean = true,
    keyboard: KeyboardType = KeyboardType.Text,
    extra: Pair<String, () -> Unit>? = null,
    onDismiss: () -> Unit, onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text(placeholder) },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 5,
                    maxLines = if (singleLine) 1 else 10,
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = {
            Row {
                extra?.let { (label, action) -> TextButton(onClick = { action(); onDismiss() }) { Text(label) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
